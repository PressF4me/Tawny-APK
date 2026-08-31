// The four signalling relays each carry their own copy of the "addressed
// message types we forward" allowlist:
//
//   server.js                      (Node dev / self-host)
//   rendezvous/room.js             (Cloudflare Worker)
//   rendezvous/deno/main.ts        (Deno port)
//   android/.../LocalWeb.kt        (the relay inside the Monitor app)
//
// A type in public/app.js's sig() but missing from one of these is dropped in
// silence on that transport — which is how the lens picker, remote zoom,
// pet-name sync and the Light key each broke on one relay and not the others.
// This probe just proves the four lists are identical, and that every addressed
// type app.js actually sends is in them.
//
//   node tools/probes/relay-allowlists.mjs

import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), '..', '..');
const read = (p) => fs.readFileSync(path.join(root, p), 'utf8');

// Pull the string literals out of the first `RELAY = <set/collection literal>`.
function relaySet(src, file) {
  const m = src.match(/RELAY\s*=\s*(?:new Set|setOf)?\s*[([]([\s\S]*?)[)\]]/);
  if (!m) throw new Error(`no RELAY literal in ${file}`);
  const items = [...m[1].matchAll(/["']([a-z-]+)["']/g)].map((x) => x[1]);
  if (!items.length) throw new Error(`empty RELAY literal in ${file}`);
  return new Set(items);
}

const files = {
  'server.js': 'server.js',
  'rendezvous/room.js': 'rendezvous/room.js',
  'rendezvous/deno/main.ts': 'rendezvous/deno/main.ts',
  'LocalWeb.kt': 'android/app/src/main/java/com/tawny/monitor/LocalWeb.kt',
};

let fails = 0;
const check = (label, cond, detail) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (detail ? ' :: ' + detail : '')); }
};

const sets = Object.fromEntries(
  Object.entries(files).map(([name, p]) => [name, relaySet(read(p), name)]),
);

// 1. all four identical
const names = Object.keys(sets);
const base = sets[names[0]];
const sorted = (s) => [...s].sort().join(',');
for (const n of names.slice(1)) {
  const a = sorted(base), b = sorted(sets[n]);
  check(`${names[0]} == ${n}`, a === b,
    `\n         ${names[0]}: ${a}\n         ${n}: ${b}`);
}

// 2. every addressed type the client relies on is forwarded. app.js sends some
//    of these by spreading a helper (torchState(), batteryState()) rather than a
//    `type:` literal, so this list is curated, not scraped — update it when a
//    new addressed message type is added to sig().
const REQUIRED = [
  'offer', 'answer', 'ice', 'bye',        // the call itself
  'chime', 'chime-ack', 'talking',        // sound
  'cameras', 'camera-control',            // lens + zoom
  'meta',                                 // pause + pet name
  'torch',                               // the Light key
  'battery',                             // the Monitor's charge, mirrored to Handhelds
];
for (const t of REQUIRED) {
  const missing = names.filter((n) => !sets[n].has(t));
  check(`every relay forwards "${t}"`, missing.length === 0,
    'missing from: ' + missing.join(', '));
}

// 3. nothing extra slipped into one list only
const union = new Set(names.flatMap((n) => [...sets[n]]));
for (const t of union) {
  if (REQUIRED.includes(t)) continue;
  check(`unlisted type "${t}" is either in every relay or none`,
    names.every((n) => sets[n].has(t)) || names.every((n) => !sets[n].has(t)));
}

console.log(fails ? `\n${fails} FAILURE(S)` : '\nall relays carry the same list');
process.exit(fails ? 1 : 0);
