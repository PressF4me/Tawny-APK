// Tawny — WebRTC signaling + static file server.
// Media is peer-to-peer. This process brokers the handshake and nothing else.
//
// Channels are identified to the server only by an opaque id derived client
// side from a secret the server never receives. See SECURITY.md.

import http from 'node:http';
import { readFile } from 'node:fs/promises';
import { join, extname, normalize, sep } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID, createHash, createHmac } from 'node:crypto';
import { WebSocketServer } from 'ws';
import { PRIVACY_HTML, PRIVACY_HEADERS } from './rendezvous/privacy.js';

const PORT = Number(process.env.PORT || 8099);
const HOST = process.env.HOST || '0.0.0.0';
const STUN = list(process.env.STUN_URLS);
const ALLOWED_HOSTS = list(process.env.ALLOWED_HOSTS).map((h) => h.toLowerCase());
const TRUST_PROXY = process.env.TRUST_PROXY !== 'off';

// Remote relay (all optional). RENDEZVOUS_URL is only echoed for a browser
// client that fetches /config.json from this origin. TURN is coturn with
// use-auth-secret (static-auth-secret === TAWNY_TURN_SECRET).
const RENDEZVOUS_URL = process.env.RENDEZVOUS_URL || '';
const TURN_MODE = process.env.TURN_MODE || 'auto';
const TURN_URLS = list(process.env.TAWNY_TURN_URLS);
const TURN_SECRET = process.env.TAWNY_TURN_SECRET || '';

const MAX_PER_ROOM = 4;      // one Watcher + up to three Handhelds
const MAX_STATIONS = 1;
const MAX_PER_IP = 6;
const MAX_TOTAL = 64;
const MAX_MSG = 64 * 1024;   // enforced by the ws maxPayload below, too
const AUTH_FAILS = 8;        // per IP before lockout
const AUTH_WINDOW = 10 * 60_000;
// Distinct room ids this process will track at once. LocalWeb.kt has always had
// this cap; here the room map could grow without bound (see the empty-room leak
// fixed in admit()), so an unauthenticated caller could walk it up until the
// process died.
const MAX_ROOMS = 256;
// A socket that opens and never sends {type:'hello'} used to sit there forever.
// The 30 s heartbeat only reaps sockets that stop answering pings, so a client
// that pongs politely and never speaks held a slot indefinitely — eleven hosts
// at MAX_PER_IP would wedge MAX_TOTAL and take the whole relay down. The LAN
// relay sweeps these after 5 s and the Durable Object after 10 s; this had no
// sweep at all.
const ADMIT_TIMEOUT_MS = 10_000;

const ROOM_RE = /^[a-f0-9]{32}$/;
const PUBLIC = join(fileURLToPath(new URL('.', import.meta.url)), 'public');

function list(v) {
  return (v || '').split(',').map((s) => s.trim()).filter(Boolean);
}

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ogg': 'audio/ogg',
  '.oga': 'audio/ogg',
  '.mp3': 'audio/mpeg',
  '.ico': 'image/x-icon',
  '.webmanifest': 'application/manifest+json; charset=utf-8'
};

/**
 * `connect-src` for the page this server hands out.
 *
 * It used to be `'self' https: wss: ws:`, which is three scheme-wide sources —
 * i.e. no restriction at all. Any script that got into the page could POST the
 * channel key out of localStorage to any host on the internet, which is the one
 * thing a CSP on this page exists to prevent.
 *
 * Same-origin covers the signaling socket, because `'self'` matches ws/wss on
 * the document's own host and port. Beyond that the page dials exactly one
 * other host: the rendezvous named in RENDEZVOUS_URL, over wss for signaling
 * and https for /turn and /config.json. If none is configured, it dials nothing
 * else and the policy says so.
 */
const CONNECT_SRC_TOKEN = '__TAWNY_CONNECT_SRC__';
const CONNECT_SRC = (() => {
  const out = ["'self'"];
  const host = RENDEZVOUS_URL
    ? RENDEZVOUS_URL.replace(/^wss?:\/\//, '').replace(/^https?:\/\//, '')
        .split('/')[0].split('?')[0]
    : '';
  // A host with whitespace or a semicolon would truncate the policy. If it does
  // not look like a hostname[:port], it does not go in.
  if (/^[A-Za-z0-9.-]+(:\d{1,5})?$/.test(host)) out.push(`wss://${host}`, `https://${host}`);
  return out.join(' ');
})();

const CSP = [
  "default-src 'none'",
  "script-src 'self'",
  "style-src 'self'",
  "img-src 'self' data: blob:",
  "media-src 'self' blob:",
  `connect-src ${CONNECT_SRC}`,
  "manifest-src 'self'",
  "base-uri 'none'",
  "form-action 'none'",
  "frame-ancestors 'none'"
].join('; ');

function secureHeaders(extra = {}) {
  return {
    'content-security-policy': CSP,
    'referrer-policy': 'no-referrer',
    'x-content-type-options': 'nosniff',
    'x-frame-options': 'DENY',
    'permissions-policy': 'camera=(self), microphone=(self), geolocation=()',
    'cross-origin-opener-policy': 'same-origin',
    'cross-origin-resource-policy': 'same-origin',
    ...extra
  };
}

// ------------------------------------------------------------------ util

function clientIP(req) {
  const raw = req.socket.remoteAddress || '';
  const loopback = raw === '127.0.0.1' || raw === '::1' || raw === '::ffff:127.0.0.1';
  if (TRUST_PROXY && loopback) {
    const xff = req.headers['x-forwarded-for'];
    if (xff) return String(xff).split(',')[0].trim();
  }
  return raw;
}

function hostAllowed(req) {
  const host = String(req.headers.host || '').toLowerCase();
  if (!host) return false;
  if (!ALLOWED_HOSTS.length) return true;
  const bare = host.replace(/:\d+$/, '');
  return ALLOWED_HOSTS.includes(host) || ALLOWED_HOSTS.includes(bare);
}

// Blocks cross-site WebSocket hijacking: a page on evil.example cannot open a
// socket here, because its Origin will not match the Host it was served from.
function originAllowed(req) {
  const origin = req.headers.origin;
  if (!origin) return true; // non-browser client (native app)
  let parsed;
  try { parsed = new URL(origin); } catch { return false; }
  const host = String(req.headers.host || '').toLowerCase();
  if (parsed.host.toLowerCase() === host) return true;
  const bare = parsed.hostname.toLowerCase();
  return ALLOWED_HOSTS.includes(bare) || ALLOWED_HOSTS.includes(parsed.host.toLowerCase());
}

const fails = new Map(); // ip -> { n, until }

function lockedOut(ip) {
  const rec = fails.get(ip);
  if (!rec) return false;
  if (Date.now() > rec.until) { fails.delete(ip); return false; }
  return rec.n >= AUTH_FAILS;
}

function noteFail(ip) {
  const rec = fails.get(ip) || { n: 0, until: 0 };
  rec.n += 1;
  rec.until = Date.now() + AUTH_WINDOW;
  fails.set(ip, rec);
}

// ------------------------------------------------------------------ http

const server = http.createServer(async (req, res) => {
  if (req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405, secureHeaders({ allow: 'GET, HEAD' }));
    return res.end();
  }
  if (!hostAllowed(req)) {
    res.writeHead(421, secureHeaders());
    return res.end();
  }

  const url = new URL(req.url, 'http://localhost');

  if (url.pathname === '/config.json') {
    return json(res, 200, {
      stun: STUN, turnMode: TURN_MODE, rendezvous: RENDEZVOUS_URL, authRequired: false
    });
  }
  if (url.pathname === '/healthz') {
    return json(res, 200, { ok: true, channels: rooms.size, clients: wss.clients.size });
  }
  // Parity with the rendezvous Worker, so a self-hoster has the same URL to
  // point at. It carries its own headers rather than secureHeaders(): the page
  // is a single body with an inline <style> and no subresources, which the
  // app's `default-src 'none'; style-src 'self'` CSP would block.
  if (url.pathname === '/privacy' || url.pathname === '/privacy/') {
    const body = Buffer.from(PRIVACY_HTML);
    res.writeHead(200, { ...PRIVACY_HEADERS, 'content-length': body.length });
    return res.end(req.method === 'HEAD' ? undefined : body);
  }
  if (url.pathname === '/turn') {
    const room = String(url.searchParams.get('room') || '');
    if (!ROOM_RE.test(room)) return json(res, 400, { error: 'bad room' });
    if (!TURN_URLS.length || !TURN_SECRET) return json(res, 404, { error: 'no turn configured' });
    // Must present a ticket valid for this room — no free credential farming.
    const rec = tickets.get(room);
    if (!ticketLive(rec) ||
        sha256hex(url.searchParams.get('t') || '') !== rec.hashT) {
      return json(res, 403, { error: 'not paired' });
    }
    const ttl = 3600;
    const username = String(Math.floor(Date.now() / 1000) + ttl);
    const credential = createHmac('sha1', TURN_SECRET).update(username).digest('base64');
    return json(res, 200, { iceServers: [{ urls: TURN_URLS, username, credential }], ttl });
  }

  let rel;
  try { rel = decodeURIComponent(url.pathname); }
  catch { return json(res, 400, { error: 'bad path' }); }
  if (rel.includes('\0')) return json(res, 400, { error: 'bad path' });
  if (rel.endsWith('/')) rel += 'index.html';

  const file = normalize(join(PUBLIC, rel));
  if (!file.startsWith(PUBLIC + sep) && file !== PUBLIC) {
    res.writeHead(403, secureHeaders());
    return res.end();
  }

  try {
    let body = await readFile(file);
    // The page carries a <meta> CSP too, as defence in depth for anyone serving
    // these files from something other than this process. A static file cannot
    // know the rendezvous host, so that copy used to fall back to the blanket
    // `ws: wss: https:` this header just stopped emitting. Substitute the real
    // source list so there is one definition rather than two that drift.
    if (extname(file) === '.html') {
      body = Buffer.from(
        body.toString('utf8').split(CONNECT_SRC_TOKEN).join(CONNECT_SRC), 'utf8'
      );
    }
    res.writeHead(200, secureHeaders({
      'content-type': MIME[extname(file)] || 'application/octet-stream',
      'content-length': body.length,
      'cache-control': 'no-cache'
    }));
    res.end(req.method === 'HEAD' ? undefined : body);
  } catch {
    json(res, 404, { error: 'not found' });
  }
});

function json(res, code, obj) {
  const body = Buffer.from(JSON.stringify(obj));
  res.writeHead(code, secureHeaders({
    'content-type': MIME['.json'],
    'content-length': body.length,
    'cache-control': 'no-store'
  }));
  res.end(body);
}

// ------------------------------------------------------------- signaling

/** @type {Map<string, Map<string, import('ws').WebSocket>>} */
const rooms = new Map();
const perIP = new Map();
// room -> { hashT, auth, exp }. Zero-secret admission: the Watcher's
// {type:'hello'} carries sha256(ticket); a Handheld's hello must carry the
// matching ticket. `auth` is sha256("tawny-auth-v1|" + channel key) when the
// Watcher offered it — proof of the key, which this server stores but can never
// derive, and the only thing that lets a Watcher reclaim its own room.
// Set REQUIRE_TICKET=off for a bare LAN-style deployment with no tickets.
const tickets = new Map();
const sha256hex = (s) => createHash('sha256').update(String(s)).digest('hex');
const HEX64 = /^[a-f0-9]{64}$/;
const TICKET_TTL = 24 * 60 * 60_000;
// Absolute ceiling on a ticket's life, mirroring rendezvous/room.js. A Monitor
// re-registers the same stored ticket every time it reconnects, which would
// otherwise push the idle TTL out indefinitely and leave a leaked pairing link
// valid forever. Re-registering the same hashT keeps the original issue time;
// only a re-paired channel (a different hashT) starts a new lifetime.
const TICKET_MAX_LIFETIME = 30 * 24 * 60 * 60_000;
const issuedAt = (rec) => rec.iss ?? (rec.exp - TICKET_TTL);
const ticketLive = (rec) =>
  !!rec && Date.now() <= rec.exp && Date.now() - issuedAt(rec) < TICKET_MAX_LIFETIME;
const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_MSG });

// Every relayed type is addressed. Peer ids come from the server, so a client
// cannot blind-broadcast into a channel it has joined.
//
// Must list every addressed `type` public/app.js sends through sig(): a type
// missing here is dropped in silence and its feature simply never happens on
// this transport. This list had fallen four types behind the client — the lens
// picker, remote zoom, pet-name sync and the Light key were all being discarded
// here. Keep it in step with LocalWeb.kt and rendezvous/room.js.
const RELAY = new Set([
  'offer', 'answer', 'ice', 'bye', 'chime', 'chime-ack', 'talking',
  'cameras', 'meta', 'camera-control', 'torch', 'battery'
]);

server.on('upgrade', (req, socket, head) => {
  const ip = clientIP(req);
  const deny = (code, why) => {
    socket.write(`HTTP/1.1 ${code} ${why}\r\nConnection: close\r\n\r\n`);
    socket.destroy();
  };

  let url;
  try { url = new URL(req.url, 'http://localhost'); } catch { return socket.destroy(); }
  if (!/(^|\/)ws$/.test(url.pathname)) return socket.destroy();
  if (!hostAllowed(req)) return deny(421, 'Misdirected Request');
  if (!originAllowed(req)) { noteFail(ip); return deny(403, 'Forbidden'); }
  if (lockedOut(ip)) return deny(429, 'Too Many Requests');
  if (wss.clients.size >= MAX_TOTAL) return deny(503, 'Service Unavailable');
  if ((perIP.get(ip) || 0) >= MAX_PER_IP) return deny(429, 'Too Many Requests');

  const room = String(url.searchParams.get('room') || '');
  if (!ROOM_RE.test(room)) return deny(400, 'Bad Request');

  wss.handleUpgrade(req, socket, head, (ws) => wss.emit('connection', ws, req, { room, ip, url }));
});

// A device must send {type:'hello'} first and pass admission before it is joined
// to the room or told about anyone. A Handheld's hello carries the pairing
// ticket `t`; the Watcher's carries `hashT = sha256(t)` to register it.
const REQUIRE_TICKET = process.env.REQUIRE_TICKET !== 'off';

wss.on('connection', (ws, req, ctx) => {
  const { room, ip, url } = ctx;
  const role = url.searchParams.get('role') === 'station' ? 'station' : 'viewer';
  const id = randomUUID().slice(0, 8);

  ws.meta = { id, room, role, ip, pending: true };
  ws.isAlive = true;
  // Counted from the moment the socket exists, not from admission, so an
  // unadmitted socket cannot be used to sidestep the per-IP cap.
  perIP.set(ip, (perIP.get(ip) || 0) + 1);
  ws.on('pong', () => { ws.isAlive = true; });

  // Say hello or go away. Cleared on admission and on close.
  ws.admitTimer = setTimeout(() => {
    if (ws.meta.pending) { noteFail(ip); try { ws.close(4008, 'no hello'); } catch {} }
  }, ADMIT_TIMEOUT_MS);
  ws.admitTimer.unref?.();

  const admit = (msg) => {
    let rec = tickets.get(room);
    if (rec && !ticketLive(rec)) { tickets.delete(room); rec = null; }

    // Deliberately do NOT create the room entry here. It used to be an
    // unconditional `rooms.set(room, new Map())` above every rejection path,
    // and `ws.on('close')` bails out early for a socket that never joined — so
    // each refused admission left a permanent empty Map behind and the map grew
    // without bound. Same bug LocalWeb.kt calls out; it was only ever fixed
    // there. The entry is created on success, in the one place below.
    const peers = rooms.get(room) || new Map();
    const proof = typeof msg.a === 'string' && HEX64.test(msg.a) ? msg.a : null;

    // Capacity first: nothing that gets rejected may change stored state. See
    // rendezvous/room.js — registering the ticket for a socket that is then
    // turned away let a caller who knew only the room id re-key the channel on
    // its way out, locking every paired Handheld to 4008 until the TTL expired.
    if (peers.size >= MAX_PER_ROOM) return ws.close(4003, 'channel full');
    if (!rooms.has(room) && rooms.size >= MAX_ROOMS) return ws.close(4005, 'busy');
    let evict = [];
    if (role === 'station') {
      const stations = [...peers.values()].filter((p) => p.meta.role === 'station');
      if (stations.length >= MAX_STATIONS) {
        // A Watcher whose network dropped is still on the books until the
        // heartbeat notices. Only the holder of the channel key may take the
        // room back from it; everyone else keeps getting 4004.
        if (!(rec?.auth && proof === rec.auth)) return ws.close(4004, 'monitor already running');
        evict = stations;
      }
    }

    let register = null;
    if (role === 'viewer') {
      if (REQUIRE_TICKET && (!rec || sha256hex(msg.t) !== rec.hashT)) return ws.close(4008, 'pairing expired');
    } else { // station
      if (rec?.auth && proof && proof !== rec.auth) return ws.close(4008, 'wrong channel key');
      const mayRekey = proof !== null || !rec?.auth;
      const hashT = typeof msg.hashT === 'string' && HEX64.test(msg.hashT) ? msg.hashT : null;
      if (hashT && mayRekey) {
        register = {
          hashT, auth: proof || rec?.auth || null,
          iss: rec && rec.hashT === hashT ? issuedAt(rec) : Date.now(),
          exp: Date.now() + TICKET_TTL
        };
      } else if (rec) {
        if (sha256hex(msg.t) !== rec.hashT) return ws.close(4008, 'pairing expired');
      } else if (REQUIRE_TICKET) {
        return ws.close(4008, 'no pairing ticket');
      }
    }

    // Admitted. Only now may the ticket move, or a sitting Watcher be hung up
    // on — and the evicted peer leaves the map here rather than whenever its
    // close event lands, so it is not in the welcome we are about to send.
    if (register) tickets.set(room, register);
    // First admission into this room is what creates it.
    if (!rooms.has(room)) rooms.set(room, peers);
    for (const p of evict) {
      peers.delete(p.meta.id);
      for (const peer of peers.values()) send(peer, { type: 'peer-left', id: p.meta.id });
      p.close(4005, 'replaced by owner');
    }

    ws.meta.pending = false;
    clearTimeout(ws.admitTimer);
    peers.set(id, ws);
    send(ws, {
      type: 'welcome', id, role,
      peers: [...peers.values()].filter((p) => p !== ws)
        .map((p) => ({ id: p.meta.id, role: p.meta.role }))
    });
    for (const peer of peers.values()) {
      if (peer !== ws) send(peer, { type: 'peer-joined', id, role });
    }
    log(`+ ${role} ${id} -> ${room.slice(0, 8)} (${peers.size})`);
  };

  ws.on('message', (raw) => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    if (!msg || typeof msg !== 'object') return;

    if (ws.meta.pending) {
      if (msg.type !== 'hello') return ws.close(4000, 'expected hello');
      return admit(msg);
    }
    if (!RELAY.has(msg.type) || typeof msg.to !== 'string') return;
    const peers = rooms.get(room);
    const target = peers?.get(msg.to);
    if (!target || target === ws) return;
    msg.from = id;
    send(target, msg);
  });

  ws.on('close', () => {
    clearTimeout(ws.admitTimer);
    const n = (perIP.get(ip) || 1) - 1;
    if (n <= 0) perIP.delete(ip); else perIP.set(ip, n);
    const peers = rooms.get(room);
    if (!peers || !peers.has(id)) {
      // A socket that never joined. It owns nothing, but it may have been the
      // reason an empty room is sitting there if anything ever creates one
      // early again — so sweep the room if it is empty rather than trusting it.
      if (peers && peers.size === 0) rooms.delete(room);
      return;
    }
    peers.delete(id);
    log(`- ${role} ${id} <- ${room.slice(0, 8)} (${peers.size})`);
    if (peers.size === 0) rooms.delete(room);
    else for (const peer of peers.values()) send(peer, { type: 'peer-left', id });
  });

  ws.on('error', () => ws.terminate());
});

function send(ws, obj) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(obj));
}

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    ws.ping();
  }
  const now = Date.now();
  for (const [ip, rec] of fails) if (now > rec.until) fails.delete(ip);
  for (const [room, rec] of tickets) if (!ticketLive(rec)) tickets.delete(room);
}, 30_000);
heartbeat.unref?.();

// Channel ids and tokens are secrets; request URLs never reach the log.
function log(line) {
  console.log(`${new Date().toISOString()} ${line}`);
}

for (const sig of ['SIGINT', 'SIGTERM']) {
  process.on(sig, () => {
    clearInterval(heartbeat);
    for (const ws of wss.clients) ws.close(1001, 'server shutting down');
    server.close(() => process.exit(0));
    setTimeout(() => process.exit(0), 2000).unref();
  });
}

server.listen(PORT, HOST, () => {
  log(`tawny listening on http://${HOST}:${PORT}`);
  log(`allowed hosts: ${ALLOWED_HOSTS.length ? ALLOWED_HOSTS.join(', ') : 'any'}`);
  log(`stun: ${STUN.length ? STUN.join(', ') : 'none (LAN / tailnet only)'}`);
});
