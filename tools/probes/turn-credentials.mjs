// turn-credentials.mjs — is /turn actually handing out relay credentials?
//
// /turn only answers a caller that has proved a pairing: it registers a Monitor
// in a fresh room over the real ws handshake, then asks /turn with that room's
// ticket. A 200 with a turn: URL means Cloudflare Realtime TURN is provisioned;
// a 404 "no turn configured" means the TURN_KEY_ID / TURN_API_TOKEN secrets are
// not set on the Worker.
//
//   node tools/probes/turn-credentials.mjs                       # deployed worker
//   node tools/probes/turn-credentials.mjs ws://127.0.0.1:8787   # wrangler dev
//
// Uses Node's built-in WebSocket (Node >= 22); the `ws` package is not needed.

const arg = process.argv[2] || 'wss://tawny-rendezvous.tawny1.workers.dev';
const WS = arg.replace(/^http/i, 'ws').replace(/\/+$/, '');
const HTTP = arg.replace(/^ws/i, 'http').replace(/\/+$/, '');

const enc = new TextEncoder();
const hex = (b) => [...new Uint8Array(b)].map((x) => x.toString(16).padStart(2, '0')).join('');
const sha = async (s) => hex(await crypto.subtle.digest('SHA-256', enc.encode(s)));
const rand = (n) => hex(crypto.getRandomValues(new Uint8Array(n)));

const key = 'probe_' + rand(8);
const ticket = 'tkt_' + rand(8);
const room = (await sha('tawny-room-v1|' + key)).slice(0, 32);

const ws = new WebSocket(`${WS}/ws?room=${room}&role=station`);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = () => rej(new Error('ws connect failed')); });
ws.send(JSON.stringify({
  type: 'hello', t: ticket,
  hashT: await sha(ticket),
  a: await sha('tawny-auth-v1|' + key),
}));
await new Promise((res, rej) => {
  const t = setTimeout(() => rej(new Error('no welcome')), 5000);
  ws.onmessage = (e) => { if (JSON.parse(e.data).type === 'welcome') { clearTimeout(t); res(); } };
});

const r = await fetch(`${HTTP}/turn?room=${room}&t=${ticket}`);
const body = await r.json().catch(() => ({}));
ws.close();

const urls = (body.iceServers || []).flatMap((s) => (Array.isArray(s.urls) ? s.urls : [s.urls]));
const relay = urls.filter((u) => /^turns?:/i.test(u));

if (r.status === 200 && relay.length) {
  console.log(`PASS  TURN provisioned — ${relay.length} relay URL(s), ttl ${body.ttl}s`);
  for (const u of relay) console.log(`        ${u}`);
  process.exit(0);
}
if (r.status === 404) {
  console.log('FAIL  /turn -> 404 "no turn configured" — set TURN_KEY_ID / TURN_API_TOKEN as Worker secrets');
  process.exit(1);
}
console.log(`FAIL  /turn -> ${r.status} ${JSON.stringify(body)}`);
process.exit(1);
