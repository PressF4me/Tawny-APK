// A pairing code stops admitting new phones ten minutes after it is shown.
//
// The awkward part of this rule is that the channel key rides *inside* the
// code, so the deadline cannot be enforced by the bearer (it would lie) or by
// the relay (an expired code hands it the same room id and the same long-lived
// admission ticket as a fresh one). The Monitor is the only party that knows
// when it put a code on screen, and it is the party that answers the offer — so
// the Monitor is where the rule lives, on both transports.
//
// This lifts newPairCode / pairCodeLeft / pairingAllowed / rememberPeer /
// knownPeers / bondId out of the shipped public/app.js *by source text* (no
// copy to drift) and runs them against a stubbed localStorage and crypto.
//
//   node tools/probes/pairing-expiry.mjs
//
// Asserted:
//  - a phone presenting the code currently on screen is let in, and remembered
//  - a phone presenting a *previous* code is refused, even inside ten minutes
//  - the same code past its deadline is refused
//  - a remembered phone gets in with no code at all, and after the deadline —
//    pairing expiry is not a session timeout
//  - fail-closed: no code on the Monitor, no `pc` in the offer, a non-string
//    `pc`, and a malformed `pid` all refuse
//  - the remembered list is bounded (a stolen code cannot enrol an army)
//  - the link the Monitor hands out carries both `c` and `e`, and `adopt()`
//    refuses a link whose `e` has passed with the expiry sentence

import fs from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const src = fs.readFileSync(path.join(here, '..', '..', 'public', 'app.js'), 'utf8');

const grab = (name) => {
  const start = src.search(new RegExp(`function ${name}\\(`));
  if (start < 0) throw new Error('not found: ' + name);
  let depth = 0;
  for (let j = src.indexOf('{', start); j < src.length; j++) {
    if (src[j] === '{') depth++;
    else if (src[j] === '}' && !--depth) return src.slice(start, j + 1);
  }
  throw new Error('unbalanced: ' + name);
};
const grabLine = (re, what) => {
  const m = src.match(re);
  if (!m) throw new Error('not found: ' + what);
  return m[0];
};

let fails = 0;
const check = (label, cond, detail) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (detail ? ' :: ' + detail : '')); }
};

function mkStore() {
  const m = new Map();
  return {
    getItem: (k) => (m.has(k) ? m.get(k) : null),
    setItem: (k, v) => m.set(k, String(v)),
    removeItem: (k) => m.delete(k),
    _map: m,
  };
}

// A deterministic CSPRNG stand-in: the probe cares about the plumbing, not the
// entropy, and predictable bytes make a failure readable.
function mkCrypto() {
  let n = 0;
  return { getRandomValues: (a) => { for (let i = 0; i < a.length; i++) a[i] = (n++ * 37 + i) & 255; return a; } };
}

function ctx() {
  const c = {
    console,
    localStorage: mkStore(),
    crypto: mkCrypto(),
    btoa: (s) => Buffer.from(s, 'binary').toString('base64'),
    S: { channel: { id: 'ch-1', key: 'k'.repeat(22), name: 'Pet camera' }, pairCode: null, pairSeen: null, token: '' },
  };
  vm.createContext(c);
  vm.runInContext([
    grabLine(/^const PAIR_TTL_MS = .*$/m, 'PAIR_TTL_MS'),
    grabLine(/^const PAIRED_KEY = .*$/m, 'PAIRED_KEY'),
    grabLine(/^const BOND_KEY = .*$/m, 'BOND_KEY'),
    grabLine(/^const MAX_REMEMBERED_PEERS = .*$/m, 'MAX_REMEMBERED_PEERS'),
    grabLine(/^const HEX32 = .*$/m, 'HEX32'),
    grabLine(/^const knownPeers = .*$/m, 'knownPeers'),
    grabLine(/^const readJSON = [\s\S]*?^\};$/m, 'readJSON'),
    grabLine(/^const writeJSON = [\s\S]*?^\};$/m, 'writeJSON'),
    grab('b64url'),
    grab('newPairCode'),
    grab('pairCodeLeft'),
    grab('bondId'),
    grab('rememberPeer'),
    grab('pairingAllowed'),
    // `const` in a vm script is a lexical binding, not a property of the
    // context, so hand the ones the assertions read back out by hand.
    'globalThis.knownPeers = knownPeers;',
    'globalThis.MAX_REMEMBERED_PEERS = MAX_REMEMBERED_PEERS;',
    'globalThis.PAIR_TTL_MS = PAIR_TTL_MS;',
  ].join('\n'), c);
  return c;
}

// ---- the happy path, and what it remembers --------------------------------
console.log('\nadmitting a phone');
{
  const c = ctx();
  const code = c.newPairCode();
  check('a fresh code has its full ten minutes',
    c.pairCodeLeft() > 9 * 60 * 1000 && c.pairCodeLeft() <= 10 * 60 * 1000,
    String(c.pairCodeLeft()));
  const pid = 'a'.repeat(32);
  check('the code on screen lets a new phone in', c.pairingAllowed({ pid, pc: code.c }) === true);
  check('and the phone is remembered', c.knownPeers().includes(pid));
  check('a remembered phone needs no code at all', c.pairingAllowed({ pid }) === true);
}

// ---- an old code is not the current code ----------------------------------
console.log('\nrefusing a stale code');
{
  const c = ctx();
  const first = c.newPairCode().c;
  c.newPairCode();                       // the Monitor rotated
  check('the previous code is refused inside the window',
    c.pairingAllowed({ pid: 'b'.repeat(32), pc: first }) === false);
  check('and refusing enrolled nobody', c.knownPeers().length === 0);
}

// ---- the deadline ----------------------------------------------------------
console.log('\nthe ten minutes');
{
  const c = ctx();
  const code = c.newPairCode();
  c.S.pairCode.exp = Date.now() - 1;     // the same code, one millisecond late
  check('an expired code is refused', c.pairingAllowed({ pid: 'c'.repeat(32), pc: code.c }) === false);
  check('expiry is not a session timeout — a phone let in earlier still gets in',
    (() => {
      c.S.pairCode.exp = Date.now() + 60_000;
      const pid = 'd'.repeat(32);
      c.pairingAllowed({ pid, pc: code.c });
      c.S.pairCode.exp = Date.now() - 1;
      return c.pairingAllowed({ pid }) === true;
    })());
}

// ---- fail closed -----------------------------------------------------------
console.log('\nfail-closed paths');
{
  const c = ctx();
  check('no code on the Monitor refuses everything',
    c.pairingAllowed({ pid: 'e'.repeat(32), pc: 'anything' }) === false);
  const code = c.newPairCode();
  check('an offer with no pc is refused', c.pairingAllowed({ pid: 'e'.repeat(32) }) === false);
  check('an offer with a non-string pc is refused',
    c.pairingAllowed({ pid: 'e'.repeat(32), pc: { toString: () => code.c } }) === false);
  check('undefined pc never matches an undefined code',
    (() => { c.S.pairCode = { c: undefined, exp: Date.now() + 1000 }; return c.pairingAllowed({}) === false; })());
  c.S.pairCode = code;
  check('a malformed pid is still admitted on a live code, but remembers nothing',
    c.pairingAllowed({ pid: 'nope', pc: code.c }) === true && c.knownPeers().length === 0);
}

// ---- the remembered list is bounded ---------------------------------------
console.log('\nbounded enrolment');
{
  const c = ctx();
  const code = c.newPairCode();
  for (let i = 0; i < 30; i++) {
    c.pairingAllowed({ pid: String(i).padStart(32, '0').replace(/[^0-9a-f]/g, '0'), pc: code.c });
  }
  check(`at most ${c.MAX_REMEMBERED_PEERS} phones are remembered`,
    c.knownPeers().length <= c.MAX_REMEMBERED_PEERS, String(c.knownPeers().length));
}

// ---- the bond id -----------------------------------------------------------
console.log('\nthe bond a Handheld is known by');
{
  const c = ctx();
  const a = c.bondId();
  check('is 32 hex characters', /^[a-f0-9]{32}$/.test(a), a);
  check('is stable for the channel', c.bondId() === a);
  check('is per channel', (() => { c.S.channel = { id: 'ch-2', key: 'x' }; return c.bondId() !== a; })());
}

// ---- the link carries the deadline ----------------------------------------
console.log('\nthe link the Monitor hands out');
{
  check('pairLink() sets c', /frag\.set\('c',/.test(src));
  check('pairLink() sets e', /frag\.set\('e',/.test(src));
  check('the native payload sets c and e',
    (() => {
      const kt = fs.readFileSync(path.join(here, '..', '..', 'android', 'app', 'src', 'main',
        'java', 'com', 'tawny', 'monitor', 'MainActivity.kt'), 'utf8');
      return /append\("&c=/.test(kt) && /append\("&e=/.test(kt);
    })());
  check('adopt() refuses a lapsed e with the expiry sentence',
    /Date\.now\(\) > exp \* 1000[\s\S]{0,80}lastPairError = EXPIRED_MESSAGE/.test(src));
  check('the Monitor refuses an offer that fails the gate with bye/expired',
    /!pairingAllowed\(m\)\)[\s\S]{0,240}reason: 'expired'/.test(src));
  check('a Viewer turned away shows the expiry sentence',
    /m\.reason === 'expired'[\s\S]{0,80}bail\(EXPIRED_MESSAGE, 'expired'\)/.test(src));
}

console.log(fails ? `\n${fails} FAILURE(S)` : '\nall assertions passed');
process.exit(fails ? 1 : 0);
