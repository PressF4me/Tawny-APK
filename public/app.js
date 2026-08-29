// Tawny — Pet Monitor client.
//
// A channel is a name plus a 128-bit key held only on paired devices. The
// server is told sha256(key) and never the key itself, so it cannot enumerate
// or join channels, and a channel cannot be guessed by name.

import qrcode from './vendor/qrcode.mjs';

const $ = (s) => document.querySelector(s);
const CHANNELS = 'tawny.channels.v1';
const PROFILES = 'tawny.profiles.v1';
const ONBOARDED = 'tawny.onboarded.v1';

const el = {
  channels: $('#channels'), chList: $('#ch-list'), chEmpty: $('#ch-empty'),
  chAdd: $('#ch-add'), chScan: $('#ch-scan'), chNote: $('#ch-note'),
  role: $('#role'), roleName: $('#role-name'), roleNote: $('#role-note'),
  join: $('#join'), joinName: $('#join-name'),
  live: $('#live'), remote: $('#remote'), remoteAudio: $('#remote-audio'),
  local: $('#local'), loader: $('#loader'), peerAudio: $('#peer-audio'),
  peercount: $('#peercount'),
  sas: $('#sas'), sascode: $('#sas-code'), saschip: $('#sas-chip'),
  dot: $('#dot'), statusline: $('#statusline'), channel: $('#channel'),
  meter: $('#meter'), dimMeter: $('#dim-meter'), dimmer: $('#dimmer'),
  talkflag: $('#talkflag'), chimes: $('#chimes'), toast: $('#toast'),
  cViewer: $('#controls-viewer'), cStation: $('#controls-station'),
  pair: $('#pair'), pairName: $('#pair-name'), qr: $('#qr'),
  pairProfile: $('#pair-profile'), pairCustomWrap: $('#pair-custom-wrap'),
  pairCustom: $('#pair-custom'), pairUrl: $('#pair-url'), pairWarn: $('#pair-warn'),
  editor: $('#editor'), editorTitle: $('#editor-title'), editorName: $('#editor-name'),
  editorHint: $('#editor-hint'),
  scanner: $('#scanner'), scanVideo: $('#scan-video'), scanHint: $('#scan-hint'),
  welcome: $('#welcome'), setupRole: $('#setup-role'), setupNote: $('#setup-note'),
  watchPair: $('#watch-pair'), wpQr: $('#wp-qr'), wpStatus: $('#wp-status'),
  wpWarn: $('#wp-warn')
};

const S = {
  role: null, channel: null, roomId: null, signalUrl: null, nativeShell: false,
  cfg: { stun: [], authRequired: false }, token: '',
  // Signaling transports (0-2). A Handheld commits to one; the Watcher may hold
  // its LAN relay and the rendezvous at once.
  signals: [], committedTag: null, raceTimer: null, myId: null,
  // One RTCPeerConnection per remote peer. A Handheld holds exactly one (to the
  // Watcher); the Watcher fans out — one per Handheld, up to MAX_VIEWERS.
  peers: new Map(),           // id -> { id, role, transport, pc, stream, iceKick, negotiating, talking, audioEl }
  local: null, remoteStream: null,
  ice: [], iceTimer: null, relayOnly: false,  // filled by fetchIce() when a rendezvous is configured
  facing: 'environment', micOn: true, camSending: false,
  wake: null, meterStop: null, ac: null, closing: false,
  editing: null, scanStop: null, pending: null,
  featured: null,
  cameras: [], cameraIndex: 0, zoomLevel: 1.0, zoomHardware: false, stationZoomSupported: false
};

// 1 Watcher + up to 5 Handhelds. Kept in step with server.js MAX_PER_ROOM and
// LocalWeb.kt MAX_PER_ROOM (both 6) and the rendezvous Durable Object.
const MAX_VIEWERS = 5;

// --------------------------------------------------------------- storage

const readJSON = (k, fallback) => {
  try { return JSON.parse(localStorage.getItem(k)) ?? fallback; }
  catch { return fallback; }
};
const writeJSON = (k, v) => {
  try { localStorage.setItem(k, JSON.stringify(v)); } catch {}
};

const getChannels = () => readJSON(CHANNELS, []);
const setChannels = (list) => writeJSON(CHANNELS, list);

function b64url(bytes) {
  let s = '';
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function newKey() {
  const b = new Uint8Array(16);
  crypto.getRandomValues(b);
  return b64url(b);
}

async function roomIdFor(key) {
  const data = new TextEncoder().encode(`tawny-room-v1|${key}`);
  const digest = await crypto.subtle.digest('SHA-256', data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0'))
    .join('').slice(0, 32);
}

// --------------------------------------------------------------- helpers

function toast(msg, ms = 2600) {
  el.toast.textContent = msg;
  el.toast.hidden = false;
  clearTimeout(toast._t);
  toast._t = setTimeout(() => { el.toast.hidden = true; }, ms);
}

function note(node, msg) {
  if (!msg) { node.hidden = true; return; }
  node.textContent = msg;
  node.hidden = false;
}

function show(screen) {
  const screens = [
    el.welcome, el.setupRole, el.watchPair,
    el.channels, el.role, el.join, el.live
  ];
  for (const s of screens) s.hidden = s !== screen;
}

function status(text, kind) {
  el.statusline.textContent = text;
  el.dot.className = 'dot' + (kind ? ' ' + kind : '');
}

const press = (btn, on) => btn.setAttribute('aria-pressed', on ? 'true' : 'false');

// Native shell bridge. No-op in a plain browser.
const iosNative = window.webkit?.messageHandlers?.tawny;
const androidNative = window.TawnyNative;
function tellNative(event, extra = {}) {
  const msg = { event, ...extra };
  try { iosNative?.postMessage(msg); } catch {}
  try { androidNative?.post(JSON.stringify(msg)); } catch {}
}

// Flight recorder. The signaling failures worth chasing happen on cellular,
// where the phone is off Wi-Fi and unreachable by adb — so the page ships its
// story to the shell, which persists it (see Diag in MainActivity.kt). The room
// id is a hash and is logged deliberately: it is what lets a Monitor's log and
// a Handheld's log be lined up against each other. Never log the channel key or
// the raw admission ticket.
function diag(line) {
  const s = String(line).slice(0, 300);
  try { console.log('[diag]', s); } catch {}
  tellNative('diag', { line: s });
}

function audioCtx() {
  if (!S.ac) S.ac = new (window.AudioContext || window.webkitAudioContext)();
  if (S.ac.state === 'suspended') S.ac.resume();
  return S.ac;
}

// Android WebView sometimes rejects the first play() on a just-arrived WebRTC
// stream (before RTP is flowing). Retry a few times before giving up.
function playSoon(elm, tries = 4) {
  elm.play?.().catch(() => {
    if (tries > 0) setTimeout(() => playSoon(elm, tries - 1), 250);
  });
}

// ---------------------------------------------------------- channel list

function renderChannels() {
  const list = getChannels();
  el.chList.replaceChildren();
  el.chEmpty.hidden = list.length > 0;

  for (const ch of list) {
    const li = document.createElement('li');
    li.className = 'ch-row';

    const open = document.createElement('button');
    open.type = 'button';
    open.className = 'ch-open';
    const nm = document.createElement('strong');
    nm.textContent = ch.name;
    const fp = document.createElement('span');
    fp.className = 'ch-fp';
    fp.textContent = `key ${ch.key.slice(0, 6)}…`;
    open.append(nm, fp);
    open.addEventListener('click', () => openRole(ch));

    const edit = document.createElement('button');
    edit.type = 'button';
    edit.className = 'ch-icon';
    edit.title = `Rename ${ch.name}`;
    edit.setAttribute('aria-label', `Rename ${ch.name}`);
    edit.textContent = '✎';
    edit.addEventListener('click', () => openEditor(ch));

    const del = document.createElement('button');
    del.type = 'button';
    del.className = 'ch-icon danger';
    del.title = `Delete ${ch.name}`;
    del.setAttribute('aria-label', `Delete ${ch.name}`);
    del.textContent = '␡';
    del.addEventListener('click', () => {
      if (!confirm(`Delete "${ch.name}"? Devices paired to it will stop connecting.`)) return;
      setChannels(getChannels().filter((c) => c.id !== ch.id));
      renderChannels();
      toast('Channel deleted');
    });

    li.append(open, edit, del);
    el.chList.append(li);
  }
}

function openEditor(ch) {
  S.editing = ch || null;
  el.editorTitle.textContent = ch ? 'Rename channel' : 'New channel';
  el.editorName.value = ch ? ch.name : '';
  el.editorHint.textContent = ch
    ? 'The key stays the same, so paired devices keep working.'
    : 'A fresh key is generated for this channel. Only devices you pair can join it.';
  el.editor.hidden = false;
  el.editorName.focus();
}

function saveEditor() {
  const name = el.editorName.value.trim();
  if (!name) return toast('Give the channel a name.');
  const list = getChannels();
  if (S.editing) {
    const found = list.find((c) => c.id === S.editing.id);
    if (found) found.name = name;
  } else {
    if (list.length >= 12) return toast('That is plenty of channels.');
    list.push({ id: crypto.randomUUID(), name, key: newKey() });
  }
  setChannels(list);
  el.editor.hidden = true;
  S.editing = null;
  renderChannels();
}

el.chAdd.addEventListener('click', () => openEditor(null));
$('#editor-save').addEventListener('click', saveEditor);
$('#editor-cancel').addEventListener('click', () => { el.editor.hidden = true; S.editing = null; });
el.editorName.addEventListener('keydown', (e) => { if (e.key === 'Enter') saveEditor(); });

function openRole(ch) {
  S.channel = ch;
  el.roleName.textContent = ch.name;
  note(el.roleNote, '');
  show(el.role);
}

$('#role-back').addEventListener('click', () => { S.channel = null; show(el.channels); });
$('#pick-station').addEventListener('click', () => start('station'));
$('#pick-viewer').addEventListener('click', () => start('viewer'));

// ------------------------------------------------------- first-run setup

function markOnboarded() {
  try { localStorage.setItem(ONBOARDED, '1'); } catch {}
}

// The watcher owns the channel. Reuse the first one if setup is re-run.
function watcherChannel() {
  const list = getChannels();
  if (list.length) return list[0];
  const ch = { id: crypto.randomUUID(), name: 'Pet camera', key: newKey() };
  setChannels([ch]);
  renderChannels();
  return ch;
}

function openWatchPair() {
  S.channel = watcherChannel();
  const url = pairLink();
  try {
    drawQR(url, el.wpQr);
    note(el.wpWarn, '');
  } catch {
    note(el.wpWarn, 'This address is too long to fit in a pairing code. Use a shorter hostname.');
  }
  const insecure = url.startsWith('http://') &&
    !/^https?:\/\/(localhost|127\.0\.0\.1)/.test(url);
  if (insecure) {
    note(el.wpWarn, 'This is a plain http address. The Viewer will load but the ' +
      'browser will refuse it the microphone. Serve Tawny over https.');
  }
  el.wpStatus.textContent = 'Waiting for the Viewer';
  show(el.watchPair);
}

$('#welcome-go').addEventListener('click', () => { note(el.setupNote, ''); show(el.setupRole); });
$('#welcome-skip').addEventListener('click', () => { markOnboarded(); show(el.channels); });
$('#setup-back').addEventListener('click', () => show(el.welcome));

$('#setup-watcher').addEventListener('click', () => {
  markOnboarded();
  openWatchPair();
});

$('#setup-handheld').addEventListener('click', () => {
  markOnboarded();
  if ('BarcodeDetector' in window) {
    openScanner();
    return;
  }
  const link = prompt('Paste the pairing link shown on the Watcher:');
  if (link == null) return;
  if (!adopt(link.trim())) {
    note(el.setupNote, 'That was not a Tawny pairing link. Try scanning it with your phone camera instead.');
  }
});

$('#wp-start').addEventListener('click', () => start('station'));
$('#wp-later').addEventListener('click', () => show(el.channels));

// ------------------------------------------------- connection profiles

function getProfiles() {
  const saved = readJSON(PROFILES, null);
  if (saved) return saved;
  return { choice: 'origin', custom: '' };
}

function baseCandidates() {
  const here = location.origin + location.pathname.replace(/[^/]*$/, '');
  return [
    { id: 'origin', label: `This address — ${location.host}`, url: here },
    { id: 'custom', label: 'Another address (domain or tailnet name)…', url: '' }
  ];
}

function renderProfiles() {
  const p = getProfiles();
  el.pairProfile.replaceChildren();
  for (const c of baseCandidates()) {
    const opt = document.createElement('option');
    opt.value = c.id;
    opt.textContent = c.label;
    if (c.id === p.choice) opt.selected = true;
    el.pairProfile.append(opt);
  }
  el.pairCustom.value = p.custom;
  el.pairCustomWrap.hidden = p.choice !== 'custom';
}

function chosenBase() {
  const p = getProfiles();
  if (p.choice === 'custom' && p.custom.trim()) {
    let u = p.custom.trim();
    if (!/^https?:\/\//i.test(u)) u = 'https://' + u;
    return u.replace(/\/+$/, '') + '/';
  }
  return location.origin + location.pathname.replace(/[^/]*$/, '');
}

// ------------------------------------------------------------- pairing

function pairLink() {
  const base = chosenBase();
  const frag = new URLSearchParams({ k: S.channel.key, n: S.channel.name, r: 'viewer' });
  if (S.token) frag.set('t', S.token);
  return `${base}#${frag}`;
}

function drawQR(text, cv = el.qr) {
  const qr = qrcode(0, 'M');
  qr.addData(text);
  qr.make();
  const n = qr.getModuleCount();
  const quiet = 4;
  const total = n + quiet * 2;
  const scale = Math.max(2, Math.floor(300 / total));
  const size = total * scale;

  cv.width = size;
  cv.height = size;
  const ctx = cv.getContext('2d');
  ctx.fillStyle = '#ece3d4';
  ctx.fillRect(0, 0, size, size);
  ctx.fillStyle = '#14110f';
  for (let r = 0; r < n; r++) {
    for (let c = 0; c < n; c++) {
      if (qr.isDark(r, c)) {
        ctx.fillRect((c + quiet) * scale, (r + quiet) * scale, scale, scale);
      }
    }
  }
}

function refreshPair() {
  const url = pairLink();
  el.pairName.textContent = S.channel.name;
  el.pairUrl.textContent = url;
  try {
    drawQR(url);
    note(el.pairWarn, '');
  } catch {
    note(el.pairWarn, 'That address is too long to fit in a pairing code. Use a shorter hostname.');
  }
  const insecure = url.startsWith('http://') && !/^https?:\/\/(localhost|127\.0\.0\.1)/.test(url);
  if (insecure) {
    note(el.pairWarn, 'This is a plain http address. The handset will load but browsers ' +
      'will refuse it the microphone. Use an https address instead.');
  }
}

function openPair() {
  // The native shell runs its own pairing screen.
  if (S.nativeShell) return;
  renderProfiles();
  refreshPair();
  el.pair.hidden = false;
}

el.pairProfile.addEventListener('change', () => {
  const p = getProfiles();
  p.choice = el.pairProfile.value;
  writeJSON(PROFILES, p);
  el.pairCustomWrap.hidden = p.choice !== 'custom';
  refreshPair();
});

el.pairCustom.addEventListener('input', () => {
  const p = getProfiles();
  p.custom = el.pairCustom.value;
  writeJSON(PROFILES, p);
  refreshPair();
});

$('#pair-close').addEventListener('click', () => { el.pair.hidden = true; });
$('#pair-copy').addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(pairLink());
    toast('Link copied');
  } catch {
    toast('Copy failed — select the link text instead.');
  }
});

// -------------------------------------------------------------- scanner

async function openScanner() {
  if (!('BarcodeDetector' in window)) {
    return toast('This browser cannot scan. Use your phone camera app instead.');
  }
  el.scanner.hidden = false;
  let stream;
  try {
    stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'environment' } });
  } catch {
    el.scanner.hidden = true;
    return toast('Could not open the camera.');
  }
  el.scanVideo.srcObject = stream;
  const detector = new window.BarcodeDetector({ formats: ['qr_code'] });
  let live = true;

  S.scanStop = () => {
    live = false;
    stream.getTracks().forEach((t) => t.stop());
    el.scanVideo.srcObject = null;
    el.scanner.hidden = true;
    S.scanStop = null;
  };

  const loop = async () => {
    if (!live) return;
    try {
      const found = await detector.detect(el.scanVideo);
      if (found.length) {
        const ok = adopt(found[0].rawValue);
        if (ok) { S.scanStop(); return; }
        el.scanHint.textContent = 'That code is not a Tawny pairing code.';
      }
    } catch {}
    setTimeout(loop, 250);
  };
  loop();
}

el.chScan.addEventListener('click', openScanner);
$('#scan-close').addEventListener('click', () => S.scanStop?.());

// Import a pairing link. Returns true when it was a valid one.
function adopt(raw) {
  let hash;
  try { hash = new URL(raw, location.href).hash.slice(1); }
  catch { return false; }
  const p = new URLSearchParams(hash);
  const key = p.get('k');
  if (!key || !/^[A-Za-z0-9_-]{16,64}$/.test(key)) return false;

  const name = (p.get('n') || 'Pet camera').slice(0, 40);
  if (p.get('t')) S.token = p.get('t');

  const list = getChannels();
  let ch = list.find((c) => c.key === key);
  if (!ch) {
    ch = { id: crypto.randomUUID(), name, key };
    list.push(ch);
    setChannels(list);
  }
  renderChannels();
  S.channel = ch;
  S.pending = 'viewer';
  el.joinName.textContent = ch.name;
  show(el.join);
  return true;
}

$('#join-go').addEventListener('click', () => start(S.pending || 'viewer'));
$('#join-cancel').addEventListener('click', () => { S.pending = null; show(el.channels); });

// --------------------------------------------------------------- meters

for (const node of [el.meter, el.dimMeter]) node.innerHTML = '<i></i>'.repeat(14);

function startMeter(stream) {
  stopMeter();
  if (!stream || !stream.getAudioTracks().length) return;
  let ac;
  try { ac = audioCtx(); } catch { return; }
  const src = ac.createMediaStreamSource(stream);
  const an = ac.createAnalyser();
  an.fftSize = 512;
  an.smoothingTimeConstant = 0.7;
  src.connect(an);

  const buf = new Uint8Array(an.fftSize);
  const bars = [...el.meter.children];
  const dimBars = [...el.dimMeter.children];
  let raf;

  const tick = () => {
    an.getByteTimeDomainData(buf);
    let sum = 0;
    for (let i = 0; i < buf.length; i++) {
      const v = (buf[i] - 128) / 128;
      sum += v * v;
    }
    const rms = Math.sqrt(sum / buf.length);
    const level = Math.min(1, Math.log10(1 + rms * 60) / Math.log10(61));
    const lit = Math.round(level * bars.length);
    for (let i = 0; i < bars.length; i++) {
      const cls = i < lit ? (i >= bars.length - 2 ? 'peak' : 'lit') : '';
      if (bars[i].className !== cls) bars[i].className = cls;
      if (dimBars[i].className !== cls) dimBars[i].className = cls;
    }
    raf = requestAnimationFrame(tick);
  };
  tick();

  S.meterStop = () => {
    cancelAnimationFrame(raf);
    try { src.disconnect(); } catch {}
    for (const b of [...bars, ...dimBars]) b.className = '';
  };
}

function stopMeter() { S.meterStop?.(); S.meterStop = null; }

// --------------------------------------------------------------- chimes

function playChime(kind) {
  const ac = audioCtx();
  const t0 = ac.currentTime;
  const out = ac.createGain();
  out.gain.value = 0.35;
  out.connect(ac.destination);

  const ping = (freq, start, dur, type = 'sine', peak = 1) => {
    const o = ac.createOscillator();
    const g = ac.createGain();
    o.type = type;
    o.frequency.setValueAtTime(freq, t0 + start);
    g.gain.setValueAtTime(0.0001, t0 + start);
    g.gain.exponentialRampToValueAtTime(peak, t0 + start + 0.012);
    g.gain.exponentialRampToValueAtTime(0.0001, t0 + start + dur);
    o.connect(g).connect(out);
    o.start(t0 + start);
    o.stop(t0 + start + dur + 0.05);
  };

  if (kind === 'bell') {
    ping(880, 0, 1.1);
    ping(1318.5, 0.02, 1.3, 'sine', 0.6);
    ping(1760, 0.28, 0.9, 'sine', 0.45);
  } else if (kind === 'whistle') {
    const o = ac.createOscillator();
    const g = ac.createGain();
    o.type = 'sine';
    o.frequency.setValueAtTime(900, t0);
    o.frequency.exponentialRampToValueAtTime(2400, t0 + 0.35);
    o.frequency.exponentialRampToValueAtTime(1100, t0 + 0.75);
    g.gain.setValueAtTime(0.0001, t0);
    g.gain.exponentialRampToValueAtTime(0.9, t0 + 0.05);
    g.gain.setValueAtTime(0.9, t0 + 0.6);
    g.gain.exponentialRampToValueAtTime(0.0001, t0 + 0.85);
    o.connect(g).connect(out);
    o.start(t0);
    o.stop(t0 + 0.95);
  } else {
    for (let i = 0; i < 3; i++) ping(2100, i * 0.16, 0.1, 'square', 0.5);
  }
}

// ------------------------------------------------------------ signaling
//
// A Handheld holds one signaling transport; the Watcher holds up to two — its
// embedded LAN relay AND, when a rendezvous is configured, an outbound socket to
// it. Every device only ever *dials out* (wss:443 to the rendezvous, ws to the
// Watcher's LAN IP) — nothing listens for an inbound connection. Media stays
// peer-to-peer; the rendezvous never sees a frame or the channel key.

function rendezvousBase() {
  const rv = S.cfg.rendezvous;
  if (!rv) return null;
  return rv.replace(/^http/i, 'ws').replace(/\/+$/, '');
}

async function sha256hex(s) {
  const d = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, '0')).join('');
}

// ---- short authentication string --------------------------------------------
// Only meaningful on the internet-relay path: a compromised rendezvous could try
// to sit in the middle by swapping DTLS certificate fingerprints. Both ends mix
// in the channel key (which no relay ever holds), so a swap yields a different
// code on each screen. On the LAN path the Watcher *is* the relay, so there's
// nothing to check and it is skipped.

const SAS_ALPHABET = '0123456789ABCDEFGHJKMNPQRSTVWXYZ';   // Crockford base32

// Pull the *negotiated* certificate fingerprints from getStats() — not the SDP
// text, which a relay can pad with a decoy session-level a=fingerprint line.
async function negotiatedFingerprints(pc) {
  const stats = await pc.getStats();
  let transport;
  stats.forEach((r) => { if (r.type === 'transport') transport = r; });
  if (!transport) return null;
  const cert = (id) => {
    let out;
    stats.forEach((r) => { if (r.type === 'certificate' && r.id === id) out = r; });
    return out;
  };
  const l = cert(transport.localCertificateId);
  const rm = cert(transport.remoteCertificateId);
  if (!l?.fingerprint || !rm?.fingerprint) return null;
  const norm = (c) => `${(c.fingerprintAlgorithm || 'sha-256').toLowerCase()} ${c.fingerprint.toUpperCase()}`;
  return [norm(l), norm(rm)].sort();
}

async function computeSas(peer) {
  if (!peer.pc || !S.channel) return null;
  const fps = await negotiatedFingerprints(peer.pc);
  if (!fps) return null;                       // fail closed: caller shows a warning
  const key = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(S.channel.key),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
  );
  const mac = new Uint8Array(await crypto.subtle.sign(
    'HMAC', key, new TextEncoder().encode(`tawny-sas-v1|${fps[0]}|${fps[1]}`)
  ));
  let s = '';
  for (let i = 0; i < 6; i++) s += SAS_ALPHABET[mac[i] & 31];
  return `${s.slice(0, 3)}-${s.slice(3)}`;
}

const SAS_TRIES = 25;          // ~10s at 400ms — DTLS is usually up inside 2s

async function showSas(peer, attempt = 0) {
  if (peer.transport?.tag !== 'cloud') return;   // LAN needs no SAS
  let code;
  try { code = await computeSas(peer); }
  catch { code = null; }

  // showSas() fires as soon as the answer is sent, but the DTLS transport is
  // not established yet — getStats() has no negotiated certificates for another
  // second or two, so the first attempt almost always comes back empty. Waiting
  // it out is the difference between a code and a false alarm: the Monitor used
  // to silently show no chip at all, and the Handheld flashed "connection may
  // be tampered with" over a perfectly good call.
  const pcState = peer.pc?.connectionState;
  if (!code && attempt < SAS_TRIES && pcState !== 'failed' && pcState !== 'closed') {
    clearTimeout(peer.sasTimer);
    peer.sasTimer = setTimeout(() => showSas(peer, attempt + 1), 400);
    return;
  }
  if (!code) diag(`sas unavailable after ${attempt} tries (pc=${pcState})`);

  if (S.role === 'station') {
    if (!code) return;   // fail closed — never show an empty/broken chip
    el.saschip.textContent = 'Verify: ' + code;
    el.saschip.hidden = false;
    return;
  }
  // Handheld: show the code every internet session and let the user glance at
  // it. We only stop *blocking* on it once the user has confirmed a match for
  // this monitor — the chip stays.
  el.saschip.textContent = code || '—';
  el.saschip.hidden = false;
  let confirmed = false;
  try { confirmed = localStorage.getItem(`tawny.sas.${S.channel.id}`) === '1'; } catch {}
  el.sascode.textContent = code || 'unavailable — connection may be tampered with';
  el.sas.classList.toggle('warn', !code);
  el.sas.hidden = confirmed && !!code;
}

function wsURLFor(base) {
  // The admission ticket rides in the first WS frame ({type:'hello'}), never in
  // the URL — query strings land in TLS-terminator access logs.
  const q = new URLSearchParams({ room: S.roomId, role: S.role });
  if (base) return `${base.replace(/\/+$/, '')}/ws?${q}`;
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${proto}//${location.host}${location.pathname.replace(/[^/]*$/, '')}ws?${q}`;
}

function openSignal(base, tag) {
  const entry = { tag, ws: null, retry: 0, dead: false };
  S.signals.push(entry);
  const dial = () => {
    if (entry.dead || S.closing) return;
    diag(`dial ${tag} ${String(base || 'same-origin').replace(/^wss?:\/\//, '')} room=${S.roomId}`);
    const ws = new WebSocket(wsURLFor(base));
    entry.ws = ws;
    ws.onopen = async () => {
      entry.retry = 0;
      diag(`${tag} open; hello role=${S.role} ticket=${S.token ? 'yes' : 'MISSING'}`);
      // First frame: prove admission. The Watcher also hands its ticket hash so
      // the rendezvous knows which ticket to accept for this room. sha256 only.
      const hello = { type: 'hello' };
      if (S.token) {
        hello.t = S.token;
        if (S.role === 'station') { try { hello.hashT = await sha256hex(S.token); } catch {} }
      }
      try { ws.send(JSON.stringify(hello)); } catch {}
      updateStatus();
    };
    ws.onmessage = (e) => {
      let m; try { m = JSON.parse(e.data); } catch { return; }
      // Membership traffic only — offer/answer/ice would drown the log.
      if (m.type === 'welcome') diag(`${tag} welcome id=${m.id} peers=${(m.peers || []).length}`);
      else if (m.type === 'peer-joined') diag(`${tag} peer-joined ${m.role || '?'} ${m.id}`);
      else if (m.type === 'peer-left') diag(`${tag} peer-left ${m.id}`);
      handle(m, entry).catch(console.error);
    };
    ws.onclose = (ev) => {
      diag(`${tag} close ${ev.code}${ev.reason ? ' "' + ev.reason + '"' : ''} retry=${entry.retry}`);
      if (entry.dead || S.closing) return;

      // Fatal close codes: for a Handheld (one transport) the session is over;
      // for the Watcher, a bad cloud socket must NOT tear down a healthy LAN one.
      if (ev.code === 4003 || ev.code === 4004 || ev.code === 4008) {
        if (S.role === 'station') {
          // A dead cloud leg used to be completely silent here: the Monitor sat
          // on its healthy LAN socket showing "Waiting", while every Handheld
          // off the Wi-Fi was being turned away at the relay.
          diag(`${tag} FATAL ${ev.code} — monitor is NOT reachable over the internet`);
          closeSignal(entry);
          if (ev.code === 4004) toast('Another device claimed this monitor on the internet relay.');
          if (ev.code === 4008 && tag === 'cloud') {
            toast('The internet relay refused this monitor. Viewers off your Wi-Fi cannot connect.');
          }
          return;
        }
        return bail(
          ev.code === 4003 ? 'That channel already has the maximum number of devices.'
          : ev.code === 4004 ? 'A monitor is already running on this channel.'
          : 'That pairing code has expired. Scan a fresh one from the Monitor.'
        );
      }

      for (const p of [...S.peers.values()]) if (p.transport === entry) removePeer(p.id);
      updateStatus();

      const wait = Math.min(1000 * 2 ** entry.retry++, 15000);

      // Handheld: fall to the rendezvous when the LAN attempt keeps failing —
      // whether it never committed, or the session has moved off Wi-Fi.
      if (S.role === 'viewer' && entry.tag === 'lan' && rendezvousBase() &&
          (!S.committedTag || entry.retry >= 2)) {
        clearTimeout(S.raceTimer);
        closeSignal(entry);
        S.committedTag = 'cloud';
        openSignal(rendezvousBase(), 'cloud');
        return;
      }
      if (S.role === 'viewer' && !S.committedTag && entry.tag === 'lan' && !rendezvousBase()) {
        return bail('This code needs the Monitor on the same Wi-Fi as this phone.');
      }

      if (S.nativeShell && S.role === 'viewer' && entry.retry === 6) tellNative('unreachable');
      setTimeout(dial, wait);
    };
    ws.onerror = () => ws.close();
  };
  entry.dial = dial;
  dial();
  return entry;
}

function closeSignal(entry) {
  entry.dead = true;
  try { entry.ws?.close(); } catch {}
  const i = S.signals.indexOf(entry);
  if (i >= 0) S.signals.splice(i, 1);
}

function closeAllSignals() {
  clearTimeout(S.raceTimer);
  for (const s of [...S.signals]) closeSignal(s);
}

function connectAll() {
  S.signals = [];
  S.committedTag = null;
  clearTimeout(S.raceTimer);
  const rv = rendezvousBase();
  if (S.role === 'station') {
    if (S.signalUrl) openSignal(S.signalUrl, 'lan');
    if (rv) openSignal(rv, 'cloud');
    if (!S.signals.length) return bail('No way to reach the Viewer yet — connect this phone to Wi-Fi.');
    status('Waiting', null);
    return;
  }
  raceTransports();
}

// Handheld: try the LAN hint first; if no Watcher shows up there within 2.5s (or
// the LAN socket fails outright — see openSignal.onclose), fall to the
// rendezvous. Once a transport is committed it just backoff-redials itself; a
// session that moves off Wi-Fi degrades to cloud and stays there until it ends.
function raceTransports() {
  clearTimeout(S.raceTimer);
  S.committedTag = null;
  const rv = rendezvousBase();
  if (S.signalUrl) {
    const lan = openSignal(S.signalUrl, 'lan');
    if (!rv) return;                     // LAN-only build: nothing to fall back to
    S.raceTimer = setTimeout(() => {
      if (S.committedTag) return;
      closeSignal(lan);
      S.committedTag = 'cloud';
      openSignal(rv, 'cloud');
    }, 2500);
  } else if (rv) {
    S.committedTag = 'cloud';
    openSignal(rv, 'cloud');
  } else {
    bail('This code needs the Monitor on the same Wi-Fi as this phone.');
  }
}

// Lock onto whichever transport delivered a Watcher; drop the rest.
function commitTransport(entry) {
  if (S.role !== 'viewer' || S.committedTag === entry.tag) return;
  clearTimeout(S.raceTimer);
  S.committedTag = entry.tag;
  for (const other of [...S.signals]) if (other !== entry) closeSignal(other);
}

function sig(obj, peer) {
  const entry = peer?.transport
    || S.signals.find((s) => s.ws?.readyState === WebSocket.OPEN);
  if (entry?.ws?.readyState === WebSocket.OPEN) entry.ws.send(JSON.stringify(obj));
}

function broadcast(obj) {
  for (const p of S.peers.values()) sig({ ...obj, to: p.id }, p);
}

// Short-lived TURN credentials, issued by the rendezvous per room. No secret
// ships in the app; nothing is fetched at all on a LAN-only build.
async function fetchIce() {
  const rv = S.cfg.rendezvous;
  if (!rv) { S.ice = []; return; }
  const httpBase = rv.replace(/^ws/i, 'http').replace(/\/+$/, '');
  try {
    const q = new URLSearchParams({ room: S.roomId });
    // `t`, not `token` — the relay reads `t` (worker.js /turn, server.js), so
    // the old name meant the ticket check always compared against "" and every
    // request came back 403 "not paired". TURN was therefore never fetched and
    // calls silently had no relay candidate to fall back on.
    if (S.token) q.set('t', S.token);
    const ctrl = new AbortController();
    const t = setTimeout(() => ctrl.abort(), 4000);
    const r = await fetch(`${httpBase}/turn?${q}`, { signal: ctrl.signal });
    clearTimeout(t);
    if (!r.ok) return;
    const j = await r.json();
    const turn = Array.isArray(j.iceServers) ? j.iceServers : [];
    S.ice = [...(S.cfg.stun || []).map((urls) => ({ urls })), ...turn];
    const ttl = Number(j.ttl) || 0;
    if (ttl > 30) {
      clearTimeout(S.iceTimer);
      S.iceTimer = setTimeout(fetchIce, ttl * 800);   // refresh at ~80% of TTL
    }
  } catch {}
}

// --------------------------------------------------------- peer bookkeeping

const stationPeer = () => [...S.peers.values()].find((p) => p.role === 'station');
const viewerPeers = () => [...S.peers.values()].filter((p) => p.role === 'viewer');
const viewerCount = () => viewerPeers().length;

function ensurePeer(id, role, transport) {
  let p = S.peers.get(id);
  if (!p) {
    p = { id, role, transport: transport || null, pc: null, stream: null,
          iceKick: null, talking: false, audioEl: null, pendingIce: [] };
    S.peers.set(id, p);
  } else if (transport) {
    p.transport = transport;   // Watcher may re-learn a peer on the other relay
  }
  return p;
}

// ICE candidates that arrive before setRemoteDescription would throw; queue them.
async function flushIce(peer) {
  if (!peer.pc || !peer.pendingIce.length) return;
  const q = peer.pendingIce.splice(0);
  for (const c of q) { try { await peer.pc.addIceCandidate(c); } catch {} }
}

function removePeer(id) {
  const p = S.peers.get(id);
  if (!p) return;
  clearTimeout(p.iceKick);
  clearTimeout(p.sasTimer);      // stop the safety-code retry loop
  if (p.qTimer) { clearInterval(p.qTimer); p.qTimer = null; }
  if (p.pc) { p.pc.onnegotiationneeded = null; try { p.pc.close(); } catch {} }
  if (p.audioEl) { p.audioEl.srcObject = null; p.audioEl.remove(); }
  S.peers.delete(id);
  if (S.featured === id) unfeature(id);

  if (S.role === 'viewer' && p.role === 'station') {
    // Lost the Watcher — clear the picture and wait for it to come back.
    stopMeter();
    el.talkflag.hidden = true;
    S.remoteStream = null;
    el.remote.srcObject = null;
    el.remoteAudio.srcObject = null;
    el.remote.hidden = true;
    el.loader.hidden = false;
  }
  retuneAll();
  updatePeerChip();
  updateStatus();
}

function teardownAll() {
  for (const id of [...S.peers.keys()]) removePeer(id);
  clearTimeout(S.iceTimer);      // the TURN refresh outlived the session and
  S.iceTimer = null;             // re-fetched against a stale room id
  stopMeter();
  el.talkflag.hidden = true;
  el.sas.hidden = true;
  el.saschip.hidden = true;
  // clear digital zoom so transforms don't carry over into the next session
  const remoteEl = document.getElementById('remote');
  if (remoteEl) remoteEl.style.transform = '';
  const localEl = document.getElementById('local');
  if (localEl) localEl.style.transform = '';
  S.zoomLevel = 1.0;
  S.cameras = [];
  S.cameraIndex = 0;
  S.zoomHardware = false;
  S.stationZoomSupported = false;
}

function updateStatus() {
  if (S.role === 'viewer') {
    const sp = stationPeer();
    const st = sp?.pc?.connectionState;
    if (st === 'connected') status('Live', 'live');
    else if (st === 'connecting' || st === 'new') status('Connecting', 'on');
    else if (!sp) status('Monitor offline', null);
    else status('Reconnecting', null);
    return;
  }
  const vs = viewerPeers();
  if (!vs.length) { status('Waiting', null); return; }
  const live = vs.filter((p) => p.pc?.connectionState === 'connected').length;
  status(live
    ? `On air · ${live} watching`
    : 'Handset connecting', live ? 'live' : 'on');
}

function updatePeerChip() {
  if (!el.peercount) return;
  if (S.role !== 'station') { el.peercount.hidden = true; return; }
  const n = viewerCount();
  el.peercount.hidden = n === 0;
  el.peercount.textContent = n === 1 ? '1 phone' : `${n} phones`;
  el.peercount.classList.toggle('warn', n >= 3);
  // Tell the native shell whether to keep the pairing-QR overlay up.
  const state = n > 0 ? 'watching' : 'waiting';
  if (state !== S._peerState) { S._peerState = state; tellNative(state, { n }); }
}

// ------------------------------------------------------------- dispatch

async function handle(m, entry) {
  switch (m.type) {
    case 'welcome': {
      S.myId = m.id;
      const seesStation = m.peers.some((x) => x.role === 'station');
      if (S.role === 'viewer' && seesStation) commitTransport(entry);
      // Reconcile: anyone on this transport who isn't in the list has left.
      const here = new Set(m.peers.map((x) => x.id));
      for (const [id, p] of [...S.peers]) {
        if (p.transport === entry && !here.has(id)) removePeer(id);
      }
      for (const info of m.peers) {
        if (info.role === S.role) continue;
        const p = ensurePeer(info.id, info.role, entry);
        if (S.role === 'viewer') await callPeer(p);
      }
      updateStatus();
      break;
    }
    case 'peer-joined': {
      if (m.role === S.role) return;
      if (S.role === 'station' && viewerCount() >= MAX_VIEWERS) return;
      if (S.role === 'viewer' && m.role === 'station') {
        commitTransport(entry);
        // A Watcher that dropped and rejoined comes back with a new id — retire
        // the stale record so there is only ever one station peer.
        for (const p of [...S.peers.values()]) {
          if (p.role === 'station' && p.id !== m.id) removePeer(p.id);
        }
      }
      const p = ensurePeer(m.id, m.role, entry);
      if (S.role === 'viewer') {
        await callPeer(p);
      } else {
        el.pair.hidden = true;
        updatePeerChip();
        updateStatus();
      }
      break;
    }
    case 'peer-left':
      removePeer(m.id);
      break;
    case 'offer': {
      const p = ensurePeer(m.from, S.role === 'station' ? 'viewer' : 'station', entry);
      await answerPeer(p, m.sdp);
      break;
    }
    case 'answer': {
      const p = S.peers.get(m.from);
      if (p?.pc) {
        try { await p.pc.setRemoteDescription(m.sdp); await flushIce(p); showSas(p); }
        catch (e) { console.error(e); }
      }
      break;
    }
    case 'ice': {
      const p = S.peers.get(m.from);
      if (!p || !m.candidate) break;
      if (p.pc && p.pc.remoteDescription) {
        try { await p.pc.addIceCandidate(m.candidate); } catch {}
      } else {
        (p.pendingIce ||= []).push(m.candidate);
      }
      break;
    }
    case 'chime': {
      if (S.role !== 'station') return;
      const p = S.peers.get(m.from);
      const now = Date.now();
      if (now - (handle._chimeAt || 0) > 300) { playChime(m.sound); handle._chimeAt = now; }
      sig({ type: 'chime-ack', to: m.from, sound: m.sound }, p);
      break;
    }
    case 'chime-ack': {
      // Peer-controlled and optional: a missing `sound` used to throw here and
      // the confirmation toast just never appeared.
      const name = typeof m.sound === 'string' && m.sound
        ? `${m.sound[0].toUpperCase()}${m.sound.slice(1)}` : 'Chime';
      toast(`${name} played on the Monitor`);
      break;
    }
    case 'talking': {
      const p = S.peers.get(m.from);
      if (p) p.talking = !!m.on;
      el.talkflag.hidden = S.role === 'station'
        ? !viewerPeers().some((x) => x.talking)
        : !m.on;
      break;
    }
    case 'cameras': {
      // Viewer receives the monitor's camera list after connecting.
      S.cameras = (m.list || []).map((c, i) => ({ ...c, index: i }));
      S.stationZoomSupported = !!m.zoomSupported;
      S.cameraIndex = 0;
      updateLensUI();
      break;
    }
    case 'meta': {
      // Viewer receives updated pet name from the Monitor (e.g. after rename).
      if (S.role !== 'viewer' || !m.petName) break;
      if (S.channel) {
        S.channel.name = m.petName;
        const list = getChannels();
        const ch = list.find((c) => c.key === S.channel.key);
        if (ch) { ch.name = m.petName; setChannels(list); }
        if (el.channel) el.channel.textContent = m.petName;
      }
      if (S.nativeShell) tellNative('petname', { name: m.petName });
      break;
    }
    case 'camera-control': {
      // Station receives zoom/lens commands from a viewer.
      if (S.role !== 'station') break;
      if (m.zoom != null) applyZoom(Number(m.zoom));
      if (m.lens != null) switchLens(Number(m.lens));
      break;
    }
    case 'bye':
      removePeer(m.from);
      if (!S.peers.size) status(S.role === 'viewer' ? 'Call ended' : 'Waiting', null);
      break;
  }
}

// --------------------------------------------------------------- webrtc

function iceServers() {
  if (S.ice && S.ice.length) return S.ice;
  return (S.cfg.stun || []).map((urls) => ({ urls }));
}

function newPC(peer) {
  const pc = new RTCPeerConnection({
    iceServers: iceServers(),
    bundlePolicy: 'max-bundle',
    ...(S.cfg.turnMode === 'always' || S.relayOnly ? { iceTransportPolicy: 'relay' } : {})
  });
  peer.pc = pc;

  pc.onicecandidate = (e) => {
    if (e.candidate) sig({ type: 'ice', to: peer.id, candidate: e.candidate.toJSON() }, peer);
  };

  pc.ontrack = (e) => {
    // A track added via replaceTrack() carries no stream — wrap it.
    const stream = e.streams[0] || peer.stream || new MediaStream();
    if (!stream.getTracks().includes(e.track)) stream.addTrack(e.track);
    peer.stream = stream;

    if (S.role === 'viewer') {
      // The single remote picture + audio come from the Watcher.
      S.remoteStream = stream;
      const hasVideo = stream.getVideoTracks().length > 0;
      el.remoteAudio.srcObject = stream;
      el.remoteAudio.muted = false;
      el.remoteAudio.volume = 1;
      playSoon(el.remoteAudio);
      el.remote.srcObject = stream;
      el.remote.hidden = !hasVideo;
      if (hasVideo) playSoon(el.remote);
      el.loader.hidden = true;
      startMeter(stream);           // meter the Watcher's room
    } else {
      // Station. Talk-back audio always rides a hidden per-peer <audio>.
      if (!peer.audioEl) {
        peer.audioEl = document.createElement('audio');
        peer.audioEl.autoplay = true;
        (el.peerAudio || document.body).appendChild(peer.audioEl);
      }
      peer.audioEl.srcObject = stream;
      playSoon(peer.audioEl);

      // If this Handheld shares its camera, show it big and drop our own
      // camera to a corner; toggling it off (replaceTrack(null)) mutes the
      // track, toggling on unmutes it.
      const vt = stream.getVideoTracks()[0];
      if (vt) {
        const show = () => featureVideo(peer, stream);
        const hide = () => unfeature(peer.id);
        vt.addEventListener('unmute', show);
        vt.addEventListener('mute', hide);
        vt.addEventListener('ended', hide);
        if (vt.muted) hide(); else show();
      } else if (!S.featured) {
        el.local.classList.add('fill');
        el.local.hidden = false;
      }
    }
    updateStatus();
  };

  pc.onconnectionstatechange = () => {
    const st = pc.connectionState;
    if (st === 'connected') clearTimeout(peer.iceKick);
    else if (st === 'failed') reconnectPeer(peer);
    updateStatus();
    updatePeerChip();
  };

  // A short ICE drop usually heals itself; when it doesn't, the viewer forces a
  // restart so it isn't left on a frozen frame. The Watcher just waits.
  pc.oniceconnectionstatechange = () => {
    const s = pc.iceConnectionState;
    if (s === 'connected' || s === 'completed') {
      clearTimeout(peer.iceKick);
    } else if (s === 'disconnected' || s === 'failed') {
      clearTimeout(peer.iceKick);
      peer.iceKick = setTimeout(() => {
        const now = peer.pc?.iceConnectionState;
        if (now === 'disconnected' || now === 'failed') reconnectPeer(peer);
      }, 4000);
    }
  };

  return pc;
}

// Station: show one Handheld's returned camera full-frame.
function featureVideo(peer, stream) {
  if (S.role !== 'station') return;
  S.featured = peer.id;
  el.remote.srcObject = stream;
  el.remote.hidden = false;
  playSoon(el.remote);
  el.local.classList.remove('fill');   // our own camera → corner PiP
  el.local.hidden = false;
}
function unfeature(id) {
  if (S.featured !== id) return;
  S.featured = null;
  el.remote.srcObject = null;
  el.remote.hidden = true;
  el.local.classList.add('fill');
  el.local.hidden = false;
}

// Viewer side: open the connection to the Watcher.
async function callPeer(peer) {
  if (peer.pc) return makeOffer(peer);
  const pc = newPC(peer);
  pc.onnegotiationneeded = () => makeOffer(peer).catch(console.error);
  for (const t of S.local.getTracks()) pc.addTrack(t, S.local);
  if (!S.local.getVideoTracks().length) pc.addTransceiver('video', { direction: 'recvonly' });
}

async function makeOffer(peer, opts) {
  if (!peer.pc || peer.pc.signalingState !== 'stable') return;
  const offer = await peer.pc.createOffer(opts);
  await peer.pc.setLocalDescription(offer);
  sig({ type: 'offer', to: peer.id, sdp: peer.pc.localDescription.toJSON() }, peer);
}

// The viewer drives renegotiation, so it asks for the ICE restart.
function reconnectPeer(peer) {
  if (S.role !== 'viewer' || !peer.pc) return;
  if (typeof peer.pc.restartIce === 'function') {
    try { peer.pc.restartIce(); return; } catch {}
  }
  makeOffer(peer, { iceRestart: true }).catch(() => {});
}

// Station side: answer a Handheld's offer, sharing the one local capture.
async function answerPeer(peer, sdp) {
  const pc = peer.pc || newPC(peer);
  if (!pc.getSenders().length && S.local) {
    for (const t of S.local.getTracks()) pc.addTrack(t, S.local);
  }
  await pc.setRemoteDescription(sdp);
  await flushIce(peer);
  const a = await pc.createAnswer();
  await pc.setLocalDescription(a);
  sig({ type: 'answer', to: peer.id, sdp: pc.localDescription.toJSON() }, peer);
  retuneAll();          // re-apply the uplink ladder to every connected Handheld
  updatePeerChip();
  showSas(peer);
  // Tell the viewer which lenses this monitor has (only when there's more than one).
  if (S.role === 'station' && S.cameras.length > 1) {
    sig({ type: 'cameras', list: S.cameras.map((c) => ({ index: c.index, label: c.label })), zoomSupported: S.zoomHardware, to: peer.id }, peer);
  }
  // Always sync the current pet name so the viewer's session card stays up to date.
  if (S.role === 'station' && S.channel?.name) {
    sig({ type: 'meta', petName: S.channel.name, to: peer.id }, peer);
  }
}

// Per-Handheld uplink budget. One encode per viewer on a mid-range phone, so the
// ceiling drops as more phones connect: 1→1.5M, 2→1.0M, 3→750k, 5→500k.
function bitrateFor(n) {
  return Math.max(350_000, Math.min(1_500_000, Math.round(3_000_000 / (n + 1))));
}

async function tuneVideoSender(peer) {
  const sender = peer.pc?.getSenders().find((s) => s.track?.kind === 'video');
  if (!sender || !sender.track) return;
  // A pet monitor is watched for detail — "is she still on the sofa" — not for
  // smooth motion. 'motion' + 'balanced' told the encoder the opposite: hold the
  // framerate and throw away resolution, which on a mid-range phone meant a soft
  // picture that only crept back up as the bandwidth estimate climbed. Holding
  // resolution and letting the framerate give way looks right much sooner.
  try { sender.track.contentHint = 'detail'; } catch {}
  try {
    const p = sender.getParameters();
    if (!p.encodings || !p.encodings.length) p.encodings = [{}];
    p.encodings[0].maxBitrate = bitrateFor(viewerCount());
    p.encodings[0].maxFramerate = viewerCount() > 3 ? 20 : 24;
    p.encodings[0].scaleResolutionDownBy = 1;
    p.encodings[0].networkPriority = 'high';
    p.encodings[0].priority = 'high';
    p.degradationPreference = 'maintain-resolution';
    await sender.setParameters(p);
  } catch {}
  probeSendQuality(peer);
}

// Samples the outbound video for the first minute of a call. `qualityLimitation
// Reason` is the ground truth for a slow ramp — "cpu" and "bandwidth" call for
// opposite fixes, and this is the only way to tell them apart from here.
function probeSendQuality(peer) {
  if (S.role !== 'station' || peer.qTimer) return;
  let n = 0, lastBytes = 0, lastAt = 0;
  peer.qTimer = setInterval(async () => {
    if (!peer.pc || peer.pc.connectionState === 'closed' || ++n > 12) {
      clearInterval(peer.qTimer); peer.qTimer = null; return;
    }
    try {
      const stats = await peer.pc.getStats();
      stats.forEach((r) => {
        if (r.type !== 'outbound-rtp' || r.kind !== 'video') return;
        const kbps = lastAt
          ? Math.round(((r.bytesSent - lastBytes) * 8) / (r.timestamp - lastAt))
          : 0;
        lastBytes = r.bytesSent; lastAt = r.timestamp;
        diag(`tx ${r.frameWidth || '?'}x${r.frameHeight || '?'} ` +
          `${Math.round(r.framesPerSecond || 0)}fps ${kbps}kbps ` +
          `limit=${r.qualityLimitationReason || 'none'}`);
      });
    } catch {}
  }, 5000);
}

function retuneAll() {
  if (S.role !== 'station') return;
  for (const p of viewerPeers()) tuneVideoSender(p);
}

// --------------------------------------------------------- zoom & lens

async function enumerateCameras() {
  try {
    const devices = await navigator.mediaDevices.enumerateDevices();
    const backs = devices.filter((d) => {
      if (d.kind !== 'videoinput') return false;
      const lb = d.label.toLowerCase();
      return lb.includes('back') || lb.includes('rear') ||
             lb.includes('environment') || (!lb.includes('front') && !lb.includes('user'));
    });
    const labels = ['Wide', '1×', 'Tele', '2×', '3×', '4×'];
    S.cameras = backs.map((d, i) => ({
      deviceId: d.deviceId, label: labels[i] || `${i + 1}×`, index: i
    }));
    // detect hardware zoom support once we have the live track
    const track = S.local?.getVideoTracks()[0];
    const caps = track?.getCapabilities?.();
    S.zoomHardware = !!(caps?.zoom);
    updateLensUI();
  } catch {}
}

function applyZoom(level) {
  S.zoomLevel = level;
  const track = S.local?.getVideoTracks()[0];
  if (track) {
    const caps = track.getCapabilities?.();
    if (caps?.zoom) {
      // hardware zoom — affects the actual stream seen by viewers
      const z = Math.min(Math.max(level, caps.zoom.min), caps.zoom.max);
      try { track.applyConstraints({ advanced: [{ zoom: z }] }); } catch {}
    } else {
      // digital fallback — CSS scale on the station's local preview only
      const localEl = document.getElementById('local');
      if (localEl) {
        localEl.style.transform = level > 1 ? `scale(${level})` : '';
        localEl.style.transformOrigin = 'center center';
      }
    }
  }
  showZoomChip(level);
}

function applyDigitalZoomViewer(level) {
  const remoteEl = document.getElementById('remote');
  if (!remoteEl) return;
  remoteEl.style.transform = level > 1 ? `scale(${level})` : '';
  remoteEl.style.transformOrigin = 'center center';
}

async function switchLens(index) {
  const cam = S.cameras[index];
  if (!cam) return;
  try {
    const ns = await navigator.mediaDevices.getUserMedia({
      video: { deviceId: { exact: cam.deviceId }, width: { ideal: 1280 }, height: { ideal: 720 } },
      audio: false
    });
    const nt = ns.getVideoTracks()[0];
    try { nt.contentHint = 'motion'; } catch {}
    for (const [, p] of S.peers) {
      const sender = p.pc?.getSenders().find((s) => s.track?.kind === 'video');
      if (sender) { try { await sender.replaceTrack(nt); } catch {} }
    }
    S.local.getVideoTracks().forEach((t) => { S.local.removeTrack(t); t.stop(); });
    S.local.addTrack(nt);
    const lv = document.getElementById('local');
    if (lv) { lv.srcObject = S.local; lv.style.transform = ''; }
    S.cameraIndex = index;
    S.zoomLevel = 1.0;
    updateLensUI();
  } catch { toast('Could not switch lens.'); }
}

function updateLensUI() {
  const bar = document.getElementById('lens-bar');
  if (!bar) return;
  if (S.cameras.length < 2) { bar.hidden = true; return; }
  bar.hidden = false;
  bar.replaceChildren();
  for (const cam of S.cameras) {
    const btn = document.createElement('button');
    btn.type = 'button';
    btn.className = 'lens-btn' + (cam.index === S.cameraIndex ? ' active' : '');
    btn.textContent = cam.label;
    btn.addEventListener('click', () => {
      if (S.role === 'station') {
        switchLens(cam.index);
      } else {
        const sp = stationPeer();
        if (sp) sig({ type: 'camera-control', lens: cam.index, to: sp.id }, sp);
        S.cameraIndex = cam.index;
        updateLensUI();
      }
    });
    bar.appendChild(btn);
  }
}

let _zoomHideTimer = null;
function showZoomChip(level) {
  const chip = document.getElementById('zoom-chip');
  if (!chip) return;
  chip.textContent = level <= 1.005 ? '1×' : `${level.toFixed(1)}×`;
  chip.hidden = false;
  clearTimeout(_zoomHideTimer);
  _zoomHideTimer = setTimeout(() => { chip.hidden = true; }, 2000);
}

function initPinch() {
  const vid = document.getElementById('remote');
  if (!vid) return;
  let p0 = null, p1 = null, zoomStart = 1.0;
  const dist = (a, b) => Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);

  vid.addEventListener('touchstart', (e) => {
    if (e.touches.length !== 2 || S.role !== 'viewer') return;
    p0 = { clientX: e.touches[0].clientX, clientY: e.touches[0].clientY };
    p1 = { clientX: e.touches[1].clientX, clientY: e.touches[1].clientY };
    zoomStart = S.zoomLevel;
    e.preventDefault();
  }, { passive: false });

  vid.addEventListener('touchmove', (e) => {
    if (e.touches.length !== 2 || p0 === null) return;
    e.preventDefault();
    const cur = dist(e.touches[0], e.touches[1]);
    const start = dist(p0, p1);
    const zoom = Math.min(Math.max(zoomStart * (cur / start), 1.0), 8.0);
    S.zoomLevel = zoom;
    showZoomChip(zoom);
    // live digital zoom feedback while pinching (instant, no network round-trip)
    if (!S.stationZoomSupported) applyDigitalZoomViewer(zoom);
  }, { passive: false });

  vid.addEventListener('touchend', () => {
    if (p0 === null) return;
    const sp = stationPeer();
    if (S.stationZoomSupported) {
      // station has hardware zoom — send signal, station applies it to the stream
      if (sp) sig({ type: 'camera-control', zoom: S.zoomLevel, to: sp.id }, sp);
    } else {
      // digital fallback — viewer zooms their own view locally
      applyDigitalZoomViewer(S.zoomLevel);
    }
    p0 = null; p1 = null;
  });
}

// ----------------------------------------------------------------- boot

function bail(msg) {
  diag(`bail: ${msg}`);
  S.closing = true;
  closeAllSignals();
  S.local?.getTracks().forEach((t) => t.stop());
  teardownAll();
  if (S.nativeShell) {
    // The native shell owns navigation and error UI.
    tellNative('error', { message: msg });
    S.closing = false;
    return;
  }
  show(S.channel ? el.role : el.channels);
  note(el.roleNote, msg);
  S.closing = false;
}

// Kept modest on purpose: a steady 540p feed holds up on a mid-range phone
// where 720p drops frames and freezes.
function cameraConstraints() {
  return {
    facingMode: { ideal: S.facing },
    width: { ideal: 960 },
    height: { ideal: 540 },
    frameRate: { ideal: 24, max: 30 }
  };
}

async function start(role) {
  if (!S.channel) return;
  S.role = role;
  S.pending = null;
  S.roomId = await roomIdFor(S.channel.key);
  diag(`start role=${role} room=${S.roomId} lan=${S.signalUrl || '-'} ` +
    `rv=${rendezvousBase() || 'NONE'} ticket=${S.token ? 'yes' : 'MISSING'}`);
  fetchIce();   // non-blocking; new PCs pick up S.ice when it lands

  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    return bail('Browsers only release the camera and microphone on https:// pages ' +
      '(or http://localhost). Open Tawny over https and try again.');
  }

  const audio = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };
  try {
    S.local = role === 'station'
      ? await navigator.mediaDevices.getUserMedia({ audio, video: cameraConstraints() })
      : await navigator.mediaDevices.getUserMedia({ audio });
  } catch (err) {
    return bail(err.name === 'NotAllowedError'
      ? 'Camera and microphone access was blocked. Allow it for this site, then try again.'
      : `Could not open the camera or microphone (${err.name}).`);
  }

  audioCtx(); // the tap that got us here also unlocks chime playback
  // Camera enumeration only works after getUserMedia grants permission (labels are blank before).
  if (role === 'station') enumerateCameras();

  el.remote.srcObject = null;
  el.remoteAudio.srcObject = null;
  el.remote.hidden = true;
  el.loader.hidden = role === 'station';
  el.sas.hidden = true;
  el.saschip.hidden = true;

  if (role === 'station') {
    el.local.srcObject = S.local;
    el.local.classList.add('fill');   // the Watcher sees its own camera, full frame
    el.local.hidden = false;
    el.cStation.hidden = false;
    el.cViewer.hidden = true;
    startMeter(S.local);              // dim screen shows the pet's room level
    updatePeerChip();
    keepAwake();
  } else {
    for (const t of S.local.getAudioTracks()) t.enabled = false; // push-to-talk
    el.cViewer.hidden = false;
    el.cStation.hidden = true;
  }

  // Rail label: the room name the user gave, with "monitor" appended.
  const room = (S.channel.name || 'Pet camera').trim();
  el.channel.textContent = /\bmonitor$/i.test(room) ? room : `${room} monitor`;

  show(el.live);
  status('Connecting', null);
  tellNative('live', { role });
  connectAll();

  if (role === 'station') openPair();
}

async function keepAwake() {
  if (!('wakeLock' in navigator)) return;
  const grab = async () => { try { S.wake = await navigator.wakeLock.request('screen'); } catch {} };
  await grab();
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible' && S.role === 'station') grab();
  });
}

function hangUp() {
  S.closing = true;
  for (const p of S.peers.values()) sig({ type: 'bye', to: p.id }, p);
  teardownAll();
  closeAllSignals();
  S.local?.getTracks().forEach((t) => t.stop());
  S.wake?.release?.();
  tellNative('idle');
  if (S.nativeShell) {
    // The native shell owns navigation; reloading here would drop #native and
    // strand the user in the web UI.
    tellNative('ended');
    return;
  }
  location.hash = '';
  location.reload();
}

// ------------------------------------------------------------- controls

const btnTalk = $('#btn-talk');
const setTalk = (on) => {
  if (S.role !== 'viewer' || !S.local) return;
  S.local.getAudioTracks().forEach((t) => { t.enabled = on; });
  btnTalk.classList.toggle('hot', on);
  btnTalk.querySelector('span:last-child').textContent = on ? 'Talking' : 'Hold to talk';
  const sp = stationPeer();
  if (sp) sig({ type: 'talking', to: sp.id, on }, sp);
};

btnTalk.addEventListener('pointerdown', (e) => { e.preventDefault(); setTalk(true); });
for (const ev of ['pointerup', 'pointercancel', 'pointerleave']) {
  btnTalk.addEventListener(ev, () => setTalk(false));
}
btnTalk.addEventListener('contextmenu', (e) => e.preventDefault());

document.addEventListener('keydown', (e) => {
  if (e.code === 'Space' && S.role === 'viewer' && !e.repeat && !el.live.hidden) {
    e.preventDefault(); setTalk(true);
  }
});
document.addEventListener('keyup', (e) => {
  if (e.code === 'Space' && S.role === 'viewer') setTalk(false);
});

$('#btn-chime').addEventListener('click', () => {
  el.chimes.hidden = !el.chimes.hidden;
  press($('#btn-chime'), !el.chimes.hidden);
});

el.chimes.addEventListener('click', (e) => {
  const sound = e.target.dataset.sound;
  if (!sound) return;
  const sp = stationPeer();
  if (!sp) return toast('No monitor connected yet.');
  sig({ type: 'chime', to: sp.id, sound }, sp);
  el.chimes.hidden = true;
  press($('#btn-chime'), false);
});

$('#btn-cam').addEventListener('click', async () => {
  const btn = $('#btn-cam');
  const sp = stationPeer();          // the Handheld holds one connection, to the Watcher
  const tr = sp?.pc?.getTransceivers().find(
    (t) => t.receiver.track?.kind === 'video' || t.sender.track?.kind === 'video'
  );
  if (!tr) return toast('Start a call first.');

  if (S.camSending) {
    const old = tr.sender.track;
    old?.stop();
    await tr.sender.replaceTrack(null);
    // Drop it from S.local too. The on-path adds a track every time, so without
    // this each off/on cycle left another ended track behind — and callPeer()
    // walks S.local.getTracks(), handing every corpse to the next connection.
    if (old) { try { S.local.removeTrack(old); } catch {} }
    try { tr.direction = 'recvonly'; } catch {}
    el.local.hidden = true;
    S.camSending = false;
  } else {
    try {
      const cam = await navigator.mediaDevices.getUserMedia({ video: { facingMode: 'user' } });
      const track = cam.getVideoTracks()[0];
      await tr.sender.replaceTrack(track);
      try { tr.direction = 'sendrecv'; } catch {}   // recvonly won't actually send
      S.local.addTrack(track);
      el.local.srcObject = new MediaStream([track]);
      el.local.hidden = false;
      S.camSending = true;
    } catch { return toast('Could not open your camera.'); }
  }
  // Changing the transceiver direction needs a fresh offer.
  if (sp) await makeOffer(sp);
  press(btn, S.camSending);
});

$('#btn-snap').addEventListener('click', () => {
  const v = el.remote;
  if (!v.videoWidth) return toast('Nothing to capture yet.');
  const c = document.createElement('canvas');
  c.width = v.videoWidth;
  c.height = v.videoHeight;
  c.getContext('2d').drawImage(v, 0, 0);
  const stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 19);
  const name = `tawny-${stamp}.png`;

  if (androidNative?.saveImage) {
    // Android WebView drops <a download> on blob: URLs; pass base64 across.
    androidNative.saveImage(c.toDataURL('image/png'), name);
    return;
  }
  c.toBlob((blob) => {
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = name;
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 10000);
    toast('Snapshot saved');
  }, 'image/png');
});

$('#btn-leave').addEventListener('click', hangUp);
$('#btn-stop').addEventListener('click', hangUp);

$('#btn-flip').addEventListener('click', async () => {
  if (!S.local) return;
  const btn = $('#btn-flip');
  if (btn.disabled) return;
  btn.disabled = true;

  const want = S.facing === 'environment' ? 'user' : 'environment';
  // Free the current camera first. Most phones refuse to open the second
  // camera while the first is still held — which is exactly what made "flip"
  // report "only one camera".
  S.local.getVideoTracks().forEach((t) => { S.local.removeTrack(t); t.stop(); });

  const grab = (facing) => navigator.mediaDevices.getUserMedia({
    video: {
      facingMode: { ideal: facing },
      width: { ideal: 960 }, height: { ideal: 540 },
      frameRate: { ideal: 24, max: 30 }
    }
  });

  let stream;
  try {
    stream = await grab(want);
    S.facing = want;
  } catch {
    try { stream = await grab(S.facing); }          // roll back to what we had
    catch {
      toast('Could not switch camera.');
      btn.disabled = false;
      return;
    }
  }

  const track = stream.getVideoTracks()[0];
  try { track.contentHint = 'motion'; } catch {}
  S.local.addTrack(track);
  el.local.srcObject = S.local;
  // The Watcher feeds every connected Handheld from this one capture.
  for (const p of viewerPeers()) {
    const sender = p.pc?.getSenders().find((s) => s.track?.kind === 'video');
    if (sender) { try { await sender.replaceTrack(track); } catch {} }
  }
  toast(S.facing === 'user' ? 'Front camera' : 'Rear camera');
  btn.disabled = false;
});

$('#btn-mute').addEventListener('click', () => {
  S.micOn = !S.micOn;
  S.local.getAudioTracks().forEach((t) => { t.enabled = S.micOn; });
  const btn = $('#btn-mute');
  press(btn, !S.micOn);
  btn.querySelector('span:last-child').textContent = S.micOn ? 'Mute mic' : 'Unmute mic';
});

$('#btn-dim').addEventListener('click', () => { el.dimmer.hidden = false; });
el.dimmer.addEventListener('click', () => { el.dimmer.hidden = true; });

$('#sas-ok')?.addEventListener('click', () => {
  try { localStorage.setItem(`tawny.sas.${S.channel?.id}`, '1'); } catch {}
  el.sas.hidden = true;
});
$('#sas-no')?.addEventListener('click', hangUp);

// ---------------------------------------------------------------- theme

// 'system' → follow prefers-color-scheme; 'light' / 'dark' → force it.
function applyTheme(mode) {
  const root = document.documentElement;
  if (mode === 'light' || mode === 'dark') root.dataset.theme = mode;
  else root.removeAttribute('data-theme');
}
function savedTheme() {
  try { return localStorage.getItem('tawny.theme') || 'system'; } catch { return 'system'; }
}
// Set from the toggle (and mirrored from the native shell). Applies instantly —
// pure CSS custom properties, so it works mid-session too.
window.tawnySetTheme = (mode) => {
  try { localStorage.setItem('tawny.theme', mode); } catch {}
  applyTheme(mode);
  // Persist on the native side so it survives an app restart.
  try { androidNative?.post(JSON.stringify({ event: 'theme', mode })); } catch {}
};
applyTheme(savedTheme());

// A small cycle toggle, top-right (moves above the controls during a live
// session). In the native shell it's Light ↔ Dark; in a browser, +System.
function initThemeToggle() {
  const order = (window.TawnyNative || window.webkit?.messageHandlers?.tawny)
    ? ['light', 'dark'] : ['system', 'light', 'dark'];
  const btn = document.createElement('button');
  btn.type = 'button';
  btn.id = 'theme-toggle';
  btn.setAttribute('aria-label', 'Change theme');
  const paint = () => {
    let m = savedTheme();
    if (!order.includes(m)) m = 'dark';
    btn.textContent = m === 'dark' ? '☾' : m === 'light' ? '☀' : '◐';
    btn.title = `Theme: ${m}`;
  };
  btn.addEventListener('click', () => {
    const cur = order.includes(savedTheme()) ? savedTheme() : order[0];
    window.tawnySetTheme(order[(order.indexOf(cur) + 1) % order.length]);
    paint();
  });
  paint();
  document.body.appendChild(btn);
}

// --------------------------------------------------------------- init

// Native-shell entry point. The Android app runs its own welcome / role /
// pairing screens, then loads this page with #native and calls this to drop
// straight into a live session. No-op in a plain browser.
window.tawnyStart = function (role, key, name, signalUrl, rendezvousUrl, token, opts) {
  if (!key || !/^[A-Za-z0-9_-]{16,64}$/.test(key)) return false;
  S.nativeShell = true;
  if (opts && opts.theme) {
    try { localStorage.setItem('tawny.theme', opts.theme); } catch {}
    applyTheme(opts.theme);
  }
  S.signalUrl = signalUrl || null;              // may be null on a cloud-only pairing
  if (rendezvousUrl) S.cfg.rendezvous = rendezvousUrl;
  if (token) S.token = token;
  const wanted = (name || 'Pet camera').slice(0, 40);
  const list = getChannels();
  let ch = list.find((c) => c.key === key);
  if (!ch) {
    ch = { id: crypto.randomUUID(), name: wanted, key };
    list.push(ch);
    setChannels(list);
    renderChannels();
  } else if (name && ch.name !== wanted) {
    // The Watcher was renamed since this device last paired — take the new name.
    ch.name = wanted;
    setChannels(list);
    renderChannels();
  }
  try { localStorage.setItem(ONBOARDED, '1'); } catch {}
  S.channel = ch;
  start(role === 'station' ? 'station' : 'viewer');
  return true;
};

// Back-compat alias for the not-yet-updated iOS shell (deferred parity work).
window.frentalkStart = window.tawnyStart;

(async function init() {
  if ('BarcodeDetector' in window) el.chScan.hidden = false;
  initThemeToggle();
  initPinch();
  renderChannels();

  const nativeBoot = /(^|&)native(&|$)/.test(location.hash.slice(1));

  // Config first, so tawnyStart() and start() see the rendezvous. It races the
  // native onPageFinished call: config.json is the base, but a value the shell
  // set on S.cfg first (rendezvous, turnMode) wins.
  try {
    const r = await fetch('config.json');
    if (r.ok) {
      const j = await r.json();
      S.cfg = {
        stun: Array.isArray(j.stun) ? j.stun : [],
        turnMode: j.turnMode || S.cfg.turnMode || 'auto',
        rendezvous: S.cfg.rendezvous || j.rendezvous || '',   // shell value wins
        authRequired: !!j.authRequired
      };
    }
  } catch {}

  // Pick the opening screen before any further wait, so there is no flash.
  let landed = nativeBoot;
  if (nativeBoot) {
    history.replaceState(null, '', location.pathname + location.search);
    // Stay blank; the native shell will call window.tawnyStart().
  } else if (location.hash.length > 1) {
    const raw = location.href;
    // Clear the key out of the address bar before anything else can see it.
    history.replaceState(null, '', location.pathname + location.search);
    if (adopt(raw)) landed = true;            // adopt() shows the join screen
    else note(el.chNote, 'That pairing link was not valid.');
  }

  if (!landed) {
    let onboarded = false;
    try { onboarded = !!localStorage.getItem(ONBOARDED); } catch {}
    const fresh = getChannels().length === 0 && !onboarded;
    show(fresh ? el.welcome : el.channels);
  }
})();

window.addEventListener('tawny:background', () => {
  if (S.role === 'station') status('Backgrounded', null);
});

window.addEventListener('tawny:foreground', () => {
  reopenAfterRestore();
  if (!el.live.hidden && S.peers.size) updateStatus();
});

// `pagehide` fires on backgrounding too (persisted: true), and the page comes
// back from bfcache rather than reloading. S.closing was only ever cleared in
// bail()/hangUp(), so a restored page had every dial() permanently short-
// circuited — the session looked alive but could never reconnect.
window.addEventListener('pagehide', () => { S.closing = true; closeAllSignals(); });
window.addEventListener('pageshow', (e) => { if (e.persisted) reopenAfterRestore(); });

function reopenAfterRestore() {
  if (!S.closing || !S.channel || !S.role || el.live.hidden) return;
  S.closing = false;
  diag('restored from background — reopening signaling');
  connectAll();
}
