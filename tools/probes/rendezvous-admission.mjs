// Speaks Tawny's real rendezvous handshake against a deployed worker.
// Node 24 has a built-in WebSocket; the `ws` package is NOT installed here.
//
//   node rz-test.mjs [wss://host]
import { webcrypto as crypto } from 'node:crypto';

const BASE = process.argv[2] || 'wss://tawny-rendezvous.tawny1.workers.dev';

const hex = (b) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
const sha = async (s) => hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));
const roomFor = async (key) => (await sha(`tawny-room-v1|${key}`)).slice(0, 32);
const authFor = async (key) => sha(`tawny-auth-v1|${key}`);

function rand(n) {
  return Buffer.from(crypto.getRandomValues(new Uint8Array(n)))
    .toString('base64url');
}

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

/** Hold a socket open so the room has a live station while we test. */
function hold(room, role, hello) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${BASE}/ws?room=${room}&role=${role}`);
    ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', ...hello }));
    ws.onmessage = (e) => {
      let m; try { m = JSON.parse(e.data); } catch { return; }
      if (m.type === 'welcome') resolve({ ws, id: m.id });
    };
    ws.onclose = (ev) => reject(new Error(`held socket closed ${ev.code} ${ev.reason}`));
    setTimeout(() => reject(new Error('hold timeout')), 8000);
  });
}

const line = (name, pass, detail) =>
  console.log(`${pass ? 'PASS' : 'FAIL'}  ${name}${detail ? ' — ' + detail : ''}`);

const results = [];
const check = (name, pass, detail) => { results.push(pass); line(name, pass, detail); };

console.log(`relay: ${BASE}\n`);

// A fresh channel nobody has ever used.
const key = rand(16);
const token = rand(16);
const room = await roomFor(key);
const auth = await authFor(key);
const hashT = await sha(token);
console.log(`room ${room} (fresh)\n`);

// 1. The legitimate Monitor claims the room.
const station = await hold(room, 'station', { t: token, hashT, a: auth })
  .catch((e) => { console.log('could not seat a station:', e.message); process.exit(1); });
check('legitimate Monitor is admitted', !!station.id, `id=${station.id}`);

// 2. Its paired Viewer gets in.
const v = await probe(room, 'viewer', { t: token });
check('paired Viewer is admitted', v.ok, v.ok ? `id=${v.id}` : `${v.code} ${v.why}`);

// 3. THE ATTACK: someone who learned only the room id tries to re-key it.
//    They cannot compute `a`, so they either omit it or guess.
//
//    Being refused admission is NOT the interesting part — the old worker
//    rewrote the ticket *before* it checked whether a Monitor was already
//    seated, so the attacker got bounced with 4004 and bricked the channel
//    anyway. The only honest test is whether the real Viewer still works.
const evilToken = rand(16);
const evilHashT = await sha(evilToken);

const noAuth = await probe(room, 'station', { t: evilToken, hashT: evilHashT });
const survivedNoAuth = await probe(room, 'viewer', { t: token });
check('re-key WITHOUT the key proof does not brick the channel',
  survivedNoAuth.ok,
  `attacker got ${noAuth.code || 'in'} ${noAuth.why || ''}; ` +
  (survivedNoAuth.ok ? 'real Viewer still admitted'
                     : `real Viewer LOCKED OUT (${survivedNoAuth.code} ${survivedNoAuth.why})`));

const wrongAuth = await probe(room, 'station', {
  t: evilToken, hashT: evilHashT, a: await authFor(rand(16)),
});
const survivedWrong = await probe(room, 'viewer', { t: token });
check('re-key with a WRONG key proof does not brick the channel',
  survivedWrong.ok,
  `attacker got ${wrongAuth.code || 'in'} ${wrongAuth.why || ''}; ` +
  (survivedWrong.ok ? 'real Viewer still admitted'
                    : `real Viewer LOCKED OUT (${survivedWrong.code} ${survivedWrong.why})`));

// 4. A legitimate re-pair on the same channel must still be able to re-key,
//    so an app data wipe or "Start over" recovers instead of waiting out a TTL.
station.ws.close();
await new Promise((r) => setTimeout(r, 400));
const token2 = rand(16);
const station2 = await hold(room, 'station', { t: token2, hashT: await sha(token2), a: auth })
  .catch((e) => ({ err: e.message }));
check('legitimate re-pair can still re-key the room',
  !!station2.id, station2.id ? `id=${station2.id}` : station2.err);

const v2 = await probe(room, 'viewer', { t: token2 });
check('Viewer with the NEW ticket is admitted after the re-pair',
  v2.ok, v2.ok ? `id=${v2.id}` : `${v2.code} ${v2.why}`);

try { station2.ws?.close(); } catch {}
console.log(`\n${results.filter(Boolean).length}/${results.length} passed`);
process.exit(results.every(Boolean) ? 0 : 1);
