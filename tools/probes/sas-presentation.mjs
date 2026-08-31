// The Monitor's safety-code card: is it always about the right phone, and does
// it stay down once the user has vouched for this channel?
//
// The code used to also live in a "Verify:" chip in the top rail on every
// connection - always-on clutter over the picture. That chip is gone; only the
// card remains, and only while a review is genuinely pending.
//
// This lifts sasReviewKey / sasReviewed / markSasReviewed / sasPendingViewers /
// syncStationSas out of the shipped public/app.js *by source text* (no copy to
// drift) and runs them against stubbed peers, DOM and localStorage.
//
//   node tools/probes/sas-presentation.mjs
//
// The invariant checked after every sync: if the card is up, the digits in it
// are the `sas` of the peer S.sasAsk names, and that peer is an unverified
// cloud viewer.

import fs from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const src = fs.readFileSync(path.join(here, '..', '..', 'public', 'app.js'), 'utf8');

const grab = (name) => {
  const start = src.indexOf(`function ${name}(`);
  if (start < 0) throw new Error('not found: ' + name);
  let depth = 0;
  for (let j = src.indexOf('{', start); j < src.length; j++) {
    if (src[j] === '{') depth++;
    else if (src[j] === '}' && !--depth) return src.slice(start, j + 1);
  }
  throw new Error('unbalanced: ' + name);
};
const grabConst = (name) => {
  const m = src.match(new RegExp(`^const ${name} = .*$`, 'm'));
  if (!m) throw new Error('not found: const ' + name);
  return m[0];
};

const mk = () => {
  const e = {
    hidden: true, textContent: '', _cls: new Set(),
    classList: {
      add: (c) => e._cls.add(c), remove: (c) => e._cls.delete(c),
      toggle: (c, on) => (on ? e._cls.add(c) : e._cls.delete(c)),
      contains: (c) => e._cls.has(c),
    },
  };
  return e;
};
const freshEl = () => {
  const o = {};
  for (const k of ['sas', 'sascode', 'saschip', 'sasnote', 'sasok', 'sasno']) o[k] = mk();
  return o;
};
const mkStore = () => {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
  };
};

const ctx = { S: null, el: null, localStorage: null, viewerPeers: null, console };
vm.createContext(ctx);
vm.runInContext(
  [grabConst('sasReviewKey'), grab('sasReviewed'), grab('markSasReviewed'),
   grab('sasPendingViewers'), grab('syncStationSas')].join('\n'),
  ctx,
);

let fails = 0;
const check = (label, cond, detail) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (detail ? ' :: ' + detail : '')); }
};

function scenario(name, peers, run) {
  console.log('\n' + name);
  const map = new Map(peers.map((p) => [p.id, p]));
  ctx.S = { role: 'station', sasAsk: null, peers: map, channel: { id: 'chan-1' } };
  ctx.el = freshEl();
  ctx.localStorage = mkStore();
  ctx.viewerPeers = () => [...map.values()].filter((p) => p.role === 'viewer');
  run({
    S: ctx.S, el: ctx.el, peers: map,
    sync: () => ctx.syncStationSas(),
    vouch: () => vm.runInContext('markSasReviewed("VOUCHED")', ctx),
  });
}

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
  check('the code never sits in the rail', el.saschip.hidden === true);
  check('card asks about a', S.sasAsk === 'a');
});

scenario('3 cloud viewers, all unverified',
  [V('a', 'cloud', 'AAA-111'), V('b', 'cloud', 'BBB-222'), V('c', 'cloud', 'CCC-333')],
  ({ S, el, peers, sync }) => {
    sync();
    invariant(S, el);
    check('asks about a first', S.sasAsk === 'a');
    check('says 2 more behind it', /2 more phones after it/.test(el.sasnote.textContent), el.sasnote.textContent);

    peers.get('b').sas = 'BBB-999';           // a re-handshake must not steal the card
    sync();
    invariant(S, el);
    check('card still pinned to a', S.sasAsk === 'a' && el.sascode.textContent === 'AAA-111');

    peers.get('a').sasOk = true; S.sasAsk = null; sync();
    invariant(S, el);
    check('advances to b with b\'s own code', S.sasAsk === 'b' && el.sascode.textContent === 'BBB-999', el.sascode.textContent);
    check('says 1 more behind it', /1 more phone after it/.test(el.sasnote.textContent), el.sasnote.textContent);

    peers.get('b').sasOk = true; S.sasAsk = null; sync();
    invariant(S, el);
    check('advances to c', S.sasAsk === 'c' && el.sascode.textContent === 'CCC-333');
    check('no "more after it" on the last one', !/more phone/.test(el.sasnote.textContent), el.sasnote.textContent);

    peers.get('c').sasOk = true; S.sasAsk = null; sync();
    invariant(S, el);
    check('card gone when all three verified', el.sas.hidden === true);
  });

scenario('the pinned phone leaves mid-prompt',
  [V('a', 'cloud', 'AAA-111'), V('b', 'cloud', 'BBB-222')],
  ({ S, el, peers, sync }) => {
    sync();
    check('asks about a', S.sasAsk === 'a');
    peers.delete('a');
    sync();
    invariant(S, el);
    check('falls through to b, not a stale code',
      S.sasAsk === 'b' && el.sascode.textContent === 'BBB-222', el.sascode.textContent);
  });

scenario('LAN viewers are never asked about',
  [V('a', 'lan', null), V('b', 'lan', null)],
  ({ S, el, sync }) => {
    sync();
    invariant(S, el);
    check('no card for LAN-only', el.sas.hidden === true);
  });

scenario('mixed LAN + one cloud',
  [V('a', 'lan', null), V('b', 'cloud', 'BBB-222'), V('c', 'lan', null)],
  ({ S, el, sync }) => {
    sync();
    invariant(S, el);
    check('card asks about b', S.sasAsk === 'b' && el.sascode.textContent === 'BBB-222');
  });

scenario('a peer whose DTLS has not settled yet has no code',
  [V('a', 'cloud', null), V('b', 'cloud', 'BBB-222')],
  ({ S, el, sync }) => {
    sync();
    invariant(S, el);
    check('skips the codeless peer', S.sasAsk === 'b');
  });

scenario('once vouched for, the card stays down for this channel',
  [V('a', 'cloud', 'AAA-111')],
  ({ S, el, peers, sync, vouch }) => {
    sync();
    check('first pending cloud viewer raises the card', el.sas.hidden === false && S.sasAsk === 'a');
    vouch();                                  // user tapped "Looks right"
    sync();
    invariant(S, el);
    check('card down after vouching', el.sas.hidden === true && S.sasAsk === null);

    // A brand-new cloud viewer connects later - still no card on this channel.
    peers.set('z', V('z', 'cloud', 'ZZZ-999'));
    sync();
    invariant(S, el);
    check('a later cloud viewer does not re-raise it', el.sas.hidden === true && S.sasAsk === null);
  });

console.log(fails ? `\n${fails} FAILURE(S)` : '\nall assertions passed');
process.exit(fails ? 1 : 0);
