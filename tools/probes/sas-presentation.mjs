// Does the Monitor ever put a safety code beside a prompt about a *different*
// phone's session? It used to: showSas() wrote every Handheld's code straight
// into the one #sas-chip, so with two or three phones on the cloud relay the
// chip carried whichever DTLS handshake finished last.
//
// This lifts syncStationSas() and sasPendingViewers() out of the shipped
// public/app.js by source text (no copy to drift) and runs them against stubbed
// peers and DOM. The invariant asserted after every call: if the card is up,
// the digits in it are the `sas` of the peer S.sasAsk names, and that peer is
// an unverified cloud viewer.
//
//   node tools/probes/sas-presentation.mjs
//
// Related measurement, taken in this app's own WebView on 2026-08-30: three
// RTCPeerConnections created in one page reported three different
// a=fingerprint values. WebRTC mints a certificate per connection and the app
// persists none, so a code is good for exactly one call - which is why nothing
// here is remembered across sessions.

import fs from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const APP = path.join(here, '..', '..', 'public', 'app.js');
const src = fs.readFileSync(APP, 'utf8');
const grab = (name) => {
  const start = src.indexOf(`function ${name}(`);
  if (start < 0) throw new Error('not found: ' + name);
  let depth = 0, i = src.indexOf('{', start);
  for (let j = i; j < src.length; j++) {
    if (src[j] === '{') depth++;
    else if (src[j] === '}') { depth--; if (!depth) return src.slice(start, j + 1); }
  }
  throw new Error('unbalanced: ' + name);
};

const mk = (tag) => ({
  hidden: true, textContent: '', _cls: new Set(),
  classList: { add(c) { this.o._cls.add(c); }, remove(c) { this.o._cls.delete(c); },
               toggle(c, on) { on ? this.o._cls.add(c) : this.o._cls.delete(c); } },
  tag,
});
function freshEl() {
  const o = {};
  for (const k of ['sas', 'sascode', 'saschip', 'sasnote', 'sasok', 'sasno']) {
    const e = mk(k); e.classList.o = e; o[k] = e;
  }
  return o;
}

const ctx = { S: null, el: null, viewerPeers: null, console };
vm.createContext(ctx);
vm.runInContext(grab('sasPendingViewers') + '\n' + grab('syncStationSas'), ctx);

let fails = 0;
function check(label, cond, detail) {
  if (!cond) { fails++; console.log('  FAIL ' + label + (detail ? ' :: ' + detail : '')); }
  else console.log('  ok   ' + label);
}

function scenario(name, peers, run) {
  console.log('\n' + name);
  const map = new Map(peers.map((p) => [p.id, p]));
  ctx.S = { role: 'station', sasAsk: null, peers: map };
  ctx.el = freshEl();
  ctx.viewerPeers = () => [...map.values()].filter((p) => p.role === 'viewer');
  run({ S: ctx.S, el: ctx.el, peers: map, sync: () => ctx.syncStationSas() });
}

// The invariant, checked after every sync: if the card is up, the code in it is
// the code of the peer S.sasAsk names, and that peer is a cloud viewer.
function invariant(S, el) {
  if (el.sas.hidden) {
    check('card hidden -> no peer pinned', S.sasAsk === null, 'sasAsk=' + S.sasAsk);
    return;
  }
  const p = S.peers.get(S.sasAsk);
  check('card code belongs to the pinned peer',
    !!p && el.sascode.textContent === p.sas,
    `asks about ${S.sasAsk} (sas=${p && p.sas}) but shows ${el.sascode.textContent}`);
  check('pinned peer is an unverified cloud viewer',
    !!p && p.transport.tag === 'cloud' && p.role === 'viewer' && !p.sasOk);
}

const V = (id, tag, sas, sasOk = false) =>
  ({ id, role: 'viewer', transport: { tag }, sas, sasOk });

scenario('1 cloud viewer', [V('a', 'cloud', 'ABC-123')], ({ S, el, sync }) => {
  sync();
  invariant(S, el);
  check('chip shows the one code', el.saschip.hidden === false && el.saschip.textContent === 'Verify: ABC-123', el.saschip.textContent);
  check('card asks about a', S.sasAsk === 'a');
});

scenario('3 cloud viewers, all unverified',
  [V('a', 'cloud', 'AAA-111'), V('b', 'cloud', 'BBB-222'), V('c', 'cloud', 'CCC-333')],
  ({ S, el, peers, sync }) => {
    sync();
    invariant(S, el);
    check('chip hidden with >1 cloud viewer', el.saschip.hidden === true, el.saschip.textContent);
    check('asks about a first', S.sasAsk === 'a');
    check('says 2 more behind it', /2 more phones after it/.test(el.sasnote.textContent), el.sasnote.textContent);

    // A fourth handshake landing must NOT steal the card from a.
    peers.get('b').sas = 'BBB-999';
    sync();
    invariant(S, el);
    check('card still pinned to a after another peer re-computes', S.sasAsk === 'a' && el.sascode.textContent === 'AAA-111');

    // "Looks right" on a -> b, then c.
    peers.get('a').sasOk = true; S.sasAsk = null; sync();
    invariant(S, el);
    check('advances to b with b\'s own code', S.sasAsk === 'b' && el.sascode.textContent === 'BBB-999', el.sascode.textContent);
    check('says 1 more behind it', /1 more phone after it/.test(el.sasnote.textContent), el.sasnote.textContent);

    peers.get('b').sasOk = true; S.sasAsk = null; sync();
    invariant(S, el);
    check('advances to c with c\'s own code', S.sasAsk === 'c' && el.sascode.textContent === 'CCC-333');
    check('no "more after it" on the last one', !/more phone/.test(el.sasnote.textContent), el.sasnote.textContent);

    peers.get('c').sasOk = true; S.sasAsk = null; sync();
    invariant(S, el);
    check('card gone when all three verified', el.sas.hidden === true);
    check('chip still hidden with 3 cloud viewers', el.saschip.hidden === true);
  });

scenario('the pinned phone leaves mid-prompt',
  [V('a', 'cloud', 'AAA-111'), V('b', 'cloud', 'BBB-222')],
  ({ S, el, peers, sync }) => {
    sync();
    check('asks about a', S.sasAsk === 'a');
    peers.delete('a');                     // removePeer() then calls sync()
    sync();
    invariant(S, el);
    check('falls through to b, not a stale code', S.sasAsk === 'b' && el.sascode.textContent === 'BBB-222', el.sascode.textContent);
    check('chip returns now that one cloud viewer is left',
      el.saschip.hidden === false && el.saschip.textContent === 'Verify: BBB-222', el.saschip.textContent);
  });

scenario('LAN viewers are never asked about',
  [V('a', 'lan', null), V('b', 'lan', null)],
  ({ S, el, sync }) => {
    sync();
    invariant(S, el);
    check('no card for LAN-only', el.sas.hidden === true);
    check('no chip for LAN-only', el.saschip.hidden === true);
  });

scenario('mixed LAN + one cloud',
  [V('a', 'lan', null), V('b', 'cloud', 'BBB-222'), V('c', 'lan', null)],
  ({ S, el, sync }) => {
    sync();
    invariant(S, el);
    check('chip labels the single cloud viewer', el.saschip.textContent === 'Verify: BBB-222');
    check('card asks about b', S.sasAsk === 'b' && el.sascode.textContent === 'BBB-222');
  });

scenario('mixed LAN + two cloud',
  [V('a', 'lan', null), V('b', 'cloud', 'BBB-222'), V('c', 'cloud', 'CCC-333')],
  ({ S, el, sync }) => {
    sync();
    invariant(S, el);
    check('chip hidden with two cloud viewers', el.saschip.hidden === true);
  });

scenario('a peer whose DTLS has not settled yet has no code',
  [V('a', 'cloud', null), V('b', 'cloud', 'BBB-222')],
  ({ S, el, sync }) => {
    sync();
    invariant(S, el);
    check('skips the codeless peer', S.sasAsk === 'b');
    check('chip shows b (a has no code to be confused with)',
      el.saschip.hidden === false && el.saschip.textContent === 'Verify: BBB-222');
  });

console.log(fails ? `\n${fails} FAILURE(S)` : '\nall assertions passed');
process.exit(fails ? 1 : 0);
