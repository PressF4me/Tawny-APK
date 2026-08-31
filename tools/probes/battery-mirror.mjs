// The Monitor's battery, mirrored to the Handhelds.
//
// Lifts setStationBattery / batteryState / broadcastBattery / updateBatteryUI
// out of public/app.js *by source text* and runs them against a stubbed DOM,
// peer set and sig(). Checks the two things that make it "accurate on the
// Handheld": the Monitor only re-sends on a real change, and the Handheld's
// chip maps a percent to the right gauge width and the right state class.
//
//   node tools/probes/battery-mirror.mjs

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
  const m = src.match(new RegExp(`const ${name} = [\\s\\S]*?\\}\\);`, 'm'));
  if (!m) throw new Error('not found: const ' + name);
  return m[0];
};

const mkEl = () => {
  const e = {
    hidden: true, textContent: '', _cls: new Set(), _attr: {},
    classList: {
      toggle: (c, on) => (on ? e._cls.add(c) : e._cls.delete(c)),
      contains: (c) => e._cls.has(c),
    },
    setAttribute: (k, v) => { e._attr[k] = v; },
  };
  return e;
};

const sent = [];
const ctx = {
  S: { role: 'station', battery: { level: null, charging: null },
       remoteBattery: { level: null, charging: null } },
  el: {}, console,
  viewerPeers: () => [{ id: 'v1' }, { id: 'v2' }],
  sig: (obj) => sent.push(obj),
  bumpRail: () => {},
};
for (const k of ['battchip', 'battFill', 'battPct']) ctx.el[k] = mkEl();
vm.createContext(ctx);
vm.runInContext(
  [grabConst('batteryState'), grab('broadcastBattery'),
   grab('setStationBattery'), grab('updateBatteryUI')].join('\n'),
  ctx,
);

let fails = 0;
const check = (label, cond, detail) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (detail ? ' :: ' + detail : '')); }
};

// --- Monitor side: only re-send on a real change ---------------------------
console.log('\nMonitor rebroadcasts only on change');
vm.runInContext('setStationBattery(73.4, false)', ctx);
check('first reading is sent to every viewer', sent.length === 2);
check('rounded to a whole percent', sent[0].level === 73 && sent[0].charging === false);

sent.length = 0;
vm.runInContext('setStationBattery(73.0, false)', ctx);   // same percent, same state
check('identical reading is not re-sent', sent.length === 0);

vm.runInContext('setStationBattery(73, true)', ctx);      // plugged in — state changed
check('a plug/unplug at the same percent IS sent', sent.length === 2 && sent[0].charging === true);

sent.length = 0;
vm.runInContext('setStationBattery(72, true)', ctx);
check('a 1% drop is sent', sent.length === 2 && sent[0].level === 72);

// --- Handheld side: percent -> gauge + state ------------------------------
console.log('\nHandheld chip maps the reading');
ctx.S.role = 'viewer';

ctx.S.remoteBattery = { level: 50, charging: false };
vm.runInContext('updateBatteryUI()', ctx);
check('chip is shown', ctx.el.battchip.hidden === false);
check('percent text', ctx.el.battPct.textContent === '50%');
check('gauge is half of 20px', ctx.el.battFill._attr.width === '10.0');
check('not charging, not low at 50%',
  !ctx.el.battchip._cls.has('is-charging') && !ctx.el.battchip._cls.has('is-low'));

ctx.S.remoteBattery = { level: 12, charging: false };
vm.runInContext('updateBatteryUI()', ctx);
check('12% unplugged reads as low', ctx.el.battchip._cls.has('is-low'));

ctx.S.remoteBattery = { level: 12, charging: true };
vm.runInContext('updateBatteryUI()', ctx);
check('12% on power is charging, not low',
  ctx.el.battchip._cls.has('is-charging') && !ctx.el.battchip._cls.has('is-low'));

ctx.S.remoteBattery = { level: null, charging: null };
vm.runInContext('updateBatteryUI()', ctx);
check('no reading -> chip hidden', ctx.el.battchip.hidden === true);

console.log(fails ? `\n${fails} FAILURE(S)` : '\nall assertions passed');
process.exit(fails ? 1 : 0);
