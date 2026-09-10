// A custom rendezvous can never take the remote path down with it.
//
// The Servers screen (behind the diagnostics hatch) lets an advanced user point
// Tawny at their own rendezvous and TURN. A URL typed into a settings field is
// exactly the kind of thing that is wrong, or right and then down at 3am — so
// the built-in tunnel is *preferred against*, never replaced. This lifts
// `wsBase` / `fallbackBase` / `rendezvousBase` / `fallBackToDefault` /
// `iceServers` out of the shipped public/app.js *by source text* and runs them
// against a stubbed transport entry.
//
//   node tools/probes/relay-fallback.mjs
//
// Asserted:
//  - with no custom relay set there is nothing to fall back to, and nothing
//    about the existing path changes
//  - a custom relay that will not answer hands the session to the built-in one,
//    and moves the TURN fetch with it
//  - the swap happens once and never reverses mid-session (a flapping server
//    would otherwise cost a reconnection every time)
//  - the swap is ordered *below* the relay's own refusals: 4003/4004/4008 are a
//    working relay answering, and are not a reason to change relay
//  - a host that accepts the socket and then says nothing still falls back —
//    no dial ever fails there, so only the welcome timer notices
//  - a custom TURN server is added ahead of, not instead of, what the relay
//    issues
//  - the shell's side: the settings are runtime prefs, validated, and the
//    page's CSP is rebuilt to name the custom host (or the socket is blocked
//    before it is ever made, and the fallback papers over a bug)

import fs from 'node:fs';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const here = path.dirname(fileURLToPath(import.meta.url));
const root = path.join(here, '..', '..');
const src = fs.readFileSync(path.join(root, 'public', 'app.js'), 'utf8');
const kt = fs.readFileSync(path.join(root, 'android', 'app', 'src', 'main', 'java',
  'com', 'tawny', 'monitor', 'MainActivity.kt'), 'utf8');
const lw = fs.readFileSync(path.join(root, 'android', 'app', 'src', 'main', 'java',
  'com', 'tawny', 'monitor', 'LocalWeb.kt'), 'utf8');

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

// The English strings, read out of public/i18n.js rather than duplicated here,
// so a reworded message cannot quietly drift away from what this asserts.
const i18n = fs.readFileSync(path.join(root, 'public', 'i18n.js'), 'utf8');
const en = (key) => {
  const m = i18n.match(new RegExp(`\\b${key}:\\s*'((?:[^'\\\\]|\\\\.)*)'`));
  if (!m) throw new Error('no such string: ' + key);
  return m[1].replace(/\\'/g, "'");
};

const DEFAULT = 'wss://tawny-rendezvous.example.workers.dev';
const CUSTOM = 'wss://relay.myhouse.example';

function ctx(cfg) {
  const said = [];
  const c = {
    console,
    setTimeout: () => 0, clearTimeout: () => {},
    S: { cfg: { ...cfg }, relayBase: null, ice: [], iceTimer: null },
    diag: (l) => said.push(String(l)),
    toast: (t) => said.push('toast: ' + t),
    // The user-facing strings moved behind TawnyT (public/i18n.js) and this
    // sandbox never grew a stub, so every run since has died on "TawnyT is not
    // defined" the moment fallBackToDefault() reached its toast. The probe was
    // silently dead, which is the worst state for a regression test to be in.
    // It resolves against the real English table, so an assertion about what
    // the user is actually told keeps testing the sentence they will read.
    TawnyT: { t: (k) => en(k) },
    fetchIce: () => said.push('fetchIce'),
    said,
  };
  vm.createContext(c);
  vm.runInContext([
    grabLine(/^const wsBase = .*$/m, 'wsBase'),
    grab('rendezvousBase'),
    grabLine(/^const fallbackBase = .*$/m, 'fallbackBase'),
    grabLine(/^const RELAY_HELLO_MS = .*$/m, 'RELAY_HELLO_MS'),
    grab('fallBackToDefault'),
    grab('iceServers'),
    'globalThis.fallbackBase = fallbackBase;',
    'globalThis.RELAY_HELLO_MS = RELAY_HELLO_MS;',
  ].join('\n'), c);
  return c;
}

const mkEntry = (base) => ({ tag: 'cloud', base, ws: null, retry: 3, refused: 2 });

// ---- nothing set: nothing changes ------------------------------------------
console.log('\nno custom relay');
{
  const c = ctx({ rendezvous: DEFAULT });
  check('the built-in relay is what gets dialled', c.rendezvousBase() === DEFAULT);
  check('there is nothing to fall back to', c.fallbackBase() === null);
  const e = mkEntry(DEFAULT);
  check('and no swap is attempted', c.fallBackToDefault(e, 'close 1006') === false);
  check('the entry is untouched', e.base === DEFAULT && !e.usingFallback);
}

// ---- a custom relay that will not answer -----------------------------------
console.log('\na custom relay that will not answer');
{
  const c = ctx({ rendezvous: CUSTOM, rendezvousFallback: DEFAULT });
  const e = mkEntry(CUSTOM);
  check("the user's relay is dialled first", c.rendezvousBase() === CUSTOM);
  check('the swap is taken', c.fallBackToDefault(e, 'close 1006') === true);
  check('the entry now points at the built-in tunnel', e.base === DEFAULT);
  check('the next dial is a swap, not a fresh failure', e.swapping === true);
  check('the retry counters are reset for the new host',
    e.retry === 0 && e.refused === 0);
  check('rendezvousBase() follows', c.rendezvousBase() === DEFAULT);
  check('TURN credentials are re-fetched from the relay now in use',
    c.said.includes('fetchIce'));
  check('the user is told, not left guessing',
    c.said.some((l) => l.startsWith('toast: ') && /relay/i.test(l)), c.said.join(' | '));
  check('it is logged for the flight recorder',
    c.said.some((l) => /falling back/i.test(l)));

  check('a second call is a no-op — the swap never reverses',
    c.fallBackToDefault(e, 'close 1006') === false && e.base === DEFAULT);
}

// ---- the swap is never a response to a real refusal ------------------------
console.log('\nordering against the relay\'s own refusals');
{
  // 4003 full / 4004 monitor already running / 4008 pairing expired are a relay
  // that is working and answering. In openSignal's onclose the fatal-code block
  // returns before the fallback line is reached.
  const close = src.slice(src.indexOf('ws.onclose = (ev) =>'));
  const fatal = close.indexOf('ev.code === 4003 || ev.code === 4004 || ev.code === 4008');
  const swap = close.indexOf('fallBackToDefault(entry, `close ${ev.code}`)');
  check('the fatal-code branch is reached first', fatal > -1 && swap > -1 && fatal < swap,
    `fatal@${fatal} swap@${swap}`);
  check('a purposeful swap short-circuits its own close',
    /if \(entry\.swapping\) \{ entry\.swapping = false; setTimeout\(dial, 250\); return; \}/.test(src));
  check('two failed dials, not one, before the swap',
    /canFallBack\(\) && entry\.retry >= 1 && fallBackToDefault/.test(src));
  check('only a cloud transport with a fallback may swap',
    /const canFallBack = \(\) => tag === 'cloud' && !entry\.usingFallback && !!fallbackBase\(\)/.test(src));
}

// ---- a host that accepts the socket and says nothing ------------------------
console.log('\na host that is not a Tawny relay');
{
  const c = ctx({ rendezvous: CUSTOM, rendezvousFallback: DEFAULT });
  check('the welcome timer is what catches it', c.RELAY_HELLO_MS >= 3000 && c.RELAY_HELLO_MS <= 20000,
    String(c.RELAY_HELLO_MS));
  check('it is armed on open, for a fallback-capable transport',
    /entry\.helloTimer = setTimeout\(\(\) => fallBackToDefault\(entry, 'no welcome'\), RELAY_HELLO_MS\)/.test(src));
  check('a welcome disarms it', /if \(m\.type === 'welcome'\) \{[\s\S]{0,120}clearTimeout\(entry\.helloTimer\)/.test(src));
  check('and closing the transport disarms it too',
    /function closeSignal\(entry\) \{[\s\S]{0,120}clearTimeout\(entry\.helloTimer\)/.test(src));
}

// ---- custom TURN sits on top, never instead --------------------------------
console.log('\ncustom STUN / TURN');
{
  const mine = { urls: ['turns:turn.myhouse.example:5349'], username: 'u', credential: 'p' };
  const c = ctx({ rendezvous: CUSTOM, rendezvousFallback: DEFAULT, stun: ['stun:a'], turn: [mine] });
  const before = c.iceServers();
  check('with no fetched credentials, the custom TURN is first',
    before[0] === mine && before.length === 2, JSON.stringify(before));
  c.S.ice = [{ urls: 'stun:b' }, { urls: 'turn:issued' }];
  const after = c.iceServers();
  check('with credentials issued, the custom TURN is still there and still first',
    after[0] === mine && after.length === 3, JSON.stringify(after));
  check('so a wrong custom TURN costs nothing — the issued relay is underneath',
    after.some((s) => s.urls === 'turn:issued'));
}

// ---- the shell ---------------------------------------------------------------
console.log('\nthe native shell');
{
  check('the settings are runtime prefs, not build config',
    /PREF_RENDEZVOUS = "srvRendezvous"/.test(kt) && /prefs\.getString\(PREF_RENDEZVOUS/.test(kt));
  check('a custom relay is preferred, the built-in one is never dropped',
    /fun preferredRendezvous\(\)[\s\S]{0,160}customRendezvous\(\)\.ifBlank \{ BuildConfig\.RENDEZVOUS_URL \}/.test(kt));
  check('the fallback is only sent when there is a custom relay to fall back FROM',
    /if \(custom\.isNotBlank\(\) && BuildConfig\.RENDEZVOUS_URL\.isNotBlank\(\)\)[\s\S]{0,120}put\("fallback"/.test(kt));
  check('addresses are validated before they are saved',
    /RELAY_URL_RE = Regex/.test(kt) && /STUN_URL_RE = Regex/.test(kt) && /TURN_URL_RE = Regex/.test(kt));
  check('an unparseable saved value is ignored rather than dialled',
    /takeIf \{ RELAY_URL_RE\.matches\(it\) \}\.orEmpty\(\)/.test(kt));
  check('it lives behind the diagnostics hatch, not in About',
    /fun showDiagnostics\(\)[\s\S]*?showServers\(\)/.test(kt) && !/showAbout[\s\S]{0,4000}showServers\(\)/.test(kt));

  // The CSP names the hosts the page may reach. A custom relay missing from it
  // is blocked before it gets a socket, which the fallback would then hide.
  check('the CSP is built from a host list, not a build constant',
    /fun connectSrc\([\s\S]{0,60}relayHosts: List<String>\)/.test(lw));
  check('the asset server carries that list',
    /class AssetHttpServer\([\s\S]{0,300}relayHosts: List<String>/.test(lw));
  check('and is rebuilt when the list changes',
    /assetServer\?\.let \{ if \(it\.relayHosts == hosts\) return it\.port/.test(kt));
  check('both the built-in and the custom host are listed',
    /relayHost\(BuildConfig\.RENDEZVOUS_URL\), relayHost\(customRendezvous\(\)\)/.test(kt));
}

console.log(fails ? `\n${fails} FAILURE(S)` : '\nall assertions passed');
process.exit(fails ? 1 : 0);
