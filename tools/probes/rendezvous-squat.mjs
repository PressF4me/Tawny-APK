// Does the new key-commitment let an attacker permanently claim an IDLE room?
// (i.e. one whose Monitor is not currently connected.) It must not.
import { webcrypto as crypto } from 'node:crypto';
const BASE = process.argv[2] || 'ws://127.0.0.1:8787';
const hex = (b) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
const sha = async (s) => hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(s)));
const roomFor = async (k) => (await sha(`tawny-room-v1|${k}`)).slice(0, 32);
const authFor = async (k) => sha(`tawny-auth-v1|${k}`);
const rand = (n) => Buffer.from(crypto.getRandomValues(new Uint8Array(n))).toString('base64url');

function probe(room, role, hello, ms = 6000) {
  return new Promise((res) => {
    const ws = new WebSocket(`${BASE}/ws?room=${room}&role=${role}`);
    let done = false;
    const fin = (r) => { if (!done) { done = true; try { ws.close(); } catch {} res(r); } };
    const t = setTimeout(() => fin({ ok: false, why: 'timeout' }), ms);
    ws.onopen = () => ws.send(JSON.stringify({ type: 'hello', ...hello }));
    ws.onmessage = (e) => { const m = JSON.parse(e.data); if (m.type === 'welcome') { clearTimeout(t); fin({ ok: true, id: m.id }); } };
    ws.onclose = (ev) => { clearTimeout(t); fin({ ok: false, code: ev.code, why: ev.reason }); };
    ws.onerror = () => {};
  });
}
const out = [];
const check = (n, p, d) => { out.push(p); console.log(`${p ? 'PASS' : 'FAIL'}  ${n}${d ? ' — ' + d : ''}`); };

const key = rand(16), token = rand(16);
const room = await roomFor(key), auth = await authFor(key);

// The real Monitor uses the channel once, then goes away (phone off).
const first = await probe(room, 'station', { t: token, hashT: await sha(token), a: auth });
check('Monitor claims a fresh room', first.ok, first.id);
await new Promise((r) => setTimeout(r, 300));

// An attacker who knows the room id squats the now-idle room with their own key.
const evilKey = rand(16), evilToken = rand(16);
const squat = await probe(room, 'station', {
  t: evilToken, hashT: await sha(evilToken), a: await authFor(evilKey),
});
check('squatter on an idle room is refused', !squat.ok,
  squat.ok ? 'SQUATTER ADMITTED AND RE-KEYED' : `${squat.code} ${squat.why}`);

// The real Monitor comes back and must still own its channel.
const back = await probe(room, 'station', { t: token, hashT: await sha(token), a: auth });
check('real Monitor still owns its channel afterwards', back.ok,
  back.ok ? back.id : `PERMANENTLY LOCKED OUT: ${back.code} ${back.why}`);
await new Promise((r) => setTimeout(r, 300));

const v = await probe(room, 'viewer', { t: token });
check('its paired Viewer still connects', v.ok, v.ok ? v.id : `${v.code} ${v.why}`);

console.log(`\n${out.filter(Boolean).length}/${out.length} passed`);
process.exit(out.every(Boolean) ? 0 : 1);
