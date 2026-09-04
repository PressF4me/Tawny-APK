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
  peercount: $('#peercount'), peercountN: $('#peercount b'), peerlabel: $('#peerlabel'),
  rail: $('#rail'),
  battchip: $('#battchip'), battFill: $('#battchip .batt-fill'), battPct: $('#battchip .batt-pct'),
  sas: $('#sas'), sascode: $('#sas-code'), saschip: $('#sas-chip'),
  sasnote: $('#sas-note'), sasok: $('#sas-ok'), sasno: $('#sas-no'),
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
  pairExpiry: $('#pair-expiry'),
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
  // Pairing codes. On the Monitor: the code currently on screen and the moment
  // it stops admitting new phones. On a Viewer: the code it scanned, presented
  // once to the Monitor to be let in. See PAIR_TTL_MS.
  pairCode: null, pairSeen: null,
  // Signaling transports (0-2). A Handheld commits to one; the Watcher may hold
  // its LAN relay and the rendezvous at once.
  signals: [], committedTag: null, raceTimer: null, myId: null,
  // One RTCPeerConnection per remote peer. A Handheld holds exactly one (to the
  // Watcher); the Watcher fans out — one per Handheld, up to MAX_VIEWERS.
  peers: new Map(),           // id -> { id, role, transport, pc, stream, iceKick, negotiating, talking, audioEl, sas, sasOk }
  local: null, remoteStream: null,
  // Monitor: the id of the one cloud Viewer whose safety code is on screen
  // right now. The card is pinned to a peer, never to "the newest handshake",
  // so a second phone connecting mid-read cannot swap the code out.
  sasAsk: null,
  ice: [], iceTimer: null, relayOnly: false,  // filled by fetchIce() when a rendezvous is configured
  facing: 'environment', micOn: true, camSending: false,
  wake: null, meterStop: null, ac: null, closing: false, dimmed: false,
  editing: null, scanStop: null, pending: null,
  featured: null,
  // Torch. On the Monitor these are the truth; on a Viewer they are the last
  // thing the Monitor said, and nothing else is ever rendered.
  torchOn: false, torchSupported: false, torchFacing: null, torchTimer: null,
  torchAsk: false,            // viewer: an "on" is in flight, so a refusal can explain itself
  // Battery. On the Monitor: this phone's charge, mirrored to every Handheld as
  // it changes. On a Viewer: the last thing the Monitor said, rendered as-is.
  battery: { level: null, charging: null },
  remoteBattery: { level: null, charging: null },
  cameras: [], cameraIndex: 0, zoomLevel: 1.0, zoomHardware: false, stationZoomSupported: false
};

// One Monitor, three Viewers. Three is the product, not a preference: there is
// deliberately no setting, no URL parameter and no message that raises it, and
// the same number is compiled into every relay that could otherwise admit a
// fourth — LocalWeb.kt and rendezvous/room.js both cap a room at
// MAX_PER_ROOM = 4 (this + the Monitor), as do server.js and the Deno port.
// A fourth phone is refused and told why; it never displaces one of the three.
const MAX_VIEWERS = 3;

// The one sentence a refused fourth phone sees, wherever the refusal came from
// — the Monitor's own cap or a relay's 4003.
const FULL_MESSAGE =
  `This monitor is full (${MAX_VIEWERS} phones). Close Tawny on one of the `
  + 'other phones, then try this code again.';

// How long a pairing code is good for.
//
// A photograph of a QR, or a `tawny://pair` link forwarded through a chat app,
// is a bearer credential: it carries the channel key. Ten minutes is how long
// that credential can be *used to pair a new phone*. It is not a session
// timeout — a phone that finished pairing inside the window keeps working
// afterwards, for as long as the channel exists.
const PAIR_TTL_MS = 10 * 60 * 1000;

// The one sentence a phone sees when it arrives with a code that has run out,
// wherever the refusal came from — its own pre-flight check on the scanned
// link, or the Monitor turning it away over LAN or the internet relay.
const EXPIRED_MESSAGE =
  'That pairing code has expired. Show a new code on the monitor phone and '
  + 'scan it again.';

// Candidates buffered before setRemoteDescription. A real negotiation sends a
// couple of dozen; anything past this is a peer filling memory.
const MAX_PENDING_ICE = 64;

// How long a Viewer waits for a picture before saying something useful.
const CONNECT_TIMEOUT_MS = 25000;

// How many times a Monitor re-dials the internet relay after it refuses the
// room, before calling it unreachable. The refusal is usually this phone's own
// dropped socket still being counted as a live Monitor; backing off 1, 2, 4, 8,
// 15, 15s covers roughly the window that takes to clear.
const STATION_CLOUD_RETRIES = 6;

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
  // A nominal state (live, or a connection still coming up) collapses to just
  // the dot on a portrait phone; a state the user might act on keeps its word.
  const nominal = kind === 'live' || kind === 'on' || /^waiting$/i.test(text);
  el.rail?.classList.toggle('rail-ok', nominal);
  bumpRail();
}

// The rail steps back to a whisper after a few quiet seconds; any call here
// (a status change, a phone joining) brings it back to full first.
let railRestTimer = null;
function bumpRail() {
  if (!el.rail) return;
  el.rail.classList.remove('rail-rest');
  clearTimeout(railRestTimer);
  railRestTimer = setTimeout(() => el.rail.classList.add('rail-rest'), 5000);
}

// A short, real-sounding name for this phone, shown in the other end's rail.
// Chrome / Android WebView expose the model directly; elsewhere the UA string
// is the best we have, and on iOS that is only ever "iPhone" / "iPad" - still
// short and recognisable, which is all this is for. Never persisted, never
// sent anywhere but the paired peer.
function shortDeviceLabel(model) {
  const ua = navigator.userAgent || '';
  let m = (model || '').trim();
  if (!m) {
    if (/iPhone/.test(ua)) m = 'iPhone';
    else if (/iPad/.test(ua)) m = 'iPad';
    else {
      const paren = ua.match(/\(([^)]*)\)/);
      const seg = paren ? paren[1].split(';').map((s) => s.trim()) : [];
      const i = seg.findIndex((s) => /^Android\s/i.test(s));
      m = (i >= 0 && seg[i + 1]) ? seg[i + 1] : '';
      m = m.replace(/\s+Build\/.*$/i, '').replace(/^wv$/i, '');
      if (!m && /Android/.test(ua)) m = 'Android';
    }
  }
  if (!m) m = 'Phone';
  m = m
    .replace(/^SM-A505\w*/i, 'Galaxy A50')
    .replace(/^SM-A515\w*/i, 'Galaxy A51')
    .replace(/^SM-G97[03]\w*/i, 'Galaxy S10')
    .replace(/^SM-G99\d\w*/i, 'Galaxy S21')
    .replace(/^SM-/i, 'Galaxy ')
    .replace(/[^\x20-\x7E]+/g, '').trim();
  return m.length > 18 ? m.slice(0, 18) : m;
}

async function resolveDeviceLabel() {
  let model = '';
  try {
    const v = await navigator.userAgentData?.getHighEntropyValues?.(['model']);
    model = v?.model || '';
  } catch {}
  S.deviceLabel = shortDeviceLabel(model);
}
resolveDeviceLabel();

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

// A script error that reaches the top is a bug we want in the flight recorder,
// not just the console the phone cannot show. WebView reports the line as 1 for
// anything reached through evaluateJavascript, so lean on the stack instead.
const errFrame = (e) => {
  const st = (e && (e.error?.stack || e.reason?.stack || e.stack)) || '';
  return String(st).split('\n').slice(0, 3).map((l) => l.trim()).join('  ');
};
addEventListener('error', (e) => {
  diag(`js error: ${e.message || e.error?.message || e} @ ${e.filename || '?'}:${e.lineno || 0}` +
    (errFrame(e) ? `  ${errFrame(e)}` : ''));
});
addEventListener('unhandledrejection', (e) => {
  const r = e.reason;
  diag(`js rejection: ${(r && (r.message || r)) || 'unknown'}` +
    (errFrame(e) ? `  ${errFrame(e)}` : ''));
});

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
      // Drop the once-reviewed safety-code flag, this phone's pairing bond and
      // (on a Monitor) the list of phones it had let in, so a re-pair starts
      // clean — and so a deleted channel really does stop admitting its old
      // Handhelds rather than remembering them past the delete.
      try {
        localStorage.removeItem(`tawny.sasok.${ch.id}`);
        localStorage.removeItem(`tawny.sas.${ch.id}`);
        localStorage.removeItem(`tawny.bond.${ch.id}`);
        localStorage.removeItem(`tawny.paired.${ch.id}`);
      } catch {}
      renderChannels();
      toast('Monitor removed');
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
  if (!name) return toast('Give this monitor a name.');
  const list = getChannels();
  if (S.editing) {
    const found = list.find((c) => c.id === S.editing.id);
    if (found) found.name = name;
  } else {
    if (list.length >= 12) return toast('That is as many monitors as Tawny keeps.');
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
    note(el.setupNote, lastPairError
      || 'That was not a Tawny pairing link. Try scanning it with your phone camera instead.');
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
//
// ---- a pairing code stops working after ten minutes -------------------------
//
// The awkward truth first: the 128-bit channel key travels *inside* the pairing
// code, and the key is what every relay admits a device on. So an expiry cannot
// be a check the bearer of the code performs on itself — a client that lies
// about the clock, or simply an older build, would walk straight through it —
// and it cannot be a check the relay performs either, because the relay is
// handed the same long-lived room id and admission ticket by an expired code as
// by a fresh one. (The rendezvous ticket is a *different* thing with a
// different life; see SECURITY.md, "Signaling admission".)
//
// What actually holds is the Monitor. It is the only party that knows when it
// put a code on screen, it is the party that owns the camera, and every
// transport funnels through it: on the LAN it *is* the relay, and over the
// internet it still answers every offer itself. So:
//
//   * every code carries a random `c` and its deadline `e`;
//   * the Monitor keeps `c` and rotates it every ten minutes, by its own clock;
//   * a phone that has never been let in must present the `c` the Monitor is
//     showing *now*, or it is refused before a single track is attached;
//   * a phone that HAS been let in is remembered by a `pid` it minted itself,
//     so an established Handheld never has to re-pair.
//
// `e` in the link is a courtesy, not the enforcement: it lets the scanning
// phone say "this code expired" immediately instead of dialling into a refusal.
// Lying about it buys nothing — the Monitor refuses either way.

const PAIRED_KEY = () => `tawny.paired.${S.channel?.id}`;
const BOND_KEY = () => `tawny.bond.${S.channel?.id}`;
// One Monitor holds three phones; twice that is room for a household that has
// re-installed a couple of times, and small enough that a stolen code cannot
// quietly enrol an army.
const MAX_REMEMBERED_PEERS = 8;
const HEX32 = /^[a-f0-9]{32}$/;

/** Monitor: mint the code that goes on screen, and start its ten minutes. */
function newPairCode(ttl = PAIR_TTL_MS) {
  const b = new Uint8Array(8);
  crypto.getRandomValues(b);
  S.pairCode = { c: b64url(b), exp: Date.now() + ttl };
  return S.pairCode;
}

/** Milliseconds left on the code currently on screen; 0 when there isn't one. */
function pairCodeLeft() {
  return S.pairCode ? Math.max(0, S.pairCode.exp - Date.now()) : 0;
}

/**
 * Viewer: the id this phone is known to the Monitor by once it has been let in.
 * Per channel, minted here, never derived from anything identifying, and never
 * sent anywhere but the paired Monitor.
 */
function bondId() {
  if (!S.channel) return null;
  let v = null;
  try { v = localStorage.getItem(BOND_KEY()); } catch {}
  if (!HEX32.test(v || '')) {
    const b = new Uint8Array(16);
    crypto.getRandomValues(b);
    v = [...b].map((x) => x.toString(16).padStart(2, '0')).join('');
    try { localStorage.setItem(BOND_KEY(), v); } catch {}
  }
  return v;
}

const knownPeers = () => readJSON(PAIRED_KEY(), []);
function rememberPeer(pid) {
  const list = knownPeers().filter((x) => x !== pid);
  list.push(pid);
  writeJSON(PAIRED_KEY(), list.slice(-MAX_REMEMBERED_PEERS));
}

/**
 * Monitor: is this offer allowed to have the camera?
 *
 * Fails closed on every path — no code on screen, no `pc` in the offer, a `pc`
 * that is not the current one, or a deadline that has passed. Enrolling a new
 * phone is the only branch that writes anything.
 */
function pairingAllowed(m) {
  const pid = typeof m.pid === 'string' && HEX32.test(m.pid) ? m.pid : null;
  if (pid && knownPeers().includes(pid)) return true;
  const code = S.pairCode;
  if (!code || !code.c) return false;
  if (typeof m.pc !== 'string' || m.pc !== code.c) return false;
  if (Date.now() > code.exp) return false;
  if (pid) rememberPeer(pid);
  return true;
}

function pairLink() {
  const base = chosenBase();
  const code = S.pairCode || newPairCode();
  const frag = new URLSearchParams({ k: S.channel.key, n: S.channel.name, r: 'viewer' });
  if (S.token) frag.set('t', S.token);
  frag.set('c', code.c);
  frag.set('e', String(Math.floor(code.exp / 1000)));
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

// A Monitor left sitting on its pairing screen must never be showing a code
// that stopped working while nobody was looking. The sheet counts the code
// down out loud and mints a fresh one the moment it lapses.
let pairTicker = null;
function tickPairSheet() {
  if (!el.pairExpiry || el.pair.hidden) return;
  if (pairCodeLeft() <= 0) {
    newPairCode();
    refreshPair();
    return;
  }
  const s = Math.ceil(pairCodeLeft() / 1000);
  el.pairExpiry.textContent =
    `This code works for another ${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}. `
    + 'A fresh one appears here when it runs out.';
}

function openPair() {
  // The native shell runs its own pairing screen.
  if (S.nativeShell) return;
  renderProfiles();
  if (pairCodeLeft() <= 0) newPairCode();
  refreshPair();
  el.pair.hidden = false;
  tickPairSheet();
  clearInterval(pairTicker);
  pairTicker = setInterval(tickPairSheet, 1000);
}

function closePair() {
  el.pair.hidden = true;
  clearInterval(pairTicker);
  pairTicker = null;
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

$('#pair-close').addEventListener('click', closePair);
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
        el.scanHint.textContent = lastPairError || 'That code is not a Tawny pairing code.';
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
const PAIRCODE_RE = /^[A-Za-z0-9_-]{8,32}$/;

// Why the last adopt() failed, when "that isn't a Tawny link" would be a lie.
// Cleared on every attempt; read by the three callers for their own note.
let lastPairError = null;

// Import a pairing link. Returns true when it was a valid one.
function adopt(raw) {
  lastPairError = null;
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

  // The code's own deadline. Checked here so a code that ran out is refused
  // where the user is looking, in a sentence that tells them what to do —
  // rather than dialling out and failing at the far end. The Monitor checks it
  // again for real; this is only the fast, kind path.
  const exp = Number(p.get('e'));
  if (Number.isFinite(exp) && exp > 0 && Date.now() > exp * 1000) {
    lastPairError = EXPIRED_MESSAGE;
    return false;
  }
  const code = p.get('c');
  S.pairSeen = code && PAIRCODE_RE.test(code) ? code : null;

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

async function playChime(slug) {
  const key = chimeSpec(slug) ? slug : DEFAULT_CHIME;

  // The native Monitor plays the chime itself. From the page's WebAudio a chime
  // comes out on STREAM_MUSIC — which sits muted underneath a call, and cannot
  // be raised with the volume keys while one is running (they move the in-call
  // stream instead). The shell puts it on the call's own audio stream, where it
  // is actually audible and tracks the in-call volume.
  if (androidNative && S.role === 'station') {
    tellNative('chime', { slug: key });
    return;
  }

  const ac = audioCtx();
  // The Monitor's session can start without a tap the WebView ever sees, and
  // Chrome re-parks the context whenever it loses audio focus (dim mode, a trip
  // through the background). Starting a source on a stopped clock makes no
  // sound, so wait for it to actually be running.
  if (ac.state !== 'running') { try { await ac.resume(); } catch {} }

  const buffer = chimeBuffers.get(key);
  if (buffer) playChimeBuffer(ac, buffer, CHIMES[key].gain);
  else {
    // Not cached yet, or known bad. Make a sound *now* from the synth, and warm
    // the cache for next time — never make the user wait on the network.
    synthChime(ac, key);
    if (!chimeBuffers.has(key)) loadChime(key);
  }
  diag(`chime ${key} (${buffer ? 'clip' : 'synth'}, ctx=${ac.state})`);
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

  // The code belongs to *this* connection and to nothing else, so it is parked
  // on the peer. That is the whole fix for the multi-viewer bug: the Monitor
  // used to write every Handheld's code straight into one shared chip, so with
  // two or three phones on the relay the chip showed whichever DTLS handshake
  // finished last, and the user was invited to compare it against a phone whose
  // session it had not come from. A safety code sitting beside a prompt about a
  // different session is worse than no safety code at all.
  peer.sas = code;

  if (S.role === 'station') { syncStationSas(); return; }
  showViewerSas(code);
}

/** Monitor: cloud Handhelds whose code has not been confirmed yet, oldest
 *  first. LAN Handhelds never appear here - the Monitor *is* their relay. Once
 *  the user has vouched for this channel's code a first time, nothing is
 *  pending: the review, like the Handheld's, happens once per monitor. */
function sasPendingViewers() {
  if (sasReviewed()) return [];
  return viewerPeers().filter(
    (p) => p.transport?.tag === 'cloud' && p.sas && !p.sasOk
  );
}

/**
 * Monitor: put the right code in front of the right phone.
 *
 * The code no longer lives in the top rail at all - it was there on every
 * connection, which is exactly the always-on clutter this screen did not need.
 * What remains is the card: it asks about one phone at a time, pinned to that
 * peer until the user answers or the phone goes away, and says how many are
 * queued behind it. Re-picking the newest peer on every call would swap the
 * digits out from under someone halfway through reading them. And once the user
 * has vouched for this channel's code once (sasReviewed), sasPendingViewers()
 * is empty and the card stays down for good.
 */
function syncStationSas() {
  if (S.role !== 'station') return;
  el.saschip.hidden = true;              // the code never sits in the rail now

  if (sasReviewed()) {                   // vouched for once — never ask again
    S.sasAsk = null;
    el.sas.hidden = true;
    el.sas.classList.remove('sas--warn');
    return;
  }

  let ask = S.sasAsk ? S.peers.get(S.sasAsk) : null;
  if (!ask || ask.sasOk || !ask.sas || ask.transport?.tag !== 'cloud') ask = null;
  if (!ask) ask = sasPendingViewers()[0] || null;
  S.sasAsk = ask ? ask.id : null;

  if (!ask) {
    el.sas.hidden = true;
    el.sas.classList.remove('sas--warn');
    return;
  }
  const behind = sasPendingViewers().length - 1;
  el.sascode.textContent = ask.sas;
  el.sasnote.textContent =
    'A phone is connecting from outside your Wi-Fi. It should be showing this code'
    + (behind > 0 ? ` (${behind} more phone${behind > 1 ? 's' : ''} after it):` : ':');
  if (el.sasok) el.sasok.textContent = 'Looks right';
  if (el.sasno) el.sasno.textContent = 'Disconnect it';
  el.sas.classList.remove('sas--warn');
  el.sas.hidden = false;
}

// Handheld: has this channel's safety code already been reviewed once?
//
// WebRTC mints a fresh DTLS certificate for every RTCPeerConnection - measured
// in this app's own WebView: three connections in one page, three different
// fingerprints - so the code is different on the next call *by construction*
// and there is nothing to re-compare it against. The old scheme re-prompted on
// every reconnect; after the first session that fired every single time and
// only ever taught people to tap it away. So the review happens once per
// channel: confirm it the first time the Handheld connects over the internet,
// and never again. A code that could not be computed at all is still shown -
// that is an alarm, not a review.
const sasReviewKey = () => `tawny.sasok.${S.channel?.id}`;
function sasReviewed() {
  try { return !!localStorage.getItem(sasReviewKey()); } catch { return false; }
}
function markSasReviewed(code) {
  try { localStorage.setItem(sasReviewKey(), code || '1'); } catch {}
}

function showViewerSas(code) {
  // Retire the old per-channel store rather than leave a stale code behind it.
  try { localStorage.removeItem(`tawny.sas.${S.channel?.id}`); } catch {}

  // Already reviewed once for this channel, and the code came through fine:
  // say nothing at all - no card, no chip.
  if (code && sasReviewed()) {
    el.sas.hidden = true;
    el.sas.classList.remove('sas--warn');
    el.saschip.hidden = true;
    return;
  }

  el.saschip.hidden = true;      // the code lives on the card, never in the rail

  el.sascode.textContent = code || 'unavailable — connection may be tampered with';
  el.sas.classList.toggle('sas--warn', !code);
  if (el.sasnote) {
    el.sasnote.textContent = code
      ? 'Check the Monitor is showing this same code. You are only asked this '
        + 'once for this monitor.'
      : 'The safety code for this connection could not be worked out. If you did '
        + 'not expect that, disconnect.';
  }
  if (el.sasok) el.sasok.textContent = 'Looks right';
  if (el.sasno) el.sasno.textContent = 'Disconnect';
  el.sas.hidden = false;
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
  // `retry` counts failures to *reach* the relay and is reset the moment a
  // socket opens. `refused` counts admissions the relay turned down, which
  // happen after the socket is open — so it needs its own counter, cleared only
  // by an actual welcome, or a Monitor bounced by a ghost would re-dial forever.
  const entry = { tag, ws: null, retry: 0, refused: 0, dead: false };
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
      if (m.type === 'welcome') {
        entry.refused = 0;
        diag(`${tag} welcome id=${m.id} peers=${(m.peers || []).length}`);
      }
      else if (m.type === 'peer-joined') diag(`${tag} peer-joined ${m.role || '?'} ${m.id}`);
      else if (m.type === 'peer-left') diag(`${tag} peer-left ${m.id}`);
      handle(m, entry).catch(console.error);
    };
    ws.onclose = (ev) => {
      diag(`${tag} close ${ev.code}${ev.reason ? ' "' + ev.reason + '"' : ''} retry=${entry.retry}`);
      if (entry.dead || S.closing) return;

      // The relay handed this room to a device that proved the channel key —
      // the same pairing, on another phone. Terminal, but not a fault, and not
      // something to retry into: two Monitors racing for one room is exactly
      // what the code prevents.
      if (ev.code === 4005) {
        diag(`${tag} replaced by another monitor on this channel`);
        closeSignal(entry);
        if (S.role === 'station') toast('This monitor was taken over by another device.');
        return;
      }

      // Fatal close codes: for a Handheld (one transport) the session is over;
      // for the Watcher, a bad cloud socket must NOT tear down a healthy LAN one.
      if (ev.code === 4003 || ev.code === 4004 || ev.code === 4008) {
        if (S.role === 'station') {
          // ...but not immediately fatal on the cloud leg. When this phone's
          // radio blips, the socket dies without a close handshake and the
          // relay cannot tell the corpse from a live Monitor for minutes: this
          // device re-hosting its own room was told 4004 "monitor already
          // running" by its own ghost, and gave up for good. The ghost clears
          // on its own, and against a relay that has the owner-reclaim it
          // clears on the first retry — so back off and try again first.
          const retryable = tag === 'cloud' && ev.code !== 4003 &&
            entry.refused < STATION_CLOUD_RETRIES;
          if (retryable) {
            if (entry.refused === 0) toast('Reconnecting…');
            const wait = Math.min(1000 * 2 ** entry.refused++, 15000);
            diag(`${tag} refused ${ev.code} — retry ${entry.refused}/${STATION_CLOUD_RETRIES} in ${wait}ms`);
            updateStatus();
            setTimeout(dial, wait);
            return;
          }
          // A dead cloud leg used to be completely silent here: the Monitor sat
          // on its healthy LAN socket showing "Waiting", while every Handheld
          // off the Wi-Fi was being turned away at the relay.
          diag(`${tag} FATAL ${ev.code} — monitor is NOT reachable over the internet`);
          closeSignal(entry);
          if (ev.code === 4004) toast('Another phone is now using this monitor.');
          if (ev.code === 4008 && tag === 'cloud') {
            toast('This monitor can only be watched from your Wi-Fi right now. Phones somewhere else will not be able to connect.');
          }
          return;
        }
        return bail(
          ev.code === 4003 ? FULL_MESSAGE
          : ev.code === 4004 ? 'This monitor is already running on another phone.'
          // 4008 is the relay's own refusal — a ticket that no longer matches
          // the room. Different cause from the Monitor's pairing gate, same
          // thing to do about it, so it gets the same sentence and the same
          // screen in the native shell.
          : EXPIRED_MESSAGE,
          ev.code === 4003 ? 'full' : ev.code === 4008 ? 'expired' : undefined
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
        return bail('This code only works on the same Wi-Fi as the other phone. Put both phones on the same Wi-Fi and try again.');
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
    if (!S.signals.length) return bail('This phone is not on Wi-Fi yet. Connect it to Wi-Fi and try again.');
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
    bail('This code only works on the same Wi-Fi as the other phone. Put both phones on the same Wi-Fi and try again.');
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

/**
 * Turn a phone away because the Monitor already has its three.
 *
 * Addressed at the socket, never at a peer record: nothing is created for the
 * refused id, no camera or microphone track is ever attached to it, and the
 * three live connections are not renegotiated or even looked at. `bye` is on
 * every relay's forward list, so this reaches the phone over LAN and cloud
 * alike without the relay needing to know what it means.
 */
function refuseAsFull(id, entry) {
  sig({ type: 'bye', to: id, reason: 'full', max: MAX_VIEWERS }, { transport: entry });
}

const stationPeer = () => [...S.peers.values()].find((p) => p.role === 'station');
const viewerPeers = () => [...S.peers.values()].filter((p) => p.role === 'viewer');
const viewerCount = () => viewerPeers().length;

function ensurePeer(id, role, transport) {
  let p = S.peers.get(id);
  if (!p) {
    p = { id, role, transport: transport || null, pc: null, stream: null,
          iceKick: null, talking: false, audioEl: null, pendingIce: [], label: null,
          // This connection's safety code, and whether the Monitor's user has
          // said it matches. Both live and die with the peer: a reconnect is a
          // fresh DTLS handshake with a fresh code, so it is asked about again.
          sas: null, sasOk: false };
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
    // Same for the battery reading — drop it rather than leave a stale percent
    // hanging over a monitor that is no longer connected.
    S.remoteBattery = { level: null, charging: null };
    updateBatteryUI();
  }
  // Monitor: the last Viewer left. Nobody is looking, so nothing justifies
  // holding an LED on in an empty room.
  if (S.role === 'station' && S.torchOn && !viewerCount()) {
    setTorch(false, 'no viewers left');
  }
  retuneAll();
  updatePeerChip();
  updateStatus();
  // A phone leaving is what retires its safety-code card and lets the next one
  // in the queue have the screen.
  syncStationSas();
}

/**
 * Monitor: end one Handheld's session and leave the others alone.
 *
 * Used when the user says a safety code does not match. Hanging up on all three
 * would be a heavier hammer than the situation earns - the other two phones are
 * watching a pet, and a monitor that goes dark for everyone is its own failure.
 * The refused phone may reconnect; that is a new handshake with a new code, and
 * it gets asked about again.
 */
function dropViewer(id) {
  const p = S.peers.get(id);
  if (!p) return;
  sig({ type: 'bye', to: id }, p);
  removePeer(id);
  diag(`viewer ${id} dropped - safety code rejected`);
}

function teardownAll() {
  for (const id of [...S.peers.keys()]) removePeer(id);
  clearTimeout(S.iceTimer);      // the TURN refresh outlived the session and
  S.iceTimer = null;             // re-fetched against a stale room id
  stopMeter();
  el.talkflag.hidden = true;
  el.sas.hidden = true;
  el.saschip.hidden = true;
  S.sasAsk = null;
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
  // The count lives in the rail's phone glyph now, not in words here.
  status(live ? 'On air' : 'Connecting', live ? 'live' : 'on');
}

function updatePeerChip() {
  if (!el.peercount) return;
  if (S.role !== 'station') {
    el.peercount.hidden = true;
    if (el.peerlabel) el.peerlabel.hidden = true;
    return;
  }
  const n = viewerCount();
  const full = n >= MAX_VIEWERS;
  el.peercount.hidden = n === 0;
  if (el.peercountN) el.peercountN.textContent = String(n);
  el.peercount.setAttribute('aria-label',
    `${n} phone${n === 1 ? '' : 's'} watching${full ? ', full' : ''}`);
  el.peercount.classList.toggle('is-busy', full);

  // Exactly one phone watching: name it. More than one, or none: no room for a
  // name, so just the count. The safety code is gone from here entirely - it is
  // the first-connection card and nothing else.
  if (el.peerlabel) {
    const only = n === 1 ? viewerPeers()[0] : null;
    el.peerlabel.hidden = !(only && only.label);
    if (only && only.label) el.peerlabel.textContent = only.label;
  }
  bumpRail();
  // Tell the native shell how many are watching and whether there is still room
  // for another. This used to fire once, on the very first Viewer, and only
  // ever said "watching" — so the shell tore down the way back to the pairing
  // code the moment phone #1 arrived and never learned that phones #2 and #3
  // were still welcome. Send it on every change of *count*, not just of state.
  if (n !== S._peerN) {
    S._peerN = n;
    tellNative(n > 0 ? 'watching' : 'waiting', { n, max: MAX_VIEWERS, full });
  }
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
      // Full. Turn the newcomer away *out loud* — this used to be a bare
      // `return`, so a fourth phone that the relay had let into the room sat on
      // "Connecting" until it timed out with no idea it had been refused. The
      // three already watching are not touched.
      if (S.role === 'station' && viewerCount() >= MAX_VIEWERS) {
        diag(`peer-joined refused: ${MAX_VIEWERS} viewers already`);
        refuseAsFull(m.id, entry);
        return;
      }
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
        // Browser Monitor: the pairing sheet closes only once the third phone
        // is on. Closing it at the first one is what made "add another phone"
        // feel like it had been taken away.
        if (viewerCount() >= MAX_VIEWERS) closePair();
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
        refuseAsFull(m.from, entry);
        return;
      }
      // The pairing gate. This is the only place a phone the Monitor has never
      // seen becomes one it has, and it sits above ensurePeer/answerPeer —
      // answerPeer attaches the live camera and microphone, so nothing may
      // reach it on a code that has run out. Already-known phones (a `pid` the
      // Monitor recorded when it first let them in) sail past.
      if (S.role === 'station' && !S.peers.has(m.from) && !pairingAllowed(m)) {
        diag(`offer refused: pairing code expired or never seen (${m.from})`);
        sig({ type: 'bye', to: m.from, reason: 'expired' }, { transport: entry });
        return;
      }
      const p = ensurePeer(m.from, S.role === 'station' ? 'viewer' : 'station', entry);
      // The Handheld names its own device in the offer, for the Monitor's rail.
      // Peer-controlled text: printable ASCII only, kept short, only ever shown.
      if (S.role === 'station' && typeof m.label === 'string') {
        const clean = m.label.replace(/[^\x20-\x7E]+/g, '').trim().slice(0, 20);
        if (clean) p.label = clean;
      }
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
      if (now - (handle._chimeAt || 0) > 300) { handle._chimeAt = now; playChime(m.sound); }
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
      // We asked for light and the Monitor came back dark: it tried and the
      // camera would not do it. Say why once, here, rather than leaving a key
      // that springs back with no explanation.
      if (S.torchAsk && !S.torchOn) toast(torchHint());
      S.torchAsk = false;
      break;
    }
    case 'battery': {
      // Viewer: the Monitor is authoritative. Store and render, nothing else.
      if (S.role !== 'viewer') break;
      const lv = Number(m.level);
      S.remoteBattery = {
        level: Number.isFinite(lv) ? Math.max(0, Math.min(100, Math.round(lv))) : null,
        charging: typeof m.charging === 'boolean' ? m.charging : null,
      };
      diag(`battery <- ${S.remoteBattery.level}% charging=${S.remoteBattery.charging}`);
      updateBatteryUI();
      break;
    }
    case 'bye':
      // A Monitor at its cap says goodbye with a reason. Anything else is an
      // ordinary hang-up and must stay one — a plain `bye` ends the call, it
      // does not accuse the Monitor of being full.
      if (m.reason === 'full' && S.role === 'viewer') return bail(FULL_MESSAGE, 'full');
      // Refused at the pairing gate: the code this phone arrived with is no
      // longer the one the Monitor is showing. Say that, not "call ended".
      if (m.reason === 'expired' && S.role === 'viewer') return bail(EXPIRED_MESSAGE, 'expired');
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
        status('Still trying', 'warn');
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
  const msg = { type: 'offer', to: peer.id, sdp: peer.pc.localDescription.toJSON() };
  // The Handheld tags its offer with a short device name for the Monitor's rail.
  if (S.role === 'viewer') {
    msg.label = S.deviceLabel || shortDeviceLabel('');
    // …and with what gets it past the pairing gate: the id this phone is known
    // by once the Monitor has let it in, plus — the first time, or after the
    // Monitor forgot it — the code the user actually scanned.
    const pid = bondId();
    if (pid) msg.pid = pid;
    if (S.pairSeen) msg.pc = S.pairSeen;
  }
  sig(msg, peer);
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
    S.torchSupported = await probeTorch();
    sig({ ...torchState(), to: peer.id }, peer);
  }
  // Current battery, so a phone that joins (or rejoins) mid-session is not
  // stuck on a stale reading until the next 1% step.
  if (S.role === 'station' && S.battery.level != null) {
    sig({ ...batteryState(), to: peer.id }, peer);
  }
}

// Per-Handheld uplink budget. One encode per viewer on a mid-range phone, so the
// ceiling drops as more phones connect: 1→1.5M, 2→1.0M, 3→750k. Three is the
// cap, so 750k each — 2.2 Mbps of encode — is the worst case this has to hold.
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
    // At the cap there are three simultaneous encodes off one capture; give the
    // framerate up rather than the picture, which is what a pet monitor is for.
    p.encodings[0].maxFramerate = Math.min(viewerCount() >= 3 ? 20 : 24, cap?.maxFramerate ?? Infinity);
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
// emulator has no LED at all. So the Monitor is the only thing that decides,
// and broadcasts an authoritative {on, supported, facing} to every Viewer. A
// Viewer never guesses, never assumes, and renders only what it was told.
//
// What the Monitor must NOT do is decide by asking `getCapabilities().torch`.
// That question lies in both directions on Android/Chromium: a lamp-less
// tablet answers `true`, and — the bug that sent us here — a Samsung A50 with
// a perfectly good rear LED answers with no `torch` key at all, on a camera
// whose light `applyConstraints` drives fine. The capability set can also
// still be half-built until the pipeline has pushed frames. The constraint is
// the arbiter, not the advertisement: we ask the camera to *do* something and
// believe the answer.

const localVideoTrack = () => S.local?.getVideoTracks?.()[0] || null;

// The verdicts, kept on the track itself so a lens switch or a recovered
// capture gives that camera its own fair try, and so nothing has to be reset
// by hand.
//   torchProven — drove its light, whatever getCapabilities() had to say.
//   torchDud    — refused an attempt to *light* it. A hard no: we stop asking.
//   torchUnsure — refused the silent probe. Enough to tell a Viewer the key
//                 is unlikely to do anything, and NOT enough to refuse the
//                 press: the probe is an off-write, and if some driver
//                 answered it differently from the on-write that actually
//                 matters, the phone with the working LED must still win. One
//                 real attempt settles it either way.
const torchDud = new WeakSet();
const torchProven = new WeakSet();
const torchUnsure = new WeakSet();

/**
 * The cheap read, for the places that cannot wait: only ever true once a track
 * has been proven, or while it is still claiming a torch it hasn't been asked
 * for yet. Never the last word — probeTorch() is.
 */
function torchCapable() {
  const t = localVideoTrack();
  if (!t || t.readyState !== 'live' || torchDud.has(t)) return false;
  if (torchProven.has(t)) return true;
  if (S.facing === 'user') return false;
  try { return t.getCapabilities?.().torch === true; } catch { return false; }
}

/**
 * Find out for real whether the live track can drive its light, and remember.
 *
 * A declared `torch: true` is taken at face value (a camera that claims one
 * and then refuses it is caught later, by pushTorch). Everything else is
 * settled by *writing* `torch: false` at the track: on a camera with a lamp
 * that is a no-op — it is already off, nothing flashes, the user sees nothing
 * — and on one without, Chromium rejects it. Resolve means the knob is there.
 *
 * Like every torch write this replaces the track's constraint set rather than
 * adding to it (see pushTorch); it is a once-per-track cost and the capture
 * keeps the format it is already running at.
 */
async function probeTorch() {
  const t = localVideoTrack();
  if (!t || t.readyState !== 'live') return false;
  if (torchProven.has(t)) return true;
  if (torchDud.has(t) || torchUnsure.has(t)) return false;   // asked once, that's enough
  // The front lens is the one case we settle without asking: no phone we have
  // met has a lamp beside the selfie camera, and the Viewer's hint already
  // says to flip the Monitor round.
  if (S.facing === 'user') return false;
  try {
    if (t.getCapabilities?.().torch === true) { torchProven.add(t); return true; }
  } catch {}
  try {
    await t.applyConstraints({ advanced: [{ torch: false }] });
    torchProven.add(t);
    diag('torch: light present (capability unreported, probe accepted)');
    return true;
  } catch (e) {
    torchUnsure.add(t);
    diag(`torch: this camera has no light (probe refused, ${e && e.name})`);
    return false;
  }
}

const torchState = () => ({
  type: 'torch', on: S.torchOn, supported: S.torchSupported, facing: S.facing
});

function broadcastTorch() {
  if (S.role !== 'station') return;
  for (const p of viewerPeers()) sig({ ...torchState(), to: p.id }, p);
}

// ---- monitor battery, mirrored to every Handheld ---------------------------
// The Handheld should always show the Monitor's real charge and whether it is
// on power. The native shell is authoritative — it reads ACTION_BATTERY_CHANGED,
// which is exact and fires on every 1% step and every plug/unplug — and calls
// window.tawnyBattery(). A browser Monitor falls back to the Battery Status API
// where the engine has it. Either way the Viewer renders only what arrived.

const batteryState = () => ({
  type: 'battery', level: S.battery.level, charging: S.battery.charging,
});

function broadcastBattery() {
  if (S.role !== 'station' || S.battery.level == null) return;
  for (const p of viewerPeers()) sig({ ...batteryState(), to: p.id }, p);
}

// One entry point for both sources. Rebroadcasts only on an actual change, so a
// repeated OS broadcast at the same percent costs nothing.
function setStationBattery(level, charging) {
  if (S.role !== 'station') return;
  const lv = level == null || !Number.isFinite(level)
    ? null : Math.max(0, Math.min(100, Math.round(level)));
  const ch = typeof charging === 'boolean' ? charging : null;
  if (lv === S.battery.level && ch === S.battery.charging) return;
  S.battery = { level: lv, charging: ch };
  diag(`battery -> ${lv}% charging=${ch} (${viewerPeers().length} viewer(s))`);
  broadcastBattery();
}

// Native shell → here, on every OS battery broadcast while a Monitor is live.
window.tawnyBattery = (level, charging) =>
  setStationBattery(Number(level), !!charging);

// Browser Monitor fallback. No-op in the native shell (which drives the call
// above) and on engines without the API — the Handheld simply shows no chip.
// getBattery() hands back the same object every call, so the listeners are
// attached once for the life of the page.
let browserBatteryBound = false;
async function watchBrowserBattery() {
  if (S.role !== 'station' || S.nativeShell || !navigator.getBattery) return;
  let b;
  try { b = await navigator.getBattery(); } catch { return; }
  const push = () => setStationBattery(b.level * 100, b.charging);
  push();
  if (browserBatteryBound) return;
  browserBatteryBound = true;
  b.addEventListener('levelchange', push);
  b.addEventListener('chargingchange', push);
}

// Viewer: paint whatever the Monitor last sent. Green + a bolt when it is on
// power; amber when it is running low on its own.
function updateBatteryUI() {
  if (!el.battchip) return;
  const { level, charging } = S.remoteBattery;
  if (S.role !== 'viewer' || level == null) { el.battchip.hidden = true; return; }
  el.battchip.hidden = false;
  if (el.battPct) el.battPct.textContent = level + '%';
  if (el.battFill) el.battFill.setAttribute('width', (20 * level / 100).toFixed(1));
  el.battchip.classList.toggle('is-charging', charging === true);
  el.battchip.classList.toggle('is-low', charging !== true && level <= 15);
  el.battchip.setAttribute('aria-label',
    `Monitor battery ${level}%${charging === true ? ', charging' : ''}`);
  bumpRail();
}

/**
 * Settle the question on the live track and tell everyone watching. Called
 * whenever the track underneath us changes — session start, lens switch,
 * camera flip, recovered capture — so a Viewer's key is right *before* the
 * first tap rather than after a failed one.
 */
async function refreshTorchSupport() {
  if (S.role !== 'station') return;
  S.torchSupported = await probeTorch();
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
  catch (e) {
    // An *off* push that fails on a camera we never managed to light is not a
    // fault: there was no light to put out. The lamp-less tablet answers the
    // teardown push with UnknownError and logged it as a failure at the end of
    // every single session. Believe the refusal instead of the capability, and
    // say so quietly. A failed *off* on a light that IS burning stays loud.
    if (!on && !S.torchOn) {
      torchDud.add(t);
      S.torchSupported = false;
      diag('torch: this camera reports a light it does not have');
      return false;
    }
    diag(`torch apply failed: ${e && e.name}`);
    return false;
  }
}

async function setTorch(on, why) {
  if (S.role !== 'station') return;
  on = !!on;
  clearTimeout(S.torchTimer);
  S.torchTimer = null;

  const t = localVideoTrack();
  const live = !!t && t.readyState === 'live';
  // The only two refusals worth making before trying: there is no camera left
  // to light, or it is the front one. Anything else rear-facing gets the push
  // — a camera that stays dark costs the Viewer one "nothing happened, here's
  // why", which is cheaper than a key we wrongly greyed out forever.
  if (on && (!live || torchDud.has(t) || S.facing === 'user')) {
    S.torchSupported = false;
    S.torchOn = false;
    diag(`torch refused (${!live ? 'no live camera'
      : S.facing === 'user' ? 'front camera' : 'camera has no light'})`);
    broadcastTorch();
    return;
  }
  // Turning it *off* is still attempted when the capability has already gone
  // with the track — a stale "on" must never be the last thing a Viewer was
  // told, and a failed off must never latch. But on a phone whose camera has
  // no light and that we never lit, there is physically nothing to put out;
  // pushing anyway just earns an OverconstrainedError in the diagnostics on
  // every single session end, which is noise that hides real faults.
  const worthPushing = on || S.torchOn || S.torchSupported;
  const ok = worthPushing ? await pushTorch(on) : false;
  // The attempt is what decides, not what the camera advertised: a torch that
  // lit is a torch, and one that threw on the way on is this track's dud.
  if (on) {
    if (ok) torchProven.add(t); else torchDud.add(t);
    S.torchSupported = ok;
  }
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
      video: { deviceId: { exact: cam.deviceId }, ...idealCaptureSize(1280, 720) },
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
    await refreshTorchSupport();
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

/**
 * Give up on this session and say why.
 *
 * `reason` is a machine-readable tag for the native shell, which otherwise has
 * to guess from the role what went wrong — and guessed "Monitor isn't on yet"
 * for a Handheld turned away from a monitor that was very much on, just full.
 */
function bail(msg, reason) {
  diag(`bail: ${msg}`);
  S.closing = true;
  closeAllSignals();
  S.local?.getTracks().forEach((t) => t.stop());
  teardownAll();
  if (S.nativeShell) {
    // The native shell owns navigation and error UI.
    tellNative('error', { message: msg, ...(reason ? { reason } : {}) });
    S.closing = false;
    return;
  }
  show(S.channel ? el.role : el.channels);
  note(el.roleNote, msg);
  S.closing = false;
}

// The capture's long axis follows how the phone is being held: a Monitor on
// its side sends a genuine wide frame, one upright sends a tall one, and the
// pixel budget (~540p, steady on a mid-range phone where 720p drops frames) is
// the same either way. Aspect-driven only — no fixed W×H that would force one
// shape onto every device.
function screenIsWide() {
  return (window.screen?.orientation?.type || '').startsWith('landscape')
    || window.innerWidth >= window.innerHeight;
}
function idealCaptureSize(long = 960, short = 540) {
  return screenIsWide()
    ? { width: { ideal: long }, height: { ideal: short } }
    : { width: { ideal: short }, height: { ideal: long } };
}

function cameraConstraints() {
  return {
    facingMode: { ideal: S.facing },
    ...idealCaptureSize(),
    frameRate: { ideal: 24, max: 30 }
  };
}

// The full-frame video (#remote, and the Monitor's own #local.fill) is always
// shown whole — `object-fit: contain` in the stylesheet, in every orientation.
// It is never filled-and-cropped: turning the phone to landscape is exactly
// when you want to see the *whole* room, not have its top cut off to spare a
// bar. Matching the capture shape to how the phone is held (idealCaptureSize)
// is what keeps those bars small — or gone, when both ends face the same way.

async function start(role) {
  if (!S.channel) return;
  S.role = role;
  S.pending = null;
  // A Monitor is never without a live pairing code: pairingAllowed() fails
  // closed on a missing one, so a station that reached here without a code
  // from the shell would refuse every phone rather than let one in.
  if (role === 'station' && pairCodeLeft() <= 0) newPairCode();
  S.roomId = await roomIdFor(S.channel.key);
  diag(`start role=${role} room=${S.roomId} lan=${S.signalUrl || '-'} ` +
    `rv=${rendezvousBase() || 'NONE'} ticket=${S.token ? 'yes' : 'MISSING'}`);
  fetchIce();   // non-blocking; new PCs pick up S.ice when it lands

  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    return bail('Tawny could not get to the camera and microphone on this phone. ' +
      'Close Tawny and open it again.');
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
  S.torchAsk = false;
  clearTimeout(S.torchTimer);
  S.torchTimer = null;
  watchLocalTracks();

  audioCtx(); // the tap that got us here also unlocks chime playback
  // Camera enumeration only works after getUserMedia grants permission (labels are blank before).
  if (role === 'station') {
    enumerateCameras();
    // The Monitor is the end that plays chimes. Decode them now, while it is
    // idle and unlocked, so an arriving chime is instant instead of a fetch
    // behind the Viewer's press. The native shell plays chimes itself, so it
    // does not need the WebAudio clips at all.
    if (!androidNative) preloadChimes();
  }

  el.remote.srcObject = null;
  el.remoteAudio.srcObject = null;
  el.remote.hidden = true;
  el.loader.hidden = role === 'station';
  el.sas.hidden = true;
  el.saschip.hidden = true;
  S.sasAsk = null;
  el.live.dataset.role = role;        // CSS trims the rail differently per role
  S.battery = { level: null, charging: null };
  S.remoteBattery = { level: null, charging: null };
  updateBatteryUI();

  if (role === 'station') {
    el.local.srcObject = S.local;
    el.local.classList.add('fill');   // the Watcher sees its own camera, full frame
    el.local.hidden = false;
    el.cStation.hidden = false;
    el.cViewer.hidden = true;
    startMeter(S.local);              // dim screen shows the pet's room level
    updatePeerChip();
    keepAwake();
    watchBrowserBattery();            // native shell drives window.tawnyBattery instead
    lastCaptureWide = screenIsWide(); // getUserMedia above already matched this
  } else {
    for (const t of S.local.getAudioTracks()) t.enabled = false; // push-to-talk
    el.cViewer.hidden = false;
    el.cStation.hidden = true;
    updateTorchUI();
  }

  // Rail label: just the room name the user gave. The Handheld keeps it (you may
  // have several monitors); on the Monitor's own screen CSS hides it - you named
  // the pet, you know which room you are in.
  const room = (S.channel.name || 'Pet camera').trim().replace(/\s*monitor$/i, '');
  el.channel.textContent = room || 'Pet camera';

  show(el.live);
  status('Connecting', null);
  tellNative('live', { role });
  connectAll();

  // Settle the light question now, on the track we have just opened, so the
  // first Viewer to join is told the truth about this camera before it can
  // press anything. It is one constraint write and nothing lights up.
  if (role === 'station') { refreshTorchSupport(); openPair(); }
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
    await refreshTorchSupport();
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
  // Note what is NOT here: a refusal of our own. The Monitor is the only end
  // that knows, and the only end holding the camera — so even a key we have
  // drawn as unavailable still asks, and the Monitor either lights up or says
  // why. A Viewer that refuses on its own behalf is how a working LED on the
  // other side of the house stays dark forever.
  const want = !S.torchOn;
  S.torchAsk = want;
  sig({ type: 'torch', to: sp.id, on: want }, sp);
  // The press state moves now so the key feels connected to the thumb — but
  // only when we have been told there is a light; otherwise it would blink on
  // and straight back off. The Monitor's reply is what actually sets it.
  if (S.torchSupported) press($('#btn-torch'), want);
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
      ...idealCaptureSize(),
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
  await refreshTorchSupport();
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

// One card, two roles. On the Monitor it is asking about one named peer; on a
// Handheld it is about the one connection this phone has. Either way, "Looks
// right" is also the once-per-channel acknowledgement - after it, this monitor
// is not asked to compare codes again.
$('#sas-ok')?.addEventListener('click', () => {
  if (S.role === 'station') {
    const p = S.sasAsk ? S.peers.get(S.sasAsk) : null;
    if (p) { p.sasOk = true; if (p.sas) markSasReviewed(p.sas); }
    S.sasAsk = null;
    syncStationSas();       // card goes away; the code no longer sits in the rail
    return;
  }
  // Handheld: a real code the user confirmed - remember it so this channel
  // never shows the review again. A warn state has no code worth remembering.
  if (!el.sas.classList.contains('sas--warn')) markSasReviewed(el.sascode.textContent);
  el.sas.hidden = true;
  el.saschip.hidden = true;
});
$('#sas-no')?.addEventListener('click', () => {
  if (S.role === 'station') {
    const id = S.sasAsk;
    S.sasAsk = null;
    if (id) dropViewer(id);   // this calls back into syncStationSas()
    else syncStationSas();
    return;
  }
  hangUp();
});

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
  // The shell draws the QR, so the shell owns the pairing code. On a Monitor
  // that is the code on screen plus the deadline it is counting down; on a
  // Handheld it is the code the user scanned, presented once to get in.
  const pc = opts && typeof opts.pairCode === 'string' ? opts.pairCode : null;
  if (pc && PAIRCODE_RE.test(pc)) {
    if (role === 'station') S.pairCode = { c: pc, exp: Number(opts.pairExp) || (Date.now() + PAIR_TTL_MS) };
    else S.pairSeen = pc;
  }
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

/**
 * The shell rotated the pairing code on its overlay — take the new one as the
 * only code that now admits a phone. Called from the countdown in
 * MainActivity's pairing sheet; a no-op anywhere else.
 */
window.tawnyPairCode = function (code, expMs) {
  if (S.role !== 'station' || typeof code !== 'string' || !PAIRCODE_RE.test(code)) return false;
  S.pairCode = { c: code, exp: Number(expMs) || (Date.now() + PAIR_TTL_MS) };
  diag(`pairing code rotated — ${Math.round(pairCodeLeft() / 1000)}s left`);
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
    else note(el.chNote, lastPairError || 'That pairing link was not valid.');
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

// ----------------------------------------------------------- orientation

// Station: when the Monitor is turned, ask the camera for a frame shaped to the
// new orientation, so a phone laid on its side sends a genuinely wide picture
// (and an upright one a tall picture) — not a fixed shape with the room rotated
// or letterboxed into it. Debounced, only when the orientation actually flips,
// and a no-op off the Monitor or before a track exists.
let reshapeTimer = null;
let lastCaptureWide = null;
function reshapeCapture() {
  if (S.role !== 'station' || !S.local) return;
  const wide = screenIsWide();
  if (wide === lastCaptureWide) return;
  lastCaptureWide = wide;
  clearTimeout(reshapeTimer);
  reshapeTimer = setTimeout(async () => {
    const c = S.dimmed
      ? { ...cameraConstraints(), frameRate: { ideal: POWER.captureFps, max: POWER.captureFps } }
      : cameraConstraints();
    for (const t of S.local.getVideoTracks()) {
      try { await t.applyConstraints(c); } catch {}
    }
    for (const peer of S.peers.values()) tuneVideoSender(peer);
  }, 350);
}
for (const ev of ['resize', 'orientationchange']) {
  window.addEventListener(ev, reshapeCapture);
}
