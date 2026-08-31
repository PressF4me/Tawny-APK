// How the full-frame video sits in the stage, and how the Monitor shapes its
// capture. Lifts screenIsWide / idealCaptureSize / fitVideo out of public/app.js
// by source text.
//
//   node tools/probes/video-fit.mjs
//
// The rule under test: fill (cover) when the picture and the screen face the
// same way, box (contain) when they don't — aspect-driven, no device or
// resolution assumptions. And the capture's long axis follows the orientation.

import fs from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const src = fs.readFileSync(path.join(here, '..', '..', 'public', 'app.js'), 'utf8');

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

function ctxFor(winW, winH) {
  const ctx = {
    window: { innerWidth: winW, innerHeight: winH, screen: {} },
    console,
  };
  vm.createContext(ctx);
  vm.runInContext(
    [grabFn('screenIsWide'), grabFn('idealCaptureSize'), grabFn('fitVideo')].join('\n'),
    ctx,
  );
  return ctx;
}

const vid = (w, h, hidden = false) => {
  const v = { videoWidth: w, videoHeight: h, hidden, style: { objectFit: 'contain' } };
  return v;
};

// ---- fitVideo: the four orientation pairings ----------------------------
console.log('\nlandscape screen (2340 x 1080)');
{
  const { fitVideo } = ctxFor(2340, 1080);
  let v = vid(1280, 720); fitVideo(v);
  check('wide feed  -> cover (no side bars)', v.style.objectFit === 'cover', v.style.objectFit);
  v = vid(720, 1280); fitVideo(v);
  check('tall feed  -> contain (whole frame)', v.style.objectFit === 'contain', v.style.objectFit);
}

console.log('\nportrait screen (1080 x 2340)');
{
  const { fitVideo } = ctxFor(1080, 2340);
  let v = vid(720, 1280); fitVideo(v);
  check('tall feed  -> cover', v.style.objectFit === 'cover', v.style.objectFit);
  v = vid(1280, 720); fitVideo(v);
  check('wide feed  -> contain (the accepted letterbox)', v.style.objectFit === 'contain', v.style.objectFit);
}

console.log('\nsquare-ish edge + no metadata');
{
  const { fitVideo } = ctxFor(1600, 1600);
  let v = vid(1000, 1000); fitVideo(v);
  check('square feed on square screen -> cover', v.style.objectFit === 'cover');
  v = vid(0, 0); fitVideo(v);
  check('no metadata -> style cleared (stylesheet decides)', v.style.objectFit === '');
  v = vid(1280, 720, true); fitVideo(v);
  check('hidden element -> style cleared', v.style.objectFit === '');
}

// ---- idealCaptureSize: long axis follows the orientation ---------------
console.log('\nidealCaptureSize');
{
  const wide = ctxFor(2000, 1000).idealCaptureSize();
  check('landscape: width is the long axis',
    wide.width.ideal === 960 && wide.height.ideal === 540, JSON.stringify(wide));
  const tall = ctxFor(1000, 2000).idealCaptureSize();
  check('portrait: height is the long axis',
    tall.width.ideal === 540 && tall.height.ideal === 960, JSON.stringify(tall));
  const hd = ctxFor(2000, 1000).idealCaptureSize(1280, 720);
  check('custom size keeps its long/short split',
    hd.width.ideal === 1280 && hd.height.ideal === 720);
}

console.log(fails ? `\n${fails} FAILURE(S)` : '\nall assertions passed');
process.exit(fails ? 1 : 0);
