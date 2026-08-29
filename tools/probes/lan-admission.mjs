// Does the LAN relay actually require proof of the channel key?
// Run with the port forwarded: adb forward tcp:8820 tcp:8820
import { webcrypto as crypto } from 'node:crypto';
const KEY = process.argv[2];
const BASE = process.argv[3] || 'ws://127.0.0.1:8820';
const hex = (b) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
const sha = async (s) => hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));
const roomFor = async (k) => (await sha(`tawny-room-v1|${k}`)).slice(0, 32);
async function hmac(secret, msg) {
  const k = await crypto.subtle.importKey('raw', new TextEncoder().encode(secret),
    { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return hex(await crypto.subtle.sign('HMAC', k, new TextEncoder().encode(msg)));
}

/** Connect as a viewer and answer the challenge with `secret`. */
function attempt(room, secret, label) {
  return new Promise((res) => {
    const ws = new WebSocket(`${BASE}/ws?room=${room}&role=viewer`);
    let done = false;
    const fin = (r) => { if (!done) { done = true; try { ws.close(); } catch {} res(r); } };
    const t = setTimeout(() => fin({ ok: false, why: 'timeout' }), 8000);
    ws.onmessage = async (e) => {
      const m = JSON.parse(e.data);
      if (m.type === 'challenge') {
        if (secret === null) return;              // never answer
        const r = await hmac(secret, `tawny-lan-v1|${m.n}`);
        ws.send(JSON.stringify({ type: 'hello', r }));
      }
      if (m.type === 'welcome') { clearTimeout(t); fin({ ok: true, id: m.id }); }
    };
    ws.onclose = (ev) => { clearTimeout(t); fin({ ok: false, code: ev.code, why: ev.reason }); };
    ws.onerror = () => {};
  });
}

const room = await roomFor(KEY);
const out = [];
const check = (n, p, d) => { out.push(p); console.log(`${p ? 'PASS' : 'FAIL'}  ${n}${d ? ' — ' + d : ''}`); };
console.log(`LAN relay ${BASE}, room ${room}\n`);

const good = await attempt(room, KEY);
check('a device holding the channel key is admitted', good.ok,
  good.ok ? `id=${good.id}` : `${good.code} ${good.why}`);

const bad = await attempt(room, 'not-the-real-channel-key');
check('a wrong key is REFUSED', !bad.ok,
  bad.ok ? 'ADMITTED — the check is broken open' : `${bad.code} ${bad.why}`);

const silent = await attempt(room, null);
check('a socket that never answers is dropped', !silent.ok,
  silent.ok ? 'ADMITTED WITHOUT ANSWERING' : `${silent.code} ${silent.why}`);

console.log(`\n${out.filter(Boolean).length}/${out.length} passed`);
process.exit(out.every(Boolean) ? 0 : 1);
