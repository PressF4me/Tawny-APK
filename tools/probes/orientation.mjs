// The picture matches how the sending phone is held — all four ways up.
//
// The gap this covers: getUserMedia hands the page frames turned to the
// *window*, and with auto-rotate off the window is not the phone. A Monitor
// lying on its side keeps a portrait window and streams a room lying on its
// side, and nothing at either end can tell. The native shell reads the
// accelerometer and reports two angles; app.js works out the difference and
// turns the picture on both ends.
//
// This lifts `quarter` and `applyRotation` out of the shipped public/app.js *by
// source text* (no copy to drift) and runs them against stubbed video elements,
// then reads the stylesheet and the Kotlin shell for the parts that are not
// functions.
//
//   node tools/probes/orientation.mjs
//
// Asserted:
//  - the correction is (windowCW - deviceCW) mod 360, for all sixteen pairings
//  - auto-rotate ON (the window tracks the phone) is always a correction of 0 —
//    i.e. this changes nothing about the behaviour that shipped before
//  - all four quarters are expressible, not just 0/180
//  - a quarter turn swaps the element's box to the stage transposed, so
//    `contain` never lands a picture wider than the stage for overflow:hidden
//    to crop; a half turn does not
//  - going back to 0 clears every class and custom property it set
//  - the stylesheet has the rules those classes need, at a specificity that
//    beats `#local.fill { transform: none }`
//  - the Monitor publishes `rot` in `meta` (already on every relay's forward
//    list, so no relay changes) and a Viewer applies it live

import fs from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, '..', '..');
const src = fs.readFileSync(path.join(root, 'public', 'app.js'), 'utf8');
const css = fs.readFileSync(path.join(root, 'public', 'style.css'), 'utf8');
const kt = fs.readFileSync(path.join(root, 'android', 'app', 'src', 'main', 'java',
  'com', 'tawny', 'monitor', 'MainActivity.kt'), 'utf8');

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

const ctx = { console };
vm.createContext(ctx);
vm.runInContext([
  grabLine(/^const quarter = .*$/m, 'quarter'),
  grab('applyRotation'),
  'globalThis.quarter = quarter;',
].join('\n'), ctx);
const { quarter, applyRotation } = ctx;

// A <video> stub with just the surface applyRotation touches.
function mkVideo(stageW, stageH) {
  const props = new Map();
  const cls = new Set();
  return {
    parentElement: { clientWidth: stageW, clientHeight: stageH },
    style: {
      setProperty: (k, v) => props.set(k, v),
      removeProperty: (k) => props.delete(k),
    },
    classList: {
      toggle: (c, on) => (on ? cls.add(c) : cls.delete(c)),
      contains: (c) => cls.has(c),
    },
    props, cls,
  };
}

// ---- the correction ---------------------------------------------------------
console.log('\nthe correction, all sixteen pairings');
{
  const angles = [0, 90, 180, 270];
  let allOk = true;
  const rows = [];
  for (const w of angles) {
    for (const d of angles) {
      const got = quarter(w - d);
      const want = (w - d + 360) % 360;
      if (got !== want) { allOk = false; rows.push(`w=${w} d=${d} -> ${got} want ${want}`); }
    }
  }
  check('correction = (windowCW - deviceCW) mod 360 everywhere', allOk, rows.join('; '));

  // Auto-rotate on: the window follows the phone, so the two angles are equal.
  check('auto-rotate on is always a correction of 0 (unchanged behaviour)',
    angles.every((a) => quarter(a - a) === 0));

  check('all four quarters are reachable, not just 0 and 180',
    new Set(angles.map((d) => quarter(0 - d))).size === 4);

  check('a negative difference lands in 0..359',
    quarter(-90) === 270 && quarter(-180) === 180 && quarter(-270) === 90);
  check('an off-quarter reading snaps to the nearest quarter',
    quarter(44) === 0 && quarter(46) === 90 && quarter(359) === 0);
}

// ---- the box swap -----------------------------------------------------------
console.log('\nthe box a quarter turn is fitted into');
{
  const STAGE_W = 400, STAGE_H = 800;
  for (const q of [90, 270]) {
    const v = mkVideo(STAGE_W, STAGE_H);
    applyRotation(v, q);
    check(`${q}°: marked as a quarter turn`, v.cls.has('rot') && v.cls.has('rot-q'));
    check(`${q}°: the box is the stage transposed`,
      v.props.get('--rot-w') === `${STAGE_H}px` && v.props.get('--rot-h') === `${STAGE_W}px`,
      `${v.props.get('--rot-w')} x ${v.props.get('--rot-h')}`);
    check(`${q}°: the turn itself is set`, v.props.get('--rot') === `${q}deg`);
  }

  const half = mkVideo(STAGE_W, STAGE_H);
  applyRotation(half, 180);
  check('180°: turned, but the box is left alone',
    half.cls.has('rot') && !half.cls.has('rot-q')
      && !half.props.has('--rot-w') && !half.props.has('--rot-h'));

  // The crop this exists to prevent: a portrait 540x960 frame (a pinned
  // portrait window on a phone held sideways) shown on a 400x800 stage.
  const fitInto = (w, h, bw, bh) => { const s = Math.min(bw / w, bh / h); return [w * s, h * s]; };
  // Fitted into the stage-shaped box, then turned: the fitted *height* becomes
  // the on-screen width, and it does not fit.
  const [, noSwapH] = fitInto(540, 960, STAGE_W, STAGE_H);
  check('without the swap a quarter turn would overflow the stage',
    noSwapH > STAGE_W, `${noSwapH.toFixed(0)}px wide into a ${STAGE_W}px stage`);
  const [swapW, swapH] = fitInto(540, 960, STAGE_H, STAGE_W);
  check('with the swap the whole frame fits, turned',
    swapH <= STAGE_W + 0.5 && swapW <= STAGE_H + 0.5,
    `${swapW.toFixed(0)}x${swapH.toFixed(0)} turned into ${STAGE_W}x${STAGE_H}`);
}

// ---- back to upright --------------------------------------------------------
console.log('\nback to 0');
{
  const v = mkVideo(400, 800);
  applyRotation(v, 90);
  applyRotation(v, 0);
  check('every class is dropped', !v.cls.has('rot') && !v.cls.has('rot-q'));
  check('the transposed box is dropped',
    !v.props.has('--rot-w') && !v.props.has('--rot-h'));
  check('--rot goes back to 0deg', v.props.get('--rot') === '0deg');
}

// ---- the stylesheet ---------------------------------------------------------
console.log('\nstylesheet');
{
  check('#remote.rot and #local.fill.rot both take --rot',
    /#remote\.rot,\s*\n?\s*#local\.fill\.rot\s*\{[^}]*rotate\(var\(--rot/.test(css));
  const q = css.match(/#remote\.rot-q,[\s\S]*?\}/);
  check('the quarter-turn rule exists', !!q);
  const body = q ? q[0] : '';
  check('it sizes from --rot-w / --rot-h',
    /width:\s*var\(--rot-w/.test(body) && /height:\s*var\(--rot-h/.test(body), body);
  check('it re-centres rather than inheriting inset: 0',
    /right:\s*auto/.test(body) && /bottom:\s*auto/.test(body)
      && /translate\(-50%,\s*-50%\)/.test(body), body);
  // #local.fill sets `transform: none`; the rot rules must out-specify it.
  check('the rot rules out-specify #local.fill { transform: none }',
    /#local\.fill\.rot\b/.test(css) && /#local\.fill\.rot-q\b/.test(css));
  check('#remote still never crops', /#remote\s*\{[^}]*object-fit:\s*contain/.test(css));
}

// ---- the wire ---------------------------------------------------------------
console.log('\nboth ends');
{
  check('the Monitor publishes rot in meta', /type: 'meta', rot: S\.rot/.test(src));
  check('a joining Handheld is told before its first frame',
    /const meta = \{ type: 'meta', rot: S\.rot, to: peer\.id \}/.test(src));
  check('a Viewer applies a live rot change',
    /typeof m\.rot === 'number'[\s\S]{0,300}applyRemoteRotation\(\)/.test(src));
  check('`meta` is already on every relay forward list (no relay change needed)',
    ['server.js', 'rendezvous/room.js', 'rendezvous/deno/main.ts',
      'android/app/src/main/java/com/tawny/monitor/LocalWeb.kt']
      .every((f) => /['"]meta['"]/.test(fs.readFileSync(path.join(root, f), 'utf8'))));
  check('a new session forgets the last one\'s orientation',
    /S\.remoteRot = 0;\s*\n\s*applyRemoteRotation\(\)/.test(src));
  check('a snapshot carries the same turn as the screen',
    /const rot = S\.role === 'viewer' \? quarter\(S\.remoteRot\) : 0/.test(src));
}

// ---- the shell's two angles -------------------------------------------------
console.log('\nthe native shell');
{
  check('it reports both angles to the page',
    /window\.tawnyOrientation\(\$deviceCW,\$w\)/.test(kt));
  // Display.getRotation() is the rotation of the drawn graphics, i.e. the
  // opposite of the physical turn — so the table must invert 90 and 270.
  check('Display.getRotation() is inverted into clockwise-from-natural',
    /ROTATION_90 -> 270/.test(kt) && /ROTATION_270 -> 90/.test(kt)
      && /ROTATION_180 -> 180/.test(kt));
  check('the sensor is only on while the live screen is up',
    /startOrientationWatch\(\)/.test(kt) && /stopOrientationWatch\(\)/.test(kt)
      && /onPause\(\)[\s\S]{0,200}stopOrientationWatch\(\)/.test(kt));
  check('a reading near a 45° boundary does not chatter', /abs\(off\) > 30/.test(kt));
  check('ORIENTATION_UNKNOWN (flat on a table) keeps the last reading',
    /deg == ORIENTATION_UNKNOWN\) return/.test(kt));
}

console.log(fails ? `\n${fails} FAILURE(S)` : '\nall assertions passed');
process.exit(fails ? 1 : 0);
