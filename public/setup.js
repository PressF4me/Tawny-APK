// Tawny — the setup flow. Polls /setup.json and renders it as an ordered set
// of steps rather than a flat status list: a finished step collapses to its
// title, and whatever you still have to do stays open with the reason it
// matters and a link straight to the page that does it.
//
// No framework, no build step, same "no CDN, nothing external" rule as the
// rest of the app.
'use strict';

const POLL_MS = 4000;

const LINK = {
  keys:     'https://login.tailscale.com/admin/settings/keys',
  machines: 'https://login.tailscale.com/admin/machines',
  download: 'https://tailscale.com/download',
  subnets:  'https://tailscale.com/kb/1019/subnets'
};

const ICONS = {
  tick: '<path d="M4 12.5 9 17.5 20 6.5"/>',
  bang: '<path d="M12 4 21.5 20H2.5Z"/><path d="M12 10v4.2"/><circle cx="12" cy="17.3" r=".3" fill="currentColor" stroke="none"/>',
  cross: '<path d="M6 6 18 18M18 6 6 18"/>',
  out:  '<path d="M14 4h6v6"/><path d="M20 4 11 13"/><path d="M18 14v5a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1h5"/>'
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

const svg = (d, cls) => el('span', { class: cls || '', html: `<svg viewBox="0 0 24 24">${d}</svg>` });

/** An external link, always marked as one. */
function goLink(href, label) {
  const a = el('a', { class: 'go', href, target: '_blank', rel: 'noopener noreferrer' }, label);
  a.append(svg(ICONS.out));
  return a;
}

/** A collapsible plain-language explainer. */
function why(question, ...paragraphs) {
  return el('details', { class: 'why' },
    el('summary', {}, question),
    ...paragraphs.map((p) => el('p', {}, p)));
}

function copyRow(text) {
  return el('div', { class: 'copy-row' },
    el('code', { class: 'pair-url' }, text),
    el('button', { class: 'copy-btn', type: 'button', 'data-copy-text': text }, 'Copy'));
}

/**
 * One step. state: 'done' | 'now' | 'todo' | 'bad'
 * `now` and `bad` stay open; `done` collapses to its title.
 */
function stepRow(n, { state, title, tag, body }) {
  const badge = state === 'done'
    ? svg(ICONS.tick, 'step-badge')
    : state === 'bad'
      ? svg(ICONS.cross, 'step-badge')
      : el('span', { class: 'step-badge' }, String(n));

  const head = el('p', { class: 'step-title' }, title, tag ? el('small', {}, tag) : null);
  const kids = (body || []).filter(Boolean);
  return el('div', { class: `step is-${state}` },
    badge,
    el('div', { class: 'step-main' }, head,
      kids.length ? el('div', { class: 'step-body' }, ...kids) : null));
}

/**
 * The most recent record for a step. The state file is append-only, so a
 * rejected key followed by a good one leaves both behind — only the last one
 * describes the present.
 */
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
  const text = btn.getAttribute('data-copy-text') || '';
  if (!text) return;
  const ok = await copyText(text);
  toast(ok ? 'Copied' : 'Could not copy — select and copy manually');
  if (ok) { btn.classList.add('done'); setTimeout(() => btn.classList.remove('done'), 1200); }
});

document.addEventListener('click', (e) => {
  if (!e.target.closest('#devices-ack')) return;
  try { localStorage.setItem(DEVICES_ACK, '1'); } catch { /* private window */ }
  tick();
});

// "Use it on this Wi-Fi only." Recorded on the container so it settles the
// question for every device in the house, not just this browser. The cookie
// is only a fallback for a deployment with no writable volume.
document.getElementById('skip-link').addEventListener('click', async (e) => {
  e.preventDefault();
  try {
    await fetch('/setup/skip', { method: 'POST' });
  } catch { /* offline — the cookie below still helps this device */ }
  document.cookie = 'tawny_setup_done=1; path=/; max-age=31536000; samesite=lax';
  location.href = '/';
});

/* ------------------------------------------------------- the finish line */

/** The address, as something you can actually click. */
function openLink(url) {
  return el('div', { class: 'url-hero' },
    el('a', { class: 'open-app', href: url, target: '_blank', rel: 'noopener noreferrer' },
      el('span', { class: 'open-app-label' }, 'Open Tawny'),
      el('span', { class: 'open-app-url' }, url),
      svg(ICONS.out, 'open-app-out')),
    el('button', { class: 'copy-btn', type: 'button', 'data-copy-text': url }, 'Copy'));
}

// Fires once when the setup actually completes — on the transition, or the
// first time a finished deployment is ever opened. Not on every poll, and not
// on every reload for the rest of the deployment's life.
let celebrated = false;
let sawIncomplete = false;

function confetti() {
  // A full-screen animation is precisely what this setting is for.
  try {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
  } catch { /* no matchMedia — carry on */ }

  const cv = el('canvas', { class: 'confetti', 'aria-hidden': 'true' });
  document.body.append(cv);
  const ctx = cv.getContext('2d');
  if (!ctx) return cv.remove();

  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const W = cv.width = Math.floor(window.innerWidth * dpr);
  const H = cv.height = Math.floor(window.innerHeight * dpr);
  cv.style.width = window.innerWidth + 'px';
  cv.style.height = window.innerHeight + 'px';

  const colours = ['#d24b6d', '#5b93b8', '#e0a63c', '#a83c58', '#f7f1e8'];
  const bits = [];
  for (let i = 0; i < 150; i++) {
    bits.push({
      x: Math.random() * W,
      y: -Math.random() * H * 0.5,
      w: (5 + Math.random() * 7) * dpr,
      h: (8 + Math.random() * 10) * dpr,
      vx: (Math.random() - 0.5) * 2.6 * dpr,
      vy: (2 + Math.random() * 3.4) * dpr,
      rot: Math.random() * Math.PI,
      vr: (Math.random() - 0.5) * 0.24,
      c: colours[(Math.random() * colours.length) | 0]
    });
  }

  const DUR = 3400;
  const t0 = performance.now();
  const frame = (now) => {
    const t = now - t0;
    ctx.clearRect(0, 0, W, H);
    const fade = t > DUR - 900 ? Math.max(0, (DUR - t) / 900) : 1;
    for (const b of bits) {
      b.x += b.vx; b.y += b.vy; b.rot += b.vr; b.vy += 0.035 * dpr;
      ctx.save();
      ctx.globalAlpha = fade;
      ctx.translate(b.x, b.y);
      ctx.rotate(b.rot);
      ctx.fillStyle = b.c;
      ctx.fillRect(-b.w / 2, -b.h / 2, b.w, b.h);
      ctx.restore();
    }
    if (t < DUR) requestAnimationFrame(frame);
    else cv.remove();
  };
  requestAnimationFrame(frame);
}

function maybeCelebrate() {
  if (celebrated) return;
  celebrated = true;
  let firstEver = false;
  try {
    firstEver = localStorage.getItem('tawny.celebrated') !== '1';
    localStorage.setItem('tawny.celebrated', '1');
  } catch { /* private window — then it just fires on the transition */ }
  if (firstEver || sawIncomplete) confetti();
}

/* ------------------------------------------------------------------ steps */

function stepMachine(data) {
  const { lan } = data;
  const step = findStep(data.startup, 'lan_detect');

  if (!lan.cidr || (step && step.ok === false)) {
    return {
      state: 'bad',
      title: 'This machine',
      tag: 'no network found',
      body: [
        el('p', { class: 'step-say' }, 'Tawny could not find a normal home-network address on this machine, so it does not know which network your pet camera phone is on.'),
        el('p', { class: 'step-do' }, 'Set TS_ROUTES in your .env to your home network, then restart the container. It usually looks like 192.168.1.0/24 — the same as your router’s address with a 0 at the end.')
      ]
    };
  }

  if (lan.looksLikeDockerBridge) {
    return {
      state: 'bad',
      title: 'This machine',
      tag: 'wrong network',
      body: [
        el('p', { class: 'step-say' }, `Tawny found ${lan.cidr}, which is Docker’s own internal network rather than your home Wi-Fi. Your phone is not on that network, so it could never be reached.`),
        el('p', { class: 'step-do' }, 'This happens when the container is not using host networking. Either turn host networking on, or set TS_ROUTES in your .env to your real home network (for example 192.168.1.0/24) and restart the container.')
      ]
    };
  }

  return {
    state: 'done',
    title: 'This machine',
    tag: lan.cidr,
    body: [el('p', { class: 'step-say' }, `Found on your home network at ${lan.ip}.`)]
  };
}

// While a join is in flight the poll must not rebuild the steps underneath it,
// or the button and its message vanish mid-request.
let joinBusy = false;

function joinForm() {
  const input = el('input', {
    id: 'join-key', class: 'join-input', type: 'text',
    placeholder: 'tskey-auth-…',
    autocomplete: 'off', autocapitalize: 'off', autocorrect: 'off', spellcheck: 'false'
  });
  const btn = el('button', { id: 'join-go', class: 'wide primary', type: 'submit' }, 'Connect');
  const msg = el('p', { id: 'join-msg', class: 'join-msg', hidden: 'hidden' });

  const form = el('form', { id: 'join-form', class: 'join' },
    el('label', { class: 'join-label', for: 'join-key' }, 'Paste your auth key'),
    input, btn, msg);

  form.addEventListener('submit', async (e) => {
    e.preventDefault();
    const key = input.value.trim();
    if (!key) return;

    joinBusy = true;
    btn.disabled = true;
    btn.textContent = 'Connecting…';
    msg.hidden = true;
    msg.className = 'join-msg';

    try {
      const res = await fetch('/setup/join', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ authkey: key })
      });
      const out = await res.json().catch(() => ({}));
      if (res.ok && out.ok) {
        msg.className = 'join-msg is-ok';
        msg.textContent = 'Joined. Checking what is left to do…';
        msg.hidden = false;
        input.value = '';
        joinBusy = false;
        return tick();
      }
      msg.className = 'join-msg is-bad';
      msg.textContent = out.error || `Could not join (${res.status}).`;
      msg.hidden = false;
    } catch {
      msg.className = 'join-msg is-bad';
      msg.textContent = 'Could not reach the container. It may be restarting.';
      msg.hidden = false;
    }
    btn.disabled = false;
    btn.textContent = 'Connect';
    joinBusy = false;
  });

  return form;
}

function stepConnect(data) {
  const { tailscale } = data;
  const up = findStep(data.startup, 'tailscale_up') || findStep(data.startup, 'tailscale_routes');
  const serve = findStep(data.startup, 'tailscale_serve');
  const short = (tailscale.dnsName || '').split('.')[0];

  const explain = why('What is Tailscale, and why does Tawny want it?',
    'Tailscale is a free private network. You install it on the devices you own, sign in on each one, and from then on they can reach each other from anywhere — as if they were all sitting on your home Wi-Fi.',
    'Tawny uses it for two things: it gives this container a real web address with a proper certificate (browsers refuse to hand over a microphone without one, so talk-back depends on it), and it lets you watch from outside the house without opening any ports on your router.');

  // Already on a tailnet. How it got there decides what there is to say — and
  // when the machine was already running Tailscale, the honest answer is
  // "nothing, this step did itself".
  if (tailscale.loggedIn) {
    if (serve && serve.ok === false) {
      return {
        state: 'bad',
        title: 'Connect to Tailscale',
        tag: 'no web address',
        body: [
          el('p', { class: 'step-say' }, 'You are on the network, but Tailscale could not publish Tawny at a web address — so the https:// address will not load, and talk-back will not work.'),
          el('p', { class: 'step-do' }, 'A leftover setting from an earlier run is the usual cause. Run tailscale serve reset on this machine and restart the container.'),
          serve.detail ? el('pre', { class: 'step-log' }, serve.detail) : null
        ]
      };
    }
    return {
      state: 'done',
      title: 'Connect to Tailscale',
      tag: tailscale.mode === 'host'
        ? `using this machine${short ? ` (${short})` : ''}`
        : `joined${short ? ` as ${short}` : ''}`
    };
  }

  // A daemon is running inside the container, logged out. This is the only
  // situation where an auth key is worth asking anyone for.
  if (tailscale.configured && tailscale.reachable) {
    const retry = up && up.ok === false;
    return {
      state: retry ? 'bad' : 'now',
      title: 'Connect to Tailscale',
      tag: retry ? 'key rejected — try another' : 'needs you',
      body: [
        el('p', { class: 'step-say' }, retry
          ? 'That key was refused. Usually it has expired, it was a one-off key that has already been used, or it belongs to a different Tailscale account. Paste a fresh one — nothing needs restarting.'
          : 'This machine is not on a Tailscale network yet. One key joins it, which is what lets you watch from outside the house and what makes talk-back work at all.'),

        retry && up.detail ? el('pre', { class: 'step-log' }, up.detail) : null,

        el('div', { class: 'step-do' }, 'Get a key:',
          el('ol', {},
            el('li', {}, 'Open the auth keys page. A free account covers a household.'),
            el('li', {}, 'Press Generate auth key.'),
            el('li', {}, 'Turn Reusable ON. Leave Ephemeral OFF — an ephemeral key makes Tawny disappear from your network every time it restarts.'),
            el('li', {}, 'Copy it, and paste it below.'))),
        goLink(LINK.keys, 'Get an auth key'),

        joinForm(),

        why('Other ways to do this step',
          'If this machine already runs Tailscale for its own reasons, you do not need a key at all: mount /var/run/tailscale into the container and Tawny will drive the daemon that is already signed in.',
          'You can also set TS_AUTHKEY as a setting instead of pasting it. In Portainer: Stacks → your stack → Editor → the Environment variables box. With Compose: TS_AUTHKEY=… in a .env file beside docker-compose.yml. With docker run: -e TS_AUTHKEY=…. Pasting above does exactly the same thing, and either way the key is not written to any file — Tailscale keeps its own state in the container’s data volume.'),

        explain
      ]
    };
  }

  // No Tailscale in the image at all, so nothing here can fix it.
  return {
    state: 'now',
    title: 'Connect to Tailscale',
    tag: 'needs a setting',
    body: [
      el('p', { class: 'step-say' }, 'There is no Tailscale running in this container, so a key cannot be applied from here.'),
      el('div', { class: 'step-do' }, 'Set TS_AUTHKEY and restart:',
        el('ol', {},
          el('li', {}, 'Portainer: Stacks → your tawny stack → Editor → Environment variables → add TS_AUTHKEY → Update the stack.'),
          el('li', {}, 'Compose: put TS_AUTHKEY=tskey-auth-… in a .env file beside docker-compose.yml, then docker compose up -d.'),
          el('li', {}, 'docker run: add -e TS_AUTHKEY=tskey-auth-… and start it again.'))),
      goLink(LINK.keys, 'Get an auth key'),
      explain
    ]
  };
}

function stepRoute(data) {
  const { tailscale } = data;
  if (!tailscale.loggedIn) {
    return { state: 'todo', title: 'Let your devices reach the camera', tag: 'after the step above' };
  }

  const pending = tailscale.pendingRoutes || [];
  const approved = tailscale.approvedRoutes || [];
  const cidr = pending[0] || approved[0] || data.lan.cidr;

  const explain = why('What am I approving, exactly?',
    `Your pet camera phone sits on your home network at an address like ${data.lan.ip || '192.168.1.50'}. A device somewhere else has no way to reach an address like that — it is private to your house.`,
    `Approving this route tells Tailscale that this machine can pass traffic through to your home network, so a phone or laptop out in the world can reach the camera directly. Tailscale makes you approve it by hand, once, because it is your network and it will not open it without asking.`);

  if (!pending.length && approved.length) {
    return {
      state: 'done',
      title: 'Let your devices reach the camera',
      tag: 'approved',
      body: [el('p', { class: 'step-say' }, `${approved.join(', ')} is approved.`)]
    };
  }

  if (!pending.length && !approved.length) {
    return {
      state: 'todo',
      title: 'Let your devices reach the camera',
      tag: 'nothing to approve',
      body: [
        el('p', { class: 'step-say' }, 'Tawny is not offering a route to your home network, so you will only be able to watch from a device on this same Wi-Fi.'),
        el('p', { class: 'step-do' }, 'If you want to watch from outside the house, remove TS_ROUTES=off from your .env (or set it to your home network) and restart the container.'),
        explain
      ]
    };
  }

  return {
    state: 'now',
    title: 'Let your devices reach the camera',
    tag: 'needs you — one click',
    body: [
      el('p', { class: 'step-say' }, 'Everything is running. Tailscale just needs your permission to carry traffic to your home network — this is the one step nothing can do for you.'),
      el('div', { class: 'step-do' }, 'On the page below:',
        el('ol', {},
          el('li', {}, `Find the machine called ${tailscale.dnsName ? tailscale.dnsName.split('.')[0] : 'tawny'} in the list.`),
          el('li', {}, 'Open its ⋯ menu and choose Edit route settings.'),
          el('li', {}, `Tick ${cidr} and save.`))),
      goLink(LINK.machines, 'Open Tailscale machines'),
      copyRow(cidr),
      why('Where do I click?',
        'The machine list shows one row per device. The row for this container has a "Subnets" badge on it — that badge is what you are approving.',
        'If you would rather read Tailscale’s own explanation of subnet routes first, their documentation covers it at tailscale.com/kb/1019/subnets.'),
      explain
    ]
  };
}

const DEVICES_ACK = 'tawny.devicesAck';

function devicesAcked() {
  try { return localStorage.getItem(DEVICES_ACK) === '1'; } catch { return false; }
}

function stepDevices(data) {
  const { tailscale } = data;
  const ready = tailscale.loggedIn && !(tailscale.pendingRoutes || []).length
    && (tailscale.approvedRoutes || []).length;

  const peers = (tailscale.peers || []).filter((p) => p.name);
  const peerBox = peers.length
    ? el('div', { class: 'peers' }, ...peers.map((p) =>
        el('span', { class: `peer${p.online ? '' : ' off'}` },
          el('span', { class: `dot${p.online ? ' on' : ''}` }), p.name)))
    : null;

  // Nothing in this container can see whether a peer accepts routes, so this
  // step cannot verify itself. Left as an open action it would sit here for
  // ever, telling people to do something they have already done — so it is
  // theirs to close.
  if (devicesAcked()) {
    return { state: 'done', title: 'Put your devices on the network', tag: 'you confirmed this' };
  }
  if (!ready) {
    return { state: 'todo', title: 'Put your devices on the network', tag: 'after the steps above' };
  }

  const ack = el('button', { class: 'wide', type: 'button', id: 'devices-ack' },
    'Done — my devices are set up');

  return {
    state: 'now',
    title: 'Put your devices on the network',
    tag: 'needs you',
    body: [
      el('p', { class: 'step-say' }, 'Two devices need Tailscale installed and signed in to the same account: the phone that watches the pet, and whatever you want to watch from.'),
      el('div', { class: 'step-do' }, 'On the device you will watch from, after installing Tailscale:',
        el('ol', {},
          el('li', {}, 'iPhone or Android: open the Tailscale app and turn on "Use Tailscale subnets".'),
          el('li', {}, 'Mac or Windows: nothing to do, it is on by default.'),
          el('li', {}, 'Linux: run sudo tailscale up --accept-routes.'))),
      goLink(LINK.download, 'Install Tailscale'),
      peerBox ? el('p', { class: 'step-say' }, 'Signed in to your network right now:') : null,
      peerBox,
      el('p', { class: 'step-say' }, 'Tawny cannot check this one from inside the container — the list above shows which of your devices are on the network, not whether they accept routes. If you have already done it, close this step off:'),
      ack
    ]
  };
}

function stepWatch(data) {
  const { tailscale } = data;
  const ready = tailscale.loggedIn && tailscale.reachable && tailscale.dnsName
    && !(tailscale.pendingRoutes || []).length
    && !(data.startup || []).some((s) => s.ok === false);

  if (!ready) {
    return { state: 'todo', title: 'Start watching', tag: 'once the steps above are done' };
  }

  const url = `https://${tailscale.dnsName}/`;
  return {
    state: 'now',
    title: 'Start watching',
    tag: 'you’re ready',
    body: [
      el('p', { class: 'step-say' }, el('b', {}, 'This is the address to open'), ' — on your laptop, your phone, anywhere with Tailscale signed in:'),
      openLink(url),
      el('p', { class: 'step-say' }, 'Always use this https:// address, not the plain one with a port number on the end. Browsers only hand over a microphone on a secure address, so talk-back goes silently missing on the other one.'),

      el('div', { class: 'step-do' }, 'To start a session:',
        el('ol', {},
          el('li', {}, 'On the old phone you are leaving with the pet: open Tawny, choose The Monitor, and let it use the camera. It shows a QR code.'),
          el('li', {}, 'On the device you are watching from: open the address above, choose Viewer, and press Scan.'),
          el('li', {}, 'Point it at the phone’s QR code. That is the pairing done.'))),

      el('p', { class: 'step-say' }, 'You get video, sound, and hold-to-talk back to the phone. The pairing code lasts ten minutes, so if you leave it too long, the phone will show a fresh one.'),

      why('Anything worth doing before I walk away?',
        'Give the pet camera phone a fixed address in your router settings — a DHCP reservation. Its address is baked into each pairing code, so if the router hands it a different one later, the code stops working and you have to scan again.',
        'Leave the phone plugged in. A screen-off phone keeps streaming, but a flat one does not.')
    ]
  };
}

/* ---------------------------------------------------------------- verdict */

function renderVerdict(data, defs) {
  const icon = document.getElementById('verdict-icon');
  const box = document.getElementById('verdict');
  const title = document.getElementById('verdict-title');
  const say = document.getElementById('verdict-say');
  const extra = document.getElementById('verdict-extra');
  extra.textContent = '';

  const { tailscale, lan } = data;
  // Read the failure off the steps actually on screen, not off the log. The
  // banner disagreeing with the list below it is worse than either being
  // wrong on its own.
  const failed = defs.some((d) => d.state === 'bad');
  const pending = (tailscale.pendingRoutes || []).length > 0;
  const badLan = !lan.cidr || lan.looksLikeDockerBridge;
  const allGood = tailscale.loggedIn && tailscale.reachable && !failed && !pending
    && !badLan && !!tailscale.dnsName;

  const set = (cls, ico, h, p) => {
    box.className = `verdict is-${cls}`;
    icon.innerHTML = `<svg viewBox="0 0 24 24">${ico}</svg>`;
    title.textContent = h;
    say.textContent = p;
  };

  if (allGood) {
    set('ok', ICONS.tick, 'Tawny is ready',
      'Everything is connected. Open this on whatever you want to watch from — step 5 walks through pairing the camera phone.');
    box.classList.add('is-finish');
    extra.append(openLink(`https://${tailscale.dnsName}/`));
    maybeCelebrate();
    return;
  }
  sawIncomplete = true;
  if (failed || badLan) {
    set('bad', ICONS.cross, 'Something needs fixing',
      'One of the steps below did not work. Each one says what to do about it.');
    return;
  }
  if (pending) {
    set('warn', ICONS.bang, 'One click left',
      'Everything is running. Tailscale needs you to approve one thing before you can watch from outside the house.');
    return;
  }
  if (!tailscale.loggedIn) {
    set('warn', ICONS.bang, 'Start here',
      tailscale.configured
        ? 'Tawny is installed and running. It needs to join your Tailscale network before you can watch from anywhere — step 2 below.'
        : 'Tawny is running on this Wi-Fi. Step 2 below connects it to your Tailscale network so you can watch from anywhere.');
    return;
  }
  set('warn', ICONS.bang, 'Not finished yet',
    'Tawny is running on this Wi-Fi. Follow the steps below to watch from anywhere.');
}

/** A bar per step, and "Step 3 of 5" — so the page says where you are. */
function renderProgress(defs) {
  const track = document.getElementById('progress-track');
  const label = document.getElementById('progress-label');
  track.textContent = '';
  for (const d of defs) {
    const cls = d.state === 'done' ? 'done' : d.state === 'now' ? 'now' : d.state === 'bad' ? 'bad' : '';
    track.append(el('i', { class: cls }));
  }
  const done = defs.filter((d) => d.state === 'done').length;
  const brokenAt = defs.findIndex((d) => d.state === 'bad');
  const current = defs.findIndex((d) => d.state === 'now' || d.state === 'bad');
  label.textContent = brokenAt >= 0
    ? `Step ${brokenAt + 1} of ${defs.length} — needs fixing`
    : done === defs.length
      ? 'All done'
      : `Step ${current < 0 ? done + 1 : current + 1} of ${defs.length}`;
}

/* -------------------------------------------------------------- the loop */

function render(data) {
  document.getElementById('fetch-error').hidden = true;

  const host = document.getElementById('steps');

  // Never rebuild the steps out from under someone typing a key into them: a
  // four-second poll would otherwise wipe a half-pasted key, or the error
  // message explaining why the last one failed.
  const keyIn = document.getElementById('join-key');
  if (joinBusy || (keyIn && document.activeElement === keyIn)) return;
  const carried = keyIn ? keyIn.value : '';

  const defs = [stepMachine(data), stepConnect(data), stepRoute(data), stepDevices(data), stepWatch(data)];

  // Exactly one step is the card. Several steps can legitimately be actionable
  // at once — "add your devices" and "start watching" both open the moment the
  // route is approved — but two competing cards is precisely the "where do I
  // look" problem the layout exists to solve. The first one wins; the rest
  // wait their turn as quiet rows.
  let claimed = false;
  for (const d of defs) {
    if (d.state !== 'now' && d.state !== 'bad') continue;
    if (claimed) d.state = 'todo';
    else claimed = true;
  }

  host.textContent = '';
  defs.forEach((d, i) => host.append(stepRow(i + 1, d)));
  renderProgress(defs);
  renderVerdict(data, defs);

  const fresh = document.getElementById('join-key');
  if (fresh && carried) fresh.value = carried;

  // Only worth offering once we know there is nothing to finish here.
  document.getElementById('skip-line').hidden = data.tailscale.loggedIn;

  const coturn = data.coturn || {};
  if (coturn.embedded && !coturn.listening) {
    host.append(el('p', { class: 'warn' },
      'The built-in relay is not answering on port 3478. Video will still work in almost every home; this only matters on networks that block direct connections.'));
  }
}

let timer = null;

async function tick() {
  const dot = document.getElementById('refresh-dot');
  const label = document.getElementById('refresh-label');
  try {
    const res = await fetch('/setup.json', { cache: 'no-store' });
    if (!res.ok) throw new Error(String(res.status));
    render(await res.json());
    dot.className = 'dot on';
    label.textContent = `Checked ${new Date().toLocaleTimeString()} — rechecks every few seconds`;
  } catch {
    document.getElementById('fetch-error').hidden = false;
    dot.className = 'dot live';
    label.textContent = 'Cannot reach the container';
  }
  clearTimeout(timer);
  timer = setTimeout(tick, POLL_MS);
}

tick();
document.addEventListener('visibilitychange', () => { if (!document.hidden) tick(); });
