// Where does a chime get played?
//
// Lifts CHIMES / DEFAULT_CHIME / chimeSpec / playChime out of public/app.js by
// source text and runs playChime() against stubs. The regression this guards:
// on the native Monitor a chime must go to the shell (tellNative), never the
// page's WebAudio — WebAudio lands on STREAM_MUSIC, which Android mutes under a
// call and the volume keys won't raise while one is running.
//
//   node tools/probes/chime-routing.mjs

import fs from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const src = fs.readFileSync(path.join(here, '..', '..', 'public', 'app.js'), 'utf8');

const grabFn = (name) => {
  const re = new RegExp(`(?:async function|function) ${name}\\(`);
  const start = src.search(re);
  if (start < 0) throw new Error('not found: ' + name);
  let depth = 0;
  for (let j = src.indexOf('{', start); j < src.length; j++) {
    if (src[j] === '{') depth++;
    else if (src[j] === '}' && !--depth) return src.slice(start, j + 1);
  }
  throw new Error('unbalanced: ' + name);
};
const grabExpr = (name) => {
  const m = src.match(new RegExp(`const ${name} = [\\s\\S]*?;\\n`));
  if (!m) throw new Error('not found: const ' + name);
  return m[0];
};

let fails = 0;
const check = (label, cond, detail) => {
  if (cond) console.log('  ok   ' + label);
  else { fails++; console.log('  FAIL ' + label + (detail ? ' :: ' + detail : '')); }
};

function makeCtx({ nativeShell }) {
  const calls = { native: [], webaudio: 0, diag: [] };
  const ctx = {
    console,
    androidNative: nativeShell ? {} : undefined,
    S: { role: 'station' },
    tellNative: (event, extra) => calls.native.push({ event, ...extra }),
    audioCtx: () => { calls.webaudio++; return { state: 'running', resume: async () => {} }; },
    chimeBuffers: new Map(),
    playChimeBuffer: () => { calls.webaudio++; },
    synthChime: () => { calls.webaudio++; },
    loadChime: () => {},
    diag: (l) => calls.diag.push(l),
    _calls: calls,
  };
  vm.createContext(ctx);
  vm.runInContext(
    [grabExpr('CHIMES'), grabExpr('DEFAULT_CHIME'),
     grabFn('chimeSpec'), grabFn('playChime')].join('\n'),
    ctx,
  );
  return ctx;
}

// --- native Monitor: straight to the shell -------------------------------
{
  const ctx = makeCtx({ nativeShell: true });
  await vm.runInContext('playChime("meow")', ctx);
  const c = ctx._calls;
  check('native: one tellNative("chime")', c.native.length === 1 && c.native[0].event === 'chime');
  check('native: carries the slug', c.native[0].slug === 'meow');
  check('native: WebAudio never touched', c.webaudio === 0);

  await vm.runInContext('playChime("garbage; drop table")', ctx);
  check('native: an unknown slug is sanitised to the default',
    ctx._calls.native[1].slug === 'bell');
}

// --- browser Monitor: WebAudio path ------------------------------------
{
  const ctx = makeCtx({ nativeShell: false });
  await vm.runInContext('playChime("bark")', ctx);
  const c = ctx._calls;
  check('browser: nothing sent to a shell', c.native.length === 0);
  check('browser: played through WebAudio', c.webaudio >= 1);
  check('browser: logged a diag line', c.diag.some((l) => l.startsWith('chime bark')));
}

console.log(fails ? `\n${fails} FAILURE(S)` : '\nall assertions passed');
process.exit(fails ? 1 : 0);
