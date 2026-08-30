// Tawny rendezvous — diagnostic report intake.
//
//   POST /report            store one report (from the app's "Send to Tawny")
//   GET  /report/pull?key=  read them back (protected); &drain=1 deletes them
//
// Reports are the flight-recorder log from the in-app diagnostics hatch plus a
// few device facts. The log already only holds hashed room ids — never keys,
// tickets, or anything that identifies a person. Stored in Workers KV with a
// 30-day TTL so old ones clean themselves up.
//
// Set up:
//   wrangler kv namespace create REPORTS        # paste id into wrangler.toml
//   wrangler secret put REPORT_KEY              # long random string
// With REPORTS unbound, POST /report just 404s and the app falls back to its
// share sheet — nothing breaks.

const MAX_BODY = 96 * 1024;        // a fat diag log is a few KB; this is slack
const TTL_SECONDS = 30 * 24 * 3600;
const HEX = (n) => [...crypto.getRandomValues(new Uint8Array(n))]
  .map((b) => b.toString(16).padStart(2, '0')).join('');

const j = (obj, status = 200, headers = {}) =>
  new Response(JSON.stringify(obj), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', ...headers },
  });

function clip(v, max) {
  return (typeof v === 'string' ? v : String(v ?? '')).slice(0, max);
}

export async function postReport(request, env) {
  if (!env.REPORTS) return j({ error: 'reports not configured' }, 404);

  const len = Number(request.headers.get('content-length') || 0);
  if (len > MAX_BODY) return j({ error: 'too large' }, 413);

  let body;
  try { body = await request.json(); } catch { return j({ error: 'bad json' }, 400); }
  if (!body || typeof body !== 'object') return j({ error: 'bad json' }, 400);

  const log = clip(body.log, MAX_BODY);
  if (!log.trim()) return j({ error: 'empty' }, 400);

  const rec = {
    ts: new Date().toISOString(),
    v: clip(body.v, 32),                 // app versionName
    c: clip(body.c, 16),                 // app versionCode
    model: clip(body.model, 64),         // device model
    android: clip(body.android, 16),     // Android release
    id: clip(body.id, 32) || HEX(6),     // client-side report id (dedupe aid)
    ip: (request.headers.get('CF-Connecting-IP') || '').split(',')[0].trim(),
    country: request.headers.get('CF-IPCountry') || '',
    log,
  };

  const key = `report:${Date.now()}:${HEX(4)}`;
  await env.REPORTS.put(key, JSON.stringify(rec), { expirationTtl: TTL_SECONDS });
  return j({ ok: true });
}

export async function pullReports(request, env, url) {
  if (!env.REPORTS) return j({ error: 'reports not configured' }, 404);
  if (!env.REPORT_KEY) return j({ error: 'no report key set' }, 500);

  // Constant-time-ish compare on a shared secret in the query string. It is a
  // read key for low-value data, not a credential that protects anything.
  const given = url.searchParams.get('key') || '';
  if (given.length !== env.REPORT_KEY.length) return j({ error: 'forbidden' }, 403);
  let diff = 0;
  for (let i = 0; i < given.length; i++) diff |= given.charCodeAt(i) ^ env.REPORT_KEY.charCodeAt(i);
  if (diff !== 0) return j({ error: 'forbidden' }, 403);

  const drain = url.searchParams.get('drain') === '1';
  const out = [];
  let cursor;
  do {
    const page = await env.REPORTS.list({ prefix: 'report:', cursor, limit: 1000 });
    for (const k of page.keys) {
      const v = await env.REPORTS.get(k.name);
      if (v) out.push(v);
      if (drain) await env.REPORTS.delete(k.name);
    }
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);

  // NDJSON — one report per line, newest last (keys are time-prefixed).
  return new Response(out.join('\n') + (out.length ? '\n' : ''), {
    headers: {
      'content-type': 'application/x-ndjson; charset=utf-8',
      'cache-control': 'no-store',
      'x-report-count': String(out.length),
    },
  });
}
