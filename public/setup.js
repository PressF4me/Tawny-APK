// Tawny — /setup status page. Polls /setup.json and renders it as a
// checklist with a one-sentence remedy for each bad state. No framework, no
// build step, same "no CDN, nothing external" rule as the rest of the app.
'use strict';

const POLL_MS = 4000;

const ICONS = {
  ok: '<path d="M4 12.5 9 17.5 20 6.5"/>',
  warn: '<path d="M12 4 21.5 20H2.5Z"/><path d="M12 10v4.2"/><circle cx="12" cy="17.3" r=".3" fill="currentColor" stroke="none"/>',
  bad: '<path d="M6 6 18 18M18 6 6 18"/>',
  off: '<path d="M6 12h12"/>'
};

function el(tag, attrs, ...kids) {
  const n = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (k === 'class') n.className = v;
    else if (k === 'html') n.innerHTML = v;
    else if (v != null) n.setAttribute(k, v);
  }
  for (const k of kids) if (k != null) n.append(k.nodeType ? k : document.createTextNode(k));
  return n;
}

function iconSpan(state) {
  return el('span', { class: 'check-icon', html: `<svg viewBox="0 0 24 24">${ICONS[state] || ICONS.off}</svg>` });
}

/**
 * One row of the checklist.
 * state: 'ok' | 'warn' | 'bad' | 'off'
 */
function checkRow({ state, title, sub, detail, remedy, log, extra }) {
  const head = el('div', { class: 'check-title' }, title, sub ? el('small', {}, sub) : null);
  const parts = [iconSpan(state), head];
  if (detail) parts.push(el('p', { class: 'check-detail' }, detail));
  if (remedy) parts.push(el('p', { class: 'check-remedy' }, remedy));
  if (extra) parts.push(extra);
  if (log) parts.push(el('pre', { class: 'check-log' }, log));
  return el('div', { class: `check is-${state}` }, ...parts);
}

function findStep(startup, name) {
  if (!Array.isArray(startup)) return null;
  for (let i = startup.length - 1; i >= 0; i--) {
    if (startup[i] && startup[i].step === name) return startup[i];
  }
  return null;
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    try {
      const ta = document.createElement('textarea');
      ta.value = text;
      ta.style.position = 'fixed';
      ta.style.opacity = '0';
      document.body.append(ta);
      ta.select();
      document.execCommand('copy');
      ta.remove();
      return true;
    } catch { return false; }
  }
}

function toast(msg) {
  const t = document.getElementById('toast');
  if (!t) return;
  t.textContent = msg;
  t.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { t.hidden = true; }, 2200);
}

document.addEventListener('click', async (e) => {
  const btn = e.target.closest('.copy-btn');
  if (!btn) return;
  let text = btn.getAttribute('data-copy-text');
  if (!text) {
    const targetId = btn.getAttribute('data-copy-target');
    const targetEl = targetId && document.getElementById(targetId);
    text = targetEl ? targetEl.textContent : '';
  }
  if (!text) return;
  const ok = await copyText(text);
  toast(ok ? 'Copied' : 'Could not copy — select and copy manually');
  if (ok) { btn.classList.add('done'); setTimeout(() => btn.classList.remove('done'), 1200); }
});

function renderLan(data) {
  const { lan, startup } = data;
  const step = findStep(startup, 'lan_detect');
  if (!lan.ip) {
    return checkRow({
      state: 'bad',
      title: 'LAN network',
      detail: (step && step.detail) || 'No private IPv4 address found on any interface.',
      remedy: 'Run the container with host networking (network_mode: host) so it can see your home LAN.'
    });
  }
  if (lan.looksLikeDockerBridge) {
    return checkRow({
      state: 'warn',
      title: 'LAN network',
      detail: `${lan.ip} on ${lan.cidr || 'an unknown subnet'} — this looks like Docker's own bridge network.`,
      remedy: `Set TS_ROUTES to your real LAN, e.g. 192.168.1.0/24, rather than the address auto-detected here.`
    });
  }
  return checkRow({
    state: 'ok',
    title: 'LAN network',
    detail: `${lan.ip} on ${lan.cidr || 'an unknown subnet'} — the subnet the Monitor phone lives on.`
  });
}

function renderTailscaleJoin(data) {
  const { tailscale, startup } = data;
  const step = findStep(startup, 'tailscale_up') || findStep(startup, 'tailscale_routes');

  // A recorded join failure is checked first, and deliberately so. When
  // `tailscale up` is rejected the entrypoint clears the socket path, so
  // `configured` goes false — and reporting that as "you never set a key"
  // would blame the operator for the one failure this page exists to explain.
  if (step && step.ok === false) {
    return checkRow({
      state: 'bad',
      title: 'Tailscale',
      sub: 'join failed',
      detail: 'The container could not join your tailnet.',
      remedy: 'The auth key is expired, already used, or for a different tailnet — issue a fresh one and restart the container.',
      log: step.detail || null
    });
  }

  if (!tailscale.configured) {
    return checkRow({
      state: 'off',
      title: 'Tailscale',
      sub: 'not set up',
      detail: 'No TS_AUTHKEY set and no host tailscaled socket mounted — this deployment is LAN-only. ' +
        'That is fine on your own Wi-Fi; a remote Viewer will need one of the two Tailscale paths.'
    });
  }

  if (!tailscale.reachable) {
    return checkRow({
      state: 'bad',
      title: 'Tailscale',
      sub: "can't query it",
      detail: tailscale.statusError || 'The tailscaled socket did not answer.',
      remedy: 'Restart the container; if this persists, check that tailscaled is actually running.'
    });
  }

  const peerList = (tailscale.peers || []).length
    ? el('div', { class: 'peer-list' }, ...tailscale.peers.map((p) =>
        el('span', { class: 'peer-row' },
          el('span', { class: `dot ${p.online ? 'on' : ''}` }),
          p.name || '(unnamed)')))
    : null;

  if (!tailscale.online) {
    return checkRow({
      state: 'warn',
      title: 'Tailscale',
      sub: 'joined, offline',
      detail: `Joined as ${tailscale.dnsName || 'this node'}, but the tailnet currently shows it offline.`,
      remedy: 'Give it a few seconds and reload — a container that just started can take a moment to report in.'
    });
  }

  return checkRow({
    state: 'ok',
    title: 'Tailscale',
    sub: 'joined',
    detail: tailscale.dnsName || 'Joined the tailnet.',
    extra: peerList
  });
}

function renderRoute(data) {
  const { tailscale } = data;
  if (!tailscale.configured || !tailscale.reachable) return null;
  const advertised = tailscale.advertisedRoutes || [];
  if (!advertised.length) return null; // TS_ROUTES=off, or nothing to advertise — nothing to check

  const pending = tailscale.pendingRoutes || [];
  if (pending.length) {
    const cidr = pending[0];
    return checkRow({
      state: 'bad',
      title: 'Subnet route',
      sub: 'not approved yet',
      detail: `${cidr} is advertised but not yet approved — no media can reach it from outside your LAN.`,
      remedy: `Approve it once: login.tailscale.com/admin/machines → this node → Edit route settings → tick ${cidr}.`,
      extra: (() => {
        const row = el('div', { class: 'copy-row' });
        row.append(
          el('code', { class: 'pair-url', id: 'route-cidr' }, cidr),
          el('button', { class: 'copy-btn', type: 'button', 'data-copy-text': cidr }, 'Copy')
        );
        return row;
      })()
    });
  }
  return checkRow({
    state: 'ok',
    title: 'Subnet route',
    sub: 'approved',
    detail: `${tailscale.approvedRoutes.join(', ')} is approved. Remote viewing will work.`
  });
}

function renderServe(data) {
  const { tailscale, startup } = data;
  const step = findStep(startup, 'tailscale_serve');
  if (!step) {
    if (!tailscale.configured) return null; // nothing to serve without a tailnet
    return checkRow({
      state: 'off',
      title: 'tailscale serve',
      detail: 'Not attempted (TS_SERVE=off, or Tailscale never joined).'
    });
  }
  if (!step.ok) {
    return checkRow({
      state: 'bad',
      title: 'tailscale serve',
      sub: 'failed',
      detail: 'The ts.net URL will not resolve — Tailscale could not publish this app.',
      remedy: 'Check the log below; a stale `tailscale serve` config from a previous run is the usual cause — `tailscale serve reset` on the node fixes it.',
      log: step.detail || null
    });
  }
  const url = tailscale.dnsName ? `https://${tailscale.dnsName}/` : step.detail;
  return checkRow({
    state: 'ok',
    title: 'tailscale serve',
    detail: 'Publishing this app with a real certificate.',
    extra: (() => {
      const row = el('div', { class: 'copy-row' });
      row.append(
        el('code', { class: 'pair-url', id: 'serve-url' }, url),
        el('button', { class: 'copy-btn', type: 'button', 'data-copy-text': url }, 'Copy')
      );
      return row;
    })()
  });
}

function renderCoturn(data) {
  const { coturn } = data;
  if (!coturn.embedded) {
    return checkRow({
      state: 'off',
      title: 'TURN relay',
      detail: 'Embedded TURN is disabled (TURN_EMBEDDED=off). Fine if the subnet route above is approved.'
    });
  }
  if (!coturn.secretExists) {
    return checkRow({
      state: 'bad',
      title: 'TURN relay',
      detail: 'No shared secret file found for coturn.',
      remedy: 'Restart the container — entrypoint.sh generates this secret at every start.'
    });
  }
  if (!coturn.listening) {
    return checkRow({
      state: 'warn',
      title: 'TURN relay',
      detail: `Nothing is answering on :${coturn.port} yet.`,
      remedy: 'Give it a few seconds and reload; if it stays this way, check the container logs for a coturn startup error.'
    });
  }
  return checkRow({
    state: 'ok',
    title: 'TURN relay',
    detail: `Listening on :${coturn.port} — the unattended fallback if a direct route ever can't be used.`
  });
}

function renderHero(data) {
  const hero = document.getElementById('hero');
  const { tailscale } = data;
  const pending = (tailscale.pendingRoutes || []).length > 0;
  const ready = tailscale.configured && tailscale.reachable && tailscale.online &&
    tailscale.dnsName && !pending;
  if (!ready) { hero.classList.remove('show'); return; }
  const url = `https://${tailscale.dnsName}/`;
  document.getElementById('hero-url').textContent = url;
  document.querySelector('#hero .copy-btn').setAttribute('data-copy-text', url);
  hero.classList.add('show');
}

function render(data) {
  renderHero(data);
  const list = document.getElementById('checks');
  list.replaceChildren(...[
    renderLan(data),
    renderTailscaleJoin(data),
    renderRoute(data),
    renderServe(data),
    renderCoturn(data)
  ].filter(Boolean));

  const dot = document.getElementById('refresh-dot');
  const label = document.getElementById('refresh-label');
  dot.className = 'dot on';
  const t = new Date(data.generatedAt);
  label.textContent = Number.isNaN(t.getTime())
    ? 'Updated'
    : `Updated ${t.toLocaleTimeString()}`;
  document.getElementById('fetch-error').hidden = true;
}

async function poll() {
  try {
    const res = await fetch('/setup.json', { cache: 'no-store' });
    if (!res.ok) throw new Error('http ' + res.status);
    const data = await res.json();
    render(data);
  } catch {
    document.getElementById('refresh-dot').className = 'dot warn';
    document.getElementById('refresh-label').textContent = 'Could not refresh';
    document.getElementById('fetch-error').hidden = false;
  } finally {
    setTimeout(poll, POLL_MS);
  }
}

poll();
