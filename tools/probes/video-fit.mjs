// The full-frame picture and the Monitor's capture shape.
//
// Two rules:
//   * the stage always shows the WHOLE frame — `object-fit: contain`, every
//     orientation. Turning to landscape is when you want all of the room, not
//     its top cropped away to lose a bar.
//   * the Monitor's capture takes its long axis from how the phone is held
//     (idealCaptureSize), at a fixed ~540p budget — which is what keeps the
//     bars small, or gone when both ends face the same way.
//
//   node tools/probes/video-fit.mjs

import fs from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, '..', '..');
const src = fs.readFileSync(path.join(root, 'public', 'app.js'), 'utf8');
const css = fs.readFileSync(path.join(root, 'public', 'style.css'), 'utf8');

const grabFn = (name) => {
  const start = src.search(new RegExp(`function ${name}\\(`));
  if (start < 0) throw new Error('not found: ' + name);
  let depth = 0;
  for (let j = src.indexOf('{', start); j < src.length; j++) {
    if (src[j] === '{') depth++;
    else if (src[j] === '}' && !--depth) return src.slice(start, j + 1);
  }
  throw new Error('unbalanced: ' + name);
};

let fails = 0;
const check = (label, cond, detail) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (detail ? ' :: ' + detail : '')); }
};

function ctxFor(w, h) {
  const ctx = { window: { innerWidth: w, innerHeight: h, screen: {} }, console };
  vm.createContext(ctx);
  vm.runInContext([grabFn('screenIsWide'), grabFn('idealCaptureSize')].join('\n'), ctx);
  return ctx;
}

// ---- the stage never crops -------------------------------------------------
console.log('\nstage fit (stylesheet)');
{
  const rule = css.match(/#remote\s*\{[\s\S]*?\}/);
  check('#remote rule exists', !!rule);
  const body = rule ? rule[0] : '';
  check('#remote is object-fit: contain', /object-fit:\s*contain/.test(body), body);
  check('#remote never object-fit: cover', !/object-fit:\s*cover/.test(body));
  check('app.js no longer sets objectFit inline',
    !/\.style\.objectFit\s*=/.test(src));
}

// ---- capture shape follows the orientation -------------------------------
console.log('\nidealCaptureSize');
{
  const wide = ctxFor(2000, 1000).idealCaptureSize();
  check('landscape: width is the long axis',
    wide.width.ideal === 960 && wide.height.ideal === 540, JSON.stringify(wide));
  const tall = ctxFor(1000, 2000).idealCaptureSize();
  check('portrait: height is the long axis',
    tall.width.ideal === 540 && tall.height.ideal === 960, JSON.stringify(tall));
  const hd = ctxFor(2000, 1000).idealCaptureSize(1280, 720);
  check('a custom size keeps its long/short split',
    hd.width.ideal === 1280 && hd.height.ideal === 720);
  const sq = ctxFor(1500, 1500);
  check('square screen counts as wide (>= comparison)', sq.screenIsWide() === true);
}

// ---- every capture path goes through idealCaptureSize ------------------
console.log('\nno fixed capture rectangle survives');
{
  // width/height ideals should only ever appear via idealCaptureSize(...)
  const stray = [...src.matchAll(/width:\s*\{\s*ideal:\s*\d+\s*\}\s*,\s*height:\s*\{\s*ideal:\s*\d+\s*\}/g)];
  check('no literal "width:{ideal},height:{ideal}" pair left in a getUserMedia call',
    stray.length === 0, stray.map((m) => m[0]).join(' | '));
}

console.log(fails ? `\n${fails} FAILURE(S)` : '\nall assertions passed');
process.exit(fails ? 1 : 0);
