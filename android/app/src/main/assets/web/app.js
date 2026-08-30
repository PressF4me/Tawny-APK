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
  sasnote: $('#sas-note'),
  stageNote: $('#stage-note'),
  stageNoteTitle: $('#stage-note-title'),
  stageNoteBody: $('#stage-note-body'),
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
  wake: null, meterStop: null, ac: null, closing: false, dimmed: false,
  editing: null, scanStop: null, pending: null,
  featured: null,
  // Torch. On the Monitor these are the truth; on a Viewer they are the last
  // thing the Monitor said, and nothing else is ever rendered.
  torchOn: false, torchSupported: false, torchFacing: null, torchTimer: null,
  cameras: [], cameraIndex: 0, zoomLevel: 1.0, zoomHardware: false, stationZoomSupported: false
};

// 1 Monitor + up to 5 Viewers. Kept in step with LocalWeb.kt MAX_PER_ROOM (6)
// and the rendezvous Durable Object.
const MAX_VIEWERS = 5;

// Candidates buffered before setRemoteDescription. A real negotiation sends a
// couple of dozen; anything past this is a peer filling memory.
const MAX_PENDING_ICE = 64;

// How long a Viewer waits for a picture before saying something useful.
const CONNECT_TIMEOUT_MS = 25000;

// ------------------------------------------------------------ power budget
//
// The Monitor is a phone left on a charger for hours, often an old handset
// with a tired battery. Dim mode is when it is genuinely dozing — nobody is
// looking at its screen, only at the Viewer's — so it is the moment to spend
// as little as possible while still sending a usable picture.
//
// Every number that trades quality for battery lives here, and nowhere else.
//
//  * `video` is the encode ceiling while dimmed. 960x540/24 is the normal
//    capture; /1.5 lands at 640x360, which is under the 640x480 we are willing
//    to defend as "still a useful look at the pet", and 15fps halves the
//    encoder's work. 500kbps is comfortably enough for that frame at that rate.
//  * `captureFps` is pushed at the camera track itself, not just the encoder.
//    That is the bigger win of the two: it takes the sensor, the ISP and the
//    whole capture pipeline down with it, not only the H.264 encode.
//  * `meterHz` — the level meter is 14 bars on a black screen. It ran on
//    requestAnimationFrame, i.e. at the panel's full refresh rate, to animate
//    something with 14 possible states. A few times a second reads identically.
const POWER = {
  video: { scaleDownBy: 1.5, maxFramerate: 15, maxBitrate: 500_000 },
  captureFps: 15,
  meterHz: 4
};

// How long the Monitor's light stays on before it gives up on being needed.
//
// The torch is by a wide margin the most expensive thing this app can ask of
// the Monitor: an LED at full current, and the heat it dumps into a phone that
// is already holding a camera open and encoding video for hours on a charger.
// Someone who lights the room to check on the pet is looking for ten seconds,
// not all night — so a light nobody turned back off turns itself off, and every
// Viewer's key updates to match.
const TORCH_MAX_MS = 5 * 60 * 1000;

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

/** The single message overlay on the video stage. Pass null to clear it. */
function stageNote(title, body) {
  if (!el.stageNote) return;
  if (!title) { el.stageNote.hidden = true; return; }
  el.stageNoteTitle.textContent = title;
  el.stageNoteBody.textContent = body || '';
  el.stageNote.hidden = false;
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

// The admission ticket arrives from somewhere attacker-supplyable — a pasted
// URL, a scanned code, a `tawny://pair` intent — and from here it is echoed
// into the signalling `hello` frame and the /turn query string. Hold it to the
// same charset every relay validates it against (TICKET_RE in worker.js,
// room.js and deno/main.ts, and the identical Regex in MainActivity's
// parsePairing) rather than forwarding whatever turned up. A ticket outside
// this set can only ever be refused, so refuse the whole link now instead of
// half-adopting a channel that will fail admission later.
const TICKET_RE = /^[A-Za-z0-9_-]{8,64}$/;

// Import a pairing link. Returns true when it was a valid one.
function adopt(raw) {
  let hash;
  try { hash = new URL(raw, location.href).hash.slice(1); }
  catch { return false; }
  const p = new URLSearchParams(hash);
  const key = p.get('k');
  if (!key || !/^[A-Za-z0-9_-]{16,64}$/.test(key)) return false;

  const name = (p.get('n') || 'Pet camera').slice(0, 40);
  const token = p.get('t');
  if (token) {
    if (!TICKET_RE.test(token)) return false;
    S.token = token;
  }

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
  let raf, timer;

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
    // Dimmed, the rail meter is behind an opaque overlay: writing to it is
    // work nobody can see, and each write is a style invalidation.
    const live = S.dimmed ? [dimBars] : [bars, dimBars];
    for (let i = 0; i < bars.length; i++) {
      const cls = i < lit ? (i >= bars.length - 2 ? 'peak' : 'lit') : '';
      for (const set of live) if (set[i].className !== cls) set[i].className = cls;
    }
    // Full refresh rate while someone is watching this screen; a few hertz
    // when it is black. rAF is also throttled hard when the page is hidden,
    // which would stall the meter — the timer keeps ticking either way.
    if (S.dimmed) timer = setTimeout(tick, 1000 / POWER.meterHz);
    else raf = requestAnimationFrame(tick);
  };
  tick();

  S.meterStop = () => {
    cancelAnimationFrame(raf);
    clearTimeout(timer);
    try { src.disconnect(); } catch {}
    for (const b of [...bars, ...dimBars]) b.className = '';
  };
}

function stopMeter() { S.meterStop?.(); S.meterStop = null; }

// --------------------------------------------------------------- chimes
//
// A chime is the Viewer reaching into the room to get the pet's attention, so
// the sounds are pet-calling sounds rather than UI beeps. They are short Vorbis
// clips bundled in the APK (public/sounds/, regenerate with tools/gen-chimes.sh)
// and fetched same-origin from the loopback server — which the page CSP already
// covers: `connect-src 'self'` for the fetch, and nothing else is needed because
// decodeAudioData is not a fetch and never touches the network.
//
// Every slug also has a synthesised approximation. A chime that makes no sound
// is worse than a chime that sounds wrong: the Viewer is told it played, and the
// pet hears nothing. So a clip that fails to load or decode degrades to the
// oscillator version rather than to silence.

const CHIMES = {
  bark:    { label: 'Dog toy',     file: 'sounds/bark.ogg',    gain: 0.9 },
  pspsps:  { label: 'Psp psp psp', file: 'sounds/pspsps.ogg',  gain: 1.0 },
  meow:    { label: 'Meow',        file: 'sounds/meow.ogg',    gain: 0.85 },
  goodboy: { label: 'Good boy',    file: 'sounds/goodboy.ogg', gain: 0.8 },
  bell:    { label: 'Bell',        file: 'sounds/bell.ogg',    gain: 1.0 }
};
const DEFAULT_CHIME = 'bell';

/** slug -> AudioBuffer, or null once we know that slug will never decode. */
const chimeBuffers = new Map();
/** slug -> in-flight load, so a burst of presses does not fetch five times. */
const chimeLoads = new Map();

/**
 * The one lookup into CHIMES, and the only sanitiser `sound` gets.
 *
 * `sound` arrives from a peer, so a plain `CHIMES[slug]` is wrong twice over:
 * it reaches inherited properties (`CHIMES['constructor']` is perfectly truthy,
 * and would have sent us off to `fetch(undefined)`), and it takes any type at
 * all. Own properties only, strings only, nothing else exists.
 */
function chimeSpec(slug) {
  // hasOwnProperty.call, not Object.hasOwn: the latter is Chrome 93 and this
  // page runs in whatever WebView the device has.
  return typeof slug === 'string' &&
    Object.prototype.hasOwnProperty.call(CHIMES, slug) ? CHIMES[slug] : null;
}

/** A display name for a slug. Anything not in the table is just "Chime". */
function chimeLabel(slug) {
  return chimeSpec(slug)?.label || 'Chime';
}

function loadChime(slug) {
  if (chimeBuffers.has(slug)) return Promise.resolve(chimeBuffers.get(slug));
  if (chimeLoads.has(slug)) return chimeLoads.get(slug);
  const spec = chimeSpec(slug);
  if (!spec) return Promise.resolve(null);

  const p = fetch(spec.file)
    .then((r) => {
      if (!r.ok) throw new Error(`HTTP ${r.status}`);
      return r.arrayBuffer();
    })
    // Safari still wants the callback form, and decodeAudioData detaches the
    // ArrayBuffer, so this copy cannot be retried from the same buffer.
    .then((buf) => new Promise((res, rej) => audioCtx().decodeAudioData(buf, res, rej)))
    .then((audio) => { chimeBuffers.set(slug, audio); return audio; })
    .catch((e) => {
      diag(`chime ${slug} unavailable (${e.message || e}); using the synth`);
      chimeBuffers.set(slug, null);   // remember the failure; stop refetching
      return null;
    })
    .finally(() => chimeLoads.delete(slug));

  chimeLoads.set(slug, p);
  return p;
}

/**
 * Warm the cache on the Monitor. Called from the same tap that unlocks the
 * AudioContext, because a chime has to be instant when it arrives — waiting on
 * a fetch would put the sound a round trip behind the Viewer's press, which for
 * "get the dog's attention" is the whole point missed.
 */
function preloadChimes() {
  for (const slug of Object.keys(CHIMES)) loadChime(slug);
}

function playChime(slug) {
  const key = chimeSpec(slug) ? slug : DEFAULT_CHIME;
  const ac = audioCtx();
  const buffer = chimeBuffers.get(key);
  if (buffer) return playChimeBuffer(ac, buffer, CHIMES[key].gain);

  // Not cached yet, or known bad. Make a sound *now* out of the synth, and warm
  // the cache for next time — never make the user wait on the network.
  synthChime(ac, key);
  if (!chimeBuffers.has(key)) loadChime(key);
}

function playChimeBuffer(ac, buffer, gain = 1) {
  const src = ac.createBufferSource();
  const g = ac.createGain();
  g.gain.value = 0.55 * gain;
  src.buffer = buffer;
  src.connect(g).connect(ac.destination);
  src.start();
}

/**
 * The fallback voices. Deliberately crude — these exist so the feature still
 * does something on a device where the clips did not decode, not to compete
 * with them.
 */
function synthChime(ac, kind) {
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

  // A band-passed noise burst: the "ps" of pspsps, and the fizz on a bark.
  const hiss = (start, dur, freq, q, peak) => {
    const frames = Math.ceil(ac.sampleRate * dur);
    const buf = ac.createBuffer(1, frames, ac.sampleRate);
    const d = buf.getChannelData(0);
    for (let i = 0; i < frames; i++) d[i] = (Math.random() * 2 - 1) * (1 - i / frames);
    const src = ac.createBufferSource();
    const bp = ac.createBiquadFilter();
    const g = ac.createGain();
    src.buffer = buf;
    bp.type = 'bandpass';
    bp.frequency.value = freq;
    bp.Q.value = q;
    g.gain.value = peak;
    src.connect(bp).connect(g).connect(out);
    src.start(t0 + start);
  };

  // A pitch glide — the shape shared by a meow and a bark.
  const glide = (start, dur, points, type = 'sawtooth', peak = 0.7) => {
    const o = ac.createOscillator();
    const g = ac.createGain();
    o.type = type;
    o.frequency.setValueAtTime(points[0][1], t0 + start);
    for (const [at, hz] of points.slice(1)) {
      o.frequency.exponentialRampToValueAtTime(hz, t0 + start + at);
    }
    g.gain.setValueAtTime(0.0001, t0 + start);
    g.gain.exponentialRampToValueAtTime(peak, t0 + start + 0.03);
    g.gain.exponentialRampToValueAtTime(0.0001, t0 + start + dur);
    o.connect(g).connect(out);
    o.start(t0 + start);
    o.stop(t0 + start + dur + 0.05);
  };

  if (kind === 'pspsps') {
    hiss(0, 0.09, 6300, 1.4, 0.9);
    hiss(0.17, 0.09, 6300, 1.4, 0.9);
    hiss(0.34, 0.15, 5800, 1.2, 0.9);
  } else if (kind === 'bark') {
    glide(0, 0.22, [[0, 340], [0.06, 190], [0.22, 130]], 'sawtooth', 0.8);
    hiss(0, 0.06, 1400, 0.8, 0.5);
  } else if (kind === 'meow') {
    glide(0, 0.62, [[0, 430], [0.24, 720], [0.62, 380]], 'sawtooth', 0.6);
  } else if (kind === 'goodboy') {
    glide(0, 0.24, [[0, 172], [0.24, 140]], 'sawtooth', 0.5);
    glide(0.30, 0.40, [[0, 180], [0.40, 120]], 'sawtooth', 0.5);
  } else {
    ping(880, 0, 1.1);
    ping(1318.5, 0.02, 1.3, 'sine', 0.6);
    ping(1760, 0.28, 0.9, 'sine', 0.45);
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

/** HMAC-SHA256(secret, msg) as lowercase hex. */
async function hmacHex(secret, msg) {
  const k = await crypto.subtle.importKey(
    'raw', new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']
  );
  const mac = await crypto.subtle.sign('HMAC', k, new TextEncoder().encode(msg));
  return [...new Uint8Array(mac)].map((b) => b.toString(16).padStart(2, '0')).join('');
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

  // Remember the code the user actually approved, not merely *that* they
  // approved once. A boolean hid this panel forever after the first good call,
  // which is exactly backwards: a rendezvous that swaps DTLS fingerprints on
  // session #7 would change the code, and nobody would ever be told.
  S.sasCode = code || null;
  let saved = null;
  try { saved = localStorage.getItem(`tawny.sas.${S.channel.id}`); } catch {}
  const changed = !!code && !!saved && saved !== '1' && saved !== code;

  el.sascode.textContent = code || 'unavailable — connection may be tampered with';
  el.sas.classList.toggle('sas--warn', !code || changed);
  if (el.sasnote) {
    el.sasnote.textContent = changed
      ? 'This monitor\u2019s safety code has CHANGED since you last checked it. '
        + 'If you did not re-pair or reinstall, stop and disconnect.'
      : 'Check this code matches the \u201cVerify:\u201d code shown on the Monitor\u2019s screen:';
  }
  // Legacy '1' from the old boolean scheme means "approved, code unknown" — ask
  // once more so we can record the real code.
  el.sas.hidden = !!code && saved === code;
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

      // The LAN leg is plain ws:// on a shared Wi-Fi, so the raw ticket must
      // never go out on it — anyone sniffing the network would get the room id
      // (it is in the URL) plus the ticket, which is exactly what the internet
      // rendezvous admits a viewer on. That pair turned "someone on your Wi-Fi"
      // into "someone anywhere". The LAN relay challenges us instead; see the
      // 'challenge' case below.
      if (tag !== 'cloud') { updateStatus(); return; }

      // Cloud: the ticket rides the first frame, inside TLS. The Monitor also
      // hands sha256(ticket) so the rendezvous knows which ticket to accept.
      const hello = { type: 'hello' };
      if (S.token) {
        hello.t = S.token;
        if (S.role === 'station') { try { hello.hashT = await sha256hex(S.token); } catch {} }
      }
      // Proof that this Monitor holds the channel key, so only it can re-key the
      // room. A second hash of the key under a different domain separator: the
      // relay cannot derive it from the room id, and it never sees the key.
      if (S.role === 'station' && S.channel?.key) {
        try { hello.a = await sha256hex(`tawny-auth-v1|${S.channel.key}`); } catch {}
      }
      try { ws.send(JSON.stringify(hello)); } catch {}
      updateStatus();
    };
    ws.onmessage = (e) => {
      let m; try { m = JSON.parse(e.data); } catch { return; }
      // The LAN relay proves we hold the channel key without either side
      // putting a reusable secret on the wire: it sends a per-connection nonce,
      // we return HMAC(channel key, nonce). A sniffer sees one response that is
      // useless on the next connection.
      if (m.type === 'challenge') {
        const nonce = String(m.n || '');
        if (!/^[0-9a-f]{32}$/.test(nonce) || !S.channel?.key) return;
        hmacHex(S.channel.key, `tawny-lan-v1|${nonce}`)
          .then((r) => { try { ws.send(JSON.stringify({ type: 'hello', r })); } catch {} })
          .catch(() => {});
        return;
      }
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
  // ws.onclose bails on `entry.dead` before it reaches its own cleanup loop, so
  // closing a transport on purpose used to strand every peer that was riding
  // it: dead RTCPeerConnections stayed in S.peers, viewerCount() over-reported
  // forever, the bitrate ladder throttled the real viewer, the pairing QR
  // stayed hidden, and after five ghosts every new viewer was refused.
  for (const p of [...S.peers.values()]) if (p.transport === entry) removePeer(p.id);
  try { entry.ws?.close(); } catch {}
  const i = S.signals.indexOf(entry);
  if (i >= 0) S.signals.splice(i, 1);
  updateStatus();
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
    // newPC() snapshots the server list at construction, so a TURN answer that
    // lands after the first connection was previously never used by it — the
    // one case that actually needs a relay (both peers behind carrier NAT) is
    // also the case where the connection is still failing when TURN arrives.
    if (turn.length) applyIceToLivePeers();
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
  clearTimeout(p.connectDeadline);
  clearTimeout(p.sasTimer);      // stop the safety-code retry loop
  if (p.qTimer) { clearInterval(p.qTimer); p.qTimer = null; }
  if (p.pc) { p.pc.onnegotiationneeded = null; try { p.pc.close(); } catch {} }
  if (p.audioEl) { p.audioEl.srcObject = null; p.audioEl.remove(); }
  S.peers.delete(id);
  if (S.featured === id) unfeature(id);

  if (S.role === 'viewer' && p.role === 'station') {
    // Lost the Monitor — clear the picture and wait for it to come back.
    stopMeter();
    S.remotePaused = false;
    stageNote(null);
    el.talkflag.hidden = true;
    S.remoteStream = null;
    el.remote.srcObject = null;
    el.remoteAudio.srcObject = null;
    el.remote.hidden = true;
    el.loader.hidden = false;
    // Nothing is authoritative any more, so the key goes inert rather than
    // sitting lit over a stream that is gone.
    S.torchOn = false;
    S.torchSupported = false;
    updateTorchUI();
  }
  // Monitor: the last Viewer left. Nobody is looking, so nothing justifies
  // holding an LED on in an empty room.
  if (S.role === 'station' && S.torchOn && !viewerCount()) {
    setTorch(false, 'no viewers left');
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
  S.remotePaused = false;
  S.captureLost = false;
  stageNote(null);
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
    // A paused Monitor is still "connected" — the picture just stopped. Saying
    // "Live" over a frozen frame is the lie this whole path exists to stop.
    if (S.remotePaused && st === 'connected') return status('Monitor paused', 'warn');
    if (st === 'connected') status('Live', 'live');
    else if (st === 'connecting' || st === 'new') status('Connecting', 'on');
    else if (!sp) status('Monitor offline', null);
    else status('Reconnecting', null);
    return;
  }
  if (S.captureLost) return status('Paused — screen off', 'warn');
  const vs = viewerPeers();
  if (!vs.length) { status('Waiting', null); return; }
  const live = vs.filter((p) => p.pc?.connectionState === 'connected').length;
  status(live
    ? `On air · ${live} watching`
    : 'Viewer connecting', live ? 'live' : 'on');
}

function updatePeerChip() {
  if (!el.peercount) return;
  if (S.role !== 'station') { el.peercount.hidden = true; return; }
  const n = viewerCount();
  el.peercount.hidden = n === 0;
  el.peercount.textContent = n === 1 ? '1 phone' : `${n} phones`;
  el.peercount.classList.toggle('is-busy', n >= 3);
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
      // The cap used to live only on 'peer-joined', so a peer that skipped
      // straight to an offer was answered unconditionally — and answerPeer
      // attaches the live camera and microphone. Gate both doors.
      if (S.role === 'station' && !S.peers.has(m.from) && viewerCount() >= MAX_VIEWERS) {
        diag(`offer refused: ${MAX_VIEWERS} viewers already`);
        return;
      }
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
        // Drained only by flushIce() on setRemoteDescription, so a peer that
        // joins and never offers used to grow this without limit. A real
        // negotiation is a couple of dozen candidates.
        const q = (p.pendingIce ||= []);
        if (q.length >= MAX_PENDING_ICE) { diag(`ice queue full from ${m.from}`); break; }
        q.push(m.candidate);
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
      // the confirmation toast just never appeared. It is also no longer echoed
      // back at the user — chimeLabel() only ever returns one of our own names.
      toast(`${chimeLabel(m.sound)} played on the Monitor`);
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
      if (S.role !== 'viewer') break;
      // The Monitor lost its camera (its screen went off, or the app was
      // backgrounded). Say so, instead of leaving a frozen frame up.
      if (typeof m.paused === 'boolean') {
        S.remotePaused = m.paused;
        stageNote(m.paused ? 'The monitor is paused' : null,
          'Its screen turned off or the app moved to the background. The picture '
          + 'comes back on its own when the monitor phone is woken.');
        status(m.paused ? 'Monitor paused' : 'On air', m.paused ? 'warn' : 'live');
        if (!m.petName) break;
      }
      if (typeof m.petName !== 'string') break;
      // Peer-controlled, and it ends up in SharedPreferences and the recent-
      // sessions JSON, so bound it the same way the name field does.
      const petName = m.petName.replace(/\s+/g, ' ').trim().slice(0, 40);
      if (!petName) break;
      if (S.channel) {
        S.channel.name = petName;
        const list = getChannels();
        const ch = list.find((c) => c.key === S.channel.key);
        if (ch) { ch.name = petName; setChannels(list); }
        if (el.channel) el.channel.textContent = petName;
      }
      if (S.nativeShell) tellNative('petname', { name: petName });
      break;
    }
    case 'camera-control': {
      // Station receives zoom/lens commands from a viewer. These are entirely
      // peer-controlled: NaN used to reach applyConstraints and render "NaN×",
      // and repeated lens commands re-ran getUserMedia in a loop, letting a
      // viewer cycle the Monitor's camera hardware.
      if (S.role !== 'station') break;
      if (m.zoom != null) {
        const z = Number(m.zoom);
        if (Number.isFinite(z)) applyZoom(Math.min(Math.max(z, 1), 8));
      }
      if (m.lens != null) {
        const i = Number(m.lens);
        const now = Date.now();
        if (Number.isInteger(i) && i >= 0 && i < S.cameras.length
            && now - (handle._lensAt || 0) > 1500) {
          handle._lensAt = now;
          switchLens(i);
        }
      }
      break;
    }
    case 'torch': {
      if (S.role === 'station') {
        // Peer-controlled, and it drives hardware. Only a real boolean does
        // anything, and it is rate-limited the same way the lens command is so
        // a Viewer cannot strobe the Monitor's LED.
        if (typeof m.on !== 'boolean') break;
        const now = Date.now();
        if (now - (handle._torchAt || 0) < 400) break;
        handle._torchAt = now;
        setTorch(m.on, `viewer ${m.from}`);
        break;
      }
      // Viewer: the Monitor is authoritative. Render this, nothing else.
      S.torchOn = !!m.on;
      S.torchSupported = !!m.supported;
      S.torchFacing = typeof m.facing === 'string' ? m.facing : null;
      updateTorchUI();
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

/** Push a newly-fetched ICE server list into connections that already exist. */
function applyIceToLivePeers() {
  for (const p of S.peers.values()) {
    const pc = p.pc;
    if (!pc || pc.connectionState === 'closed') continue;
    try { pc.setConfiguration({ iceServers: iceServers(), iceCandidatePoolSize: 0 }); }
    catch { continue; }
    // Only restart the ones that need it; a healthy connection stays untouched.
    const st = pc.iceConnectionState;
    if (st === 'failed' || st === 'disconnected' || st === 'checking' || st === 'new') {
      try { pc.restartIce(); } catch {}
    }
  }
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
      if (!S.remotePaused) stageNote(null);
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
    if (st === 'connected') {
      clearTimeout(peer.iceKick);
      clearTimeout(peer.connectDeadline);
      peer.connectDeadline = null;
    } else if (st === 'failed') reconnectPeer(peer);
    updateStatus();
    updatePeerChip();
  };

  // Without TURN, two peers both behind carrier-grade NAT never gather a usable
  // candidate pair — and nothing timed that out, so the Viewer sat on
  // "Connecting" indefinitely with no idea why. Say something after a while.
  if (S.role === 'viewer' && peer.role === 'station') {
    clearTimeout(peer.connectDeadline);
    peer.connectDeadline = setTimeout(() => {
      if (peer.pc && peer.pc.connectionState !== 'connected') {
        diag(`connect timeout (ice=${peer.pc.iceConnectionState})`);
        const relayed = (S.ice || []).some((e) => /^turns?:/i.test(
          Array.isArray(e.urls) ? e.urls[0] || '' : e.urls || ''));
        stageNote('Still trying to connect', relayed
          ? 'Check the monitor phone is awake with Tawny open.'
          : 'A direct connection could not be made from this network. If both '
            + 'phones are on mobile data, try putting this one on Wi-Fi.');
        status('Cannot connect', 'warn');
      }
    }, CONNECT_TIMEOUT_MS);
  }

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
  // And the light: a Viewer joining a session where the room is already lit
  // must show a lit key, not an "off" one over an obviously lit picture.
  if (S.role === 'station') {
    S.torchSupported = torchCapable();
    sig({ ...torchState(), to: peer.id }, peer);
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
    // Dimmed, the Monitor drops to the power budget: a smaller, slower, leaner
    // encode. The Viewer keeps a picture throughout — it just gets the cheap
    // one while the phone it comes from is asleep in the corner.
    const cap = S.dimmed ? POWER.video : null;
    p.encodings[0].maxBitrate = Math.min(bitrateFor(viewerCount()), cap?.maxBitrate ?? Infinity);
    p.encodings[0].maxFramerate = Math.min(viewerCount() > 3 ? 20 : 24, cap?.maxFramerate ?? Infinity);
    p.encodings[0].scaleResolutionDownBy = cap?.scaleDownBy ?? 1;
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

// ------------------------------------------------------------------ torch
//
// The person watching from the other room can turn the Monitor phone's camera
// light on to see a pet in the dark. It is driven as a constraint on the very
// video track WebRTC is already sending — never a second getUserMedia and
// never the native CameraManager — so there is nothing for it to collide with:
// the camera is already open, and the light is one more knob on it.
//
// Every phone disagrees about whether that knob exists. Front cameras almost
// never have it, plenty of rear ones don't expose it to the browser, and the
// emulator has no LED at all. So the Monitor is the only thing that decides:
// it reads `getCapabilities().torch` off the live track and broadcasts an
// authoritative {on, supported, facing} to every Viewer. A Viewer never
// guesses, never assumes, and renders only what it was told.

const localVideoTrack = () => S.local?.getVideoTracks?.()[0] || null;

/** Does the track we are *currently sending* expose a controllable light? */
function torchCapable() {
  const t = localVideoTrack();
  if (!t || t.readyState !== 'live') return false;
  try { return t.getCapabilities?.().torch === true; } catch { return false; }
}

const torchState = () => ({
  type: 'torch', on: S.torchOn, supported: S.torchSupported, facing: S.facing
});

function broadcastTorch() {
  if (S.role !== 'station') return;
  for (const p of viewerPeers()) sig({ ...torchState(), to: p.id }, p);
}

/** Re-read the capability off the live track and tell everyone watching. */
function refreshTorchSupport() {
  if (S.role !== 'station') return;
  S.torchSupported = torchCapable();
  if (!S.torchSupported) S.torchOn = false;
  broadcastTorch();
}

/**
 * Push the light constraint at the live track.
 *
 * `advanced` is the only form Chromium honours for torch, and this is kept a
 * *separate* applyConstraints call from the resolution/framerate one on
 * purpose — applyConstraints replaces the whole constraint set it is given, so
 * folding the two together means whichever ran last wins and the other is lost.
 */
async function pushTorch(on) {
  const t = localVideoTrack();
  if (!t) return false;
  try { await t.applyConstraints({ advanced: [{ torch: !!on }] }); return true; }
  catch (e) { diag(`torch apply failed: ${e && e.name}`); return false; }
}

async function setTorch(on, why) {
  if (S.role !== 'station') return;
  on = !!on;
  clearTimeout(S.torchTimer);
  S.torchTimer = null;
  S.torchSupported = torchCapable();
  if (on && !S.torchSupported) { S.torchOn = false; broadcastTorch(); return; }
  // Turning it *off* is still attempted when the capability has already gone
  // with the track — a stale "on" must never be the last thing a Viewer was
  // told, and a failed off must never latch. But on a phone whose camera has
  // no light and that we never lit, there is physically nothing to put out;
  // pushing anyway just earns an OverconstrainedError in the diagnostics on
  // every single session end, which is noise that hides real faults.
  const worthPushing = on || S.torchOn || S.torchSupported;
  const ok = worthPushing ? await pushTorch(on) : false;
  S.torchOn = on && ok;
  if (S.torchOn) {
    S.torchTimer = setTimeout(() => {
      setTorch(false, 'auto-off');
      toast('Light turned off to save the battery.');
    }, TORCH_MAX_MS);
  }
  diag(`torch ${S.torchOn ? 'on' : 'off'}${why ? ` (${why})` : ''}`);
  broadcastTorch();
}

/**
 * Dim mode re-constrains this same track (see setDim) to drop the capture to
 * the power budget — and, per the note in pushTorch, that call replaces the
 * whole constraint set including `advanced`. Without re-asserting here the
 * light went out the moment the Monitor dozed. Torch has nothing to do with
 * screen brightness and should keep burning while the phone's panel is black.
 */
async function reassertTorch() {
  if (S.role === 'station' && S.torchOn) await pushTorch(true);
}

/** The one-line reason the key is inert, in the user's terms rather than ours. */
function torchHint() {
  if (!stationPeer()) return 'No monitor connected yet.';
  if (S.torchFacing === 'user') return 'Switch the monitor to its back camera.';
  return "The monitor's camera has no light.";
}

function updateTorchUI() {
  const btn = $('#btn-torch');
  if (!btn || S.role !== 'viewer') return;
  const ok = !!S.torchSupported;
  // Deliberately not the `disabled` attribute: an inert key that swallows the
  // tap leaves the user with no way to find out *why* it is inert, which is
  // exactly how a control comes to look broken. It wears the disabled styling
  // and answers with the reason when pressed.
  btn.classList.toggle('is-unavailable', !ok);
  btn.setAttribute('aria-disabled', ok ? 'false' : 'true');
  press(btn, ok && S.torchOn);
  btn.querySelector('span:last-child').textContent =
    ok && S.torchOn ? 'Light on' : 'Light';
  btn.title = ok ? '' : torchHint();
}

// The native shell ends sessions by its own route too (the "End the call?"
// dialog, onDestroy). Releasing the camera drops the light with it, but this
// is the explicit off so nothing rests on that side effect.
window.tawnyTorchOff = () => { setTorch(false, 'native shell'); };

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
  // Same rule as the flip: the light belongs to the lens that is open, so it
  // goes out before that lens does and is re-derived for the new one.
  await setTorch(false, 'lens switch');
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
    refreshTorchSupport();
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

  S.captureLost = false;
  // A fresh session starts dark on both sides. The Monitor works out whether
  // its camera even has a light once the track is live; the Viewer waits to be
  // told and shows the key inert until it is.
  S.torchOn = false;
  S.torchSupported = role === 'station' && torchCapable();
  S.torchFacing = null;
  clearTimeout(S.torchTimer);
  S.torchTimer = null;
  watchLocalTracks();

  audioCtx(); // the tap that got us here also unlocks chime playback
  // Camera enumeration only works after getUserMedia grants permission (labels are blank before).
  if (role === 'station') {
    enumerateCameras();
    // The Monitor is the end that plays chimes. Decode them now, while it is
    // idle and unlocked, so an arriving chime is instant instead of a fetch
    // behind the Viewer's press.
    preloadChimes();
  }

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
    updateTorchUI();
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

// ---------------------------------------------------------- capture loss
//
// Android revokes the camera (and mutes the mic) as soon as the app is not in
// the foreground, and there is no foreground service here. Nothing used to
// notice: the Monitor kept its socket and its peer connections, the rail still
// read "On air", and every Viewer sat on a frozen last frame forever. These
// three functions make that state visible and recoverable.

/** Mark the Monitor's capture as lost and tell everyone watching. */
function onCaptureLost(why) {
  if (S.role !== 'station' || S.captureLost) return;
  S.captureLost = true;
  diag(`capture lost (${why})`);
  status('Paused — screen off', 'warn');
  // The camera has been taken back, so the light went with it. Say so, or every
  // Viewer keeps a lit key over a room that is now dark.
  clearTimeout(S.torchTimer);
  S.torchTimer = null;
  S.torchOn = false;
  S.torchSupported = false;
  for (const p of S.peers.values()) sig({ type: 'meta', to: p.id, paused: true }, p);
  broadcastTorch();
  tellNative('paused', {});
}

/** Attach loss handlers to whatever is currently in S.local. */
function watchLocalTracks() {
  if (S.role !== 'station' || !S.local) return;
  for (const t of S.local.getTracks()) {
    if (t._tawnyWatched) continue;
    t._tawnyWatched = true;
    t.addEventListener('ended', () => onCaptureLost(`${t.kind} ended`));
    t.addEventListener('mute', () => onCaptureLost(`${t.kind} muted`));
  }
}

/**
 * Re-open the camera and microphone after the app comes back, and swap the new
 * tracks into every live peer connection so viewers recover without re-dialling.
 */
async function reacquireLocal() {
  if (S.role !== 'station' || !S.captureLost || S.reacquiring) return;
  S.reacquiring = true;
  try {
    const audio = { echoCancellation: true, noiseSuppression: true, autoGainControl: true };
    const fresh = await navigator.mediaDevices.getUserMedia({
      audio, video: cameraConstraints(),
    });
    // Retire the dead tracks, then adopt the new ones into the same stream so
    // everything already pointed at S.local keeps working.
    for (const t of S.local ? S.local.getTracks() : []) {
      try { S.local.removeTrack(t); } catch {}
      try { t.stop(); } catch {}
    }
    if (!S.local) S.local = new MediaStream();
    for (const t of fresh.getTracks()) S.local.addTrack(t);

    // A sender whose track has ended may report `sender.track === null`, so the
    // kind comes from the transceiver, which keeps it for the life of the m-line.
    for (const p of S.peers.values()) {
      for (const tr of p.pc?.getTransceivers() || []) {
        const kind = tr.sender?.track?.kind || tr.receiver?.track?.kind;
        if (!kind) continue;
        const next = S.local.getTracks().find((t) => t.kind === kind);
        if (next && tr.sender) { try { await tr.sender.replaceTrack(next); } catch {} }
      }
    }

    el.local.srcObject = S.local;
    startMeter(S.local);
    watchLocalTracks();
    S.captureLost = false;
    diag('capture recovered');
    // A brand-new track: the light is off, and whether it can come back on is
    // a question about this track, not the dead one.
    refreshTorchSupport();
    for (const p of S.peers.values()) sig({ type: 'meta', to: p.id, paused: false }, p);
    tellNative('resumed', {});
    updateStatus();
  } catch (e) {
    diag(`capture recovery failed: ${e && e.name}`);
    status('Paused — tap to resume', 'warn');
  } finally {
    S.reacquiring = false;
  }
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
  setDim(false);   // never leave the live screen with the backlight pinned down
  // ...and never walk away from a phone with its light still burning. Stopping
  // the tracks below releases the camera and drops the torch with it; this is
  // the explicit off, so nothing depends on that side effect.
  setTorch(false, 'session ended');
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

$('#btn-torch')?.addEventListener('click', () => {
  if (S.role !== 'viewer') return;
  const sp = stationPeer();
  if (!sp) return toast('No monitor connected yet.');
  if (!S.torchSupported) return toast(torchHint());
  const want = !S.torchOn;
  sig({ type: 'torch', to: sp.id, on: want }, sp);
  // The press state moves now so the key feels connected to the thumb; the
  // Monitor's reply lands within a frame or two and is what actually sets it.
  press($('#btn-torch'), want);
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
  // Put the light out on the camera we are about to close. The far side of a
  // flip is usually the front camera, which has no torch at all, so the state
  // is re-derived and re-broadcast once the new track is in (below).
  await setTorch(false, 'camera flip');
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
  // New camera, new answer to "does this one have a light?" — and S.facing has
  // moved, so the Viewer's hint can now say which way to flip it back.
  refreshTorchSupport();
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

// Dim mode. The overlay used to be only a black <div> laid over the page: on
// an LCD the backlight stayed wherever the user had left it, and on an AMOLED
// the panel still drove every pixel while the compositor, the preview decode
// and a 60fps meter all kept running underneath. "Dim" cost almost nothing.
//
// Going dim now means all of it at once — the native shell pulls the backlight
// down (the one thing a web page cannot reach), the self-preview stops, the
// animations stop, the meter drops to a few hertz, and both the camera and the
// encoder drop to the power budget above. The stream never stops: the Viewer
// keeps its picture throughout, it just gets the cheap one while the phone
// sending it is asleep in the corner.
async function setDim(on) {
  if (S.dimmed === on) return;
  S.dimmed = on;
  el.dimmer.hidden = !on;
  document.body.classList.toggle('dimmed', on);
  try { androidNative?.setDimmed?.(on); } catch {}

  // The self-preview is a second, full-rate video sink for a stream this phone
  // is already busy encoding — and nobody is looking at it.
  try { on ? el.local.pause() : await el.local.play(); } catch {}

  // Take the capture itself down, not just the encode. This is the half the
  // sensor and the ISP actually feel; the encoder cap alone leaves them at 24.
  for (const t of S.local?.getVideoTracks() ?? []) {
    try {
      await t.applyConstraints(on
        ? { ...cameraConstraints(), frameRate: { ideal: POWER.captureFps, max: POWER.captureFps } }
        : cameraConstraints());
    } catch {}
  }
  // That call just replaced the track's whole constraint set, `advanced` and
  // all. The light is independent of the screen and stays on through a doze.
  await reassertTorch();
  for (const peer of S.peers.values()) tuneVideoSender(peer);
  diag(`dim ${on ? 'on' : 'off'}`);
}

$('#btn-dim').addEventListener('click', () => setDim(true));
el.dimmer.addEventListener('click', () => setDim(false));

$('#sas-ok')?.addEventListener('click', () => {
  // Store the approved code itself so a later change re-raises this panel.
  if (S.sasCode) {
    try { localStorage.setItem(`tawny.sas.${S.channel?.id}`, S.sasCode); } catch {}
  }
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
  if (token) {
    if (!TICKET_RE.test(token)) return false;   // same guard as adopt()
    S.token = token;
  }
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
  // "Backgrounded" was a developer word rendered straight into the live rail.
  // Say what it means for the person watching.
  if (S.role === 'station') status('Paused — screen off', 'warn');
});

window.addEventListener('tawny:foreground', () => {
  reopenAfterRestore();
  reacquireLocal();
  if (!el.live.hidden && S.peers.size) updateStatus();
});

// The same loss happens without the native shell (a browser tab going hidden),
// so recover on the page's own visibility signal too.
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') reacquireLocal();
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
