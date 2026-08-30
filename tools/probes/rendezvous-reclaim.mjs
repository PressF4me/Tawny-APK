// The ghost-Monitor lockout: a Monitor whose radio drops leaves a socket the
// relay cannot tell from a live one for minutes, and the same phone re-hosting
// the same pairing was met with 4004 "monitor already running" — by its own
// corpse. A Monitor that proves the channel key may now take its room back.
//
// This probe holds a *real* station socket open and reclaims out from under it,
// which is the same admission path a ghost takes: as far as the relay knows,
// both are "a station is already seated".
//
// Node 24 has a built-in WebSocket; the `ws` package is NOT installed here.
//
//   node rendezvous-reclaim.mjs [wss://host]
import { webcrypto as crypto } from 'node:crypto';

const BASE = process.argv[2] || 'wss://tawny-rendezvous.tawny1.workers.dev';

const hex = (b) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
const sha = async (s) => hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));
const roomFor = async (key) => (await sha(`tawny-room-v1|${key}`)).slice(0, 32);
const authFor = async (key) => sha(`tawny-auth-v1|${key}`);
const rand = (n) => Buffer.from(crypto.getRandomValues(new Uint8Array(n))).toString('base64url');
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** Connect, send one hello, report what the relay did. */
function probe(room, role, hello, ms = 6000) {
  return new Promise((resolve) => {
    const ws = new WebSocket(`${BASE}/ws?room=${room}&role=${role}`);
    let done = false;
    const finish = (r) => {
      if (done) return; done = true;
      try { ws.close(); } catch {}
      resolve(r);
    };
    const timer = setTimeout(() => finish({ ok: false, why: 'timeout' }), ms);
    ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', ...hello }));
    ws.onmessage = (e) => {
      let m; try { m = JSON.parse(e.data); } catch { return; }
      if (m.type === 'welcome') { clearTimeout(timer); finish({ ok: true, id: m.id, peers: m.peers }); }
    };
    ws.onclose = (ev) => { clearTimeout(timer); finish({ ok: false, code: ev.code, why: ev.reason }); };
    ws.onerror = () => {};
  });
}

/**
 * Hold a socket open, recording everything the relay says to it — so we can ask
 * afterwards whether it was hung up on, and with which code.
 */
function hold(room, role, hello) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${BASE}/ws?room=${room}&role=${role}`);
    const seen = [];
    const st = { ws, seen, closed: null, id: null };
    ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', ...hello }));
    ws.onmessage = (e) => {
      let m; try { m = JSON.parse(e.data); } catch { return; }
      seen.push(m);
      if (m.type === 'welcome') { st.id = m.id; resolve(st); }
    };
    ws.onclose = (ev) => {
      st.closed = { code: ev.code, reason: ev.reason };
      if (!st.id) reject(new Error(`held socket closed ${ev.code} ${ev.reason}`));
    };
    ws.onerror = () => {};
    setTimeout(() => { if (!st.id) reject(new Error('hold timeout')); }, 8000);
  });
}

const results = [];
const check = (name, pass, detail) => {
  results.push(pass);
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);
};

console.log(`relay: ${BASE}\n`);

// ---- 1. the lockout itself -------------------------------------------------
const key = rand(16);
const token = rand(16);
const room = await roomFor(key);
const auth = await authFor(key);
const hashT = await sha(token);

const ghost = await hold(room, 'station', { t: token, hashT, a: auth })
  .catch((e) => { console.log('could not seat a station:', e.message); process.exit(1); });
check('Monitor claims a fresh room', !!ghost.id, `id=${ghost.id}`);

const viewer = await hold(room, 'viewer', { t: token }).catch((e) => ({ err: e.message }));
check('its paired Viewer joins', !!viewer.id, viewer.id ? `id=${viewer.id}` : viewer.err);

// The same phone, same pairing, coming back after a blip.
const back = await hold(room, 'station', { t: token, hashT, a: auth }).catch((e) => ({ err: e.message }));
check('the owner re-hosting is ADMITTED, not locked out',
  !!back.id, back.id ? `id=${back.id}` : back.err);

await sleep(400);
check('the stale Monitor is hung up on with 4005',
  ghost.closed?.code === 4005,
  ghost.closed ? `${ghost.closed.code} ${ghost.closed.reason}` : 'still connected');

const left = viewer.seen?.some((m) => m.type === 'peer-left' && m.id === ghost.id);
const joined = viewer.seen?.some((m) => m.type === 'peer-joined' && m.id === back.id);
check('the Viewer is told the old Monitor left and the new one arrived',
  !!left && !!joined, `peer-left=${!!left} peer-joined=${!!joined}`);

const welcomeHasGhost = (back.seen.find((m) => m.type === 'welcome')?.peers || [])
  .some((p) => p.id === ghost.id);
check('the new Monitor is not introduced to the one it replaced', !welcomeHasGhost);

// ---- 2. the anti-squat property must be untouched --------------------------
const noProof = await probe(room, 'station', { t: token, hashT });
check('a station with NO key proof is still refused 4004',
  noProof.code === 4004, `${noProof.code} ${noProof.why || ''}`);

const wrongProof = await probe(room, 'station', {
  t: token, hashT, a: await authFor(rand(16)),
});
check('a station with the WRONG key proof does not evict',
  wrongProof.code === 4004 || wrongProof.code === 4008,
  `${wrongProof.code} ${wrongProof.why || ''}`);

check('the real Monitor survived both', back.closed === null,
  back.closed ? `closed ${back.closed.code}` : 'still connected');

const stillPaired = await probe(room, 'viewer', { t: token });
check('the channel still admits its paired Viewer', stillPaired.ok,
  stillPaired.ok ? `id=${stillPaired.id}` : `${stillPaired.code} ${stillPaired.why}`);

// ---- 3. a room claimed before key proof existed -----------------------------
// Nothing is stored to compare a proof against, so there is no owner to
// recognise: eviction stays off and 4004 remains the anti-squat guard.
const oldKey = rand(16);
const oldToken = rand(16);
const oldRoom = await roomFor(oldKey);
const legacy = await hold(oldRoom, 'station', { t: oldToken, hashT: await sha(oldToken) })
  .catch((e) => ({ err: e.message }));
check('a legacy Monitor (no key proof) claims a room', !!legacy.id, legacy.err || `id=${legacy.id}`);

const intoLegacy = await probe(oldRoom, 'station', {
  t: oldToken, hashT: await sha(oldToken), a: await authFor(oldKey),
});
check('an unrecognised owner cannot evict a legacy Monitor',
  intoLegacy.code === 4004 && legacy.closed === null,
  `caller got ${intoLegacy.code || 'in'}; legacy ${legacy.closed ? 'CLOSED' : 'still connected'}`);

for (const s of [back, viewer, legacy]) { try { s.ws?.close(); } catch {} }
console.log(`\n${results.filter(Boolean).length}/${results.length} passed`);
process.exit(results.every(Boolean) ? 0 : 1);
