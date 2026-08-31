// The Handheld reviews a channel's safety code once, then never again.
//
// WebRTC mints a fresh DTLS certificate per RTCPeerConnection, so the code is
// different on the next call by construction - re-prompting every reconnect only
// taught people to tap it away. This lifts showViewerSas() / sasReviewed() /
// markSasReviewed() out of the shipped public/app.js *by source text* (no copy
// to drift) and runs them against a stubbed DOM + localStorage.
//
//   node tools/probes/sas-review-once.mjs
//
// Asserted:
//  - first cloud call for a channel: the review card is shown
//  - after "Looks right" (markSasReviewed): every later call is silent - no
//    card, no chip
//  - a code that could not be computed still shows (that is an alarm, not a
//    review), and does not get remembered
//  - the once-flag is per channel: a different channel still gets its first ask

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
  const re = new RegExp(`^const ${name} = .*$`, 'm');
  const m = src.match(re);
  if (!m) throw new Error('not found: const ' + name);
  return m[0];
};

function mkEl() {
  const el = {};
  for (const k of ['sas', 'saschip', 'sascode', 'sasnote', 'sasok', 'sasno']) {
    el[k] = {
      hidden: true, textContent: '', _cls: new Set(),
      classList: {
        add(c) { el[k]._cls.add(c); },
        remove(c) { el[k]._cls.delete(c); },
        toggle(c, on) { on ? el[k]._cls.add(c) : el[k]._cls.delete(c); },
        contains(c) { return el[k]._cls.has(c); },
      },
    };
  }
  return el;
}

function mkStore() {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
    _map: m,
  };
}

const ctx = { S: null, el: null, localStorage: null, console };
vm.createContext(ctx);
vm.runInContext(
  [grabConst('sasReviewKey'), grab('sasReviewed'), grab('markSasReviewed'),
   grab('showViewerSas')].join('\n'),
  ctx,
);

let fails = 0;
const check = (label, cond, detail) => {
  if (cond) { console.log('  ok   ' + label); }
  else { fails++; console.log('  FAIL ' + label + (detail ? ' :: ' + detail : '')); }
};

// Simulate the Handheld tapping "Looks right" (the #sas-ok viewer branch).
function tapLooksRight() {
  if (!ctx.el.sas.classList.contains('sas--warn')) {
    vm.runInContext('markSasReviewed(el.sascode.textContent)', ctx);
  }
  ctx.el.sas.hidden = true;
  ctx.el.saschip.hidden = true;
}

console.log('\nfirst cloud call for a channel');
ctx.S = { role: 'viewer', channel: { id: 'chan-A' } };
ctx.el = mkEl();
ctx.localStorage = mkStore();
vm.runInContext('showViewerSas("ABC-123")', ctx);
check('review card is shown', ctx.el.sas.hidden === false);
check('the code never sits in the rail chip', ctx.el.saschip.hidden === true);
check('not marked reviewed yet', ctx.localStorage.getItem('tawny.sasok.chan-A') === null);

console.log('\nuser confirms, then reconnects');
tapLooksRight();
check('flag stored for this channel',
  ctx.localStorage.getItem('tawny.sasok.chan-A') === 'ABC-123');
ctx.el = mkEl();                                  // fresh DOM for the next call
vm.runInContext('showViewerSas("XYZ-789")', ctx); // different code, as WebRTC guarantees
check('card stays hidden on the second call', ctx.el.sas.hidden === true);
check('chip stays hidden on the second call', ctx.el.saschip.hidden === true);

console.log('\nmany more reconnects');
let everShown = false;
for (let i = 0; i < 10; i++) {
  ctx.el = mkEl();
  vm.runInContext(`showViewerSas("RUN-${i}00")`, ctx);
  if (!ctx.el.sas.hidden || !ctx.el.saschip.hidden) everShown = true;
}
check('never shown again across 10 reconnects', everShown === false);

console.log('\na code that could not be computed still alarms');
ctx.el = mkEl();
vm.runInContext('showViewerSas(null)', ctx);
check('card is shown for a missing code', ctx.el.sas.hidden === false);
check('card carries the warn class', ctx.el.sas.classList.contains('sas--warn'));
tapLooksRight();
check('a warn state is not remembered as a review',
  ctx.localStorage.getItem('tawny.sasok.chan-A') === 'ABC-123');

console.log('\nthe once-flag is per channel');
ctx.S = { role: 'viewer', channel: { id: 'chan-B' } };
ctx.el = mkEl();
vm.runInContext('showViewerSas("DEF-456")', ctx);
check('a different channel still gets its first ask', ctx.el.sas.hidden === false);

console.log(fails ? `\n${fails} FAILURE(S)` : '\nall assertions passed');
process.exit(fails ? 1 : 0);
