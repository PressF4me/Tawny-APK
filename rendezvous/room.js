// Tawny rendezvous — one Durable Object per channel room.
//
// A faithful reduction of server.js's signaling relay: it introduces peers and
// forwards addressed control messages, and it never sees media or the channel
// key. The room id is sha256("tawny-room-v1|" + key) truncated to 32 hex,
// computed on the device — this DO only ever sees that opaque id and a
// per-pairing admission ticket.
//
// Admission (fail-closed): every socket must send {type:'hello'} as its first
// frame before it is joined to the room or told about anyone. A Viewer's hello
// must carry a ticket `t` whose sha256 matches the one the Monitor registered
// (in its own hello's `hashT`). No ticket ⇒ no admission. A Monitor that wants
// to *change* the registered ticket must also prove it holds the channel key,
// with `a` = sha256("tawny-auth-v1|" + key).
//
// Uses the WebSocket Hibernation API so an idle room costs nothing.

const MAX_PER_ROOM = 6;      // 1 Watcher + up to 5 Handhelds
const MAX_STATIONS = 1;
// Addressed control messages the relay will forward. `cameras`, `meta` and
// `camera-control` were missing, so the lens picker, remote zoom and pet-name
// sync were sent by the client, dropped here, and never arrived.
const RELAY = new Set([
  'offer', 'answer', 'ice', 'bye', 'chime', 'chime-ack', 'talking',
  'cameras', 'meta', 'camera-control'
]);
const TICKET_TTL_MS = 24 * 60 * 60 * 1000;
const HEX64 = /^[a-f0-9]{64}$/;
// A socket that connects and never says hello held a slot forever: pending
// sockets are excluded from members(), so MAX_PER_ROOM never stopped them.
const ADMIT_TIMEOUT_MS = 10_000;
// Signalling frames are a few KB. Anything larger is someone filling memory.
const MAX_MSG = 64 * 1024;
// Wrong answers, per room, before this room stops entertaining new sockets.
const MAX_FAILED_ADMITS = 20;
const FAIL_WINDOW_MS = 10 * 60 * 1000;

const hex = (buf) =>
  [...new Uint8Array(buf)].map((b) => b.toString(16).padStart(2, '0')).join('');
async function sha256Hex(s) {
  return hex(await crypto.subtle.digest('SHA-256', new TextEncoder().encode(String(s ?? ''))));
}
function send(ws, obj) {
  try { if (ws.readyState === 1 || ws.readyState === undefined) ws.send(JSON.stringify(obj)); } catch {}
}

export class Room {
  constructor(state, env) {
    this.state = state;
    this.env = env;
  }

  async ticket() {
    const rec = await this.state.storage.get('ticket');
    return rec && Date.now() <= rec.exp ? rec : null;
  }

  /**
   * Count a rejected admission. A room that is being probed stops accepting
   * new sockets for a while — strongly consistent, unlike the KV counter in the
   * Worker, because it lives in the one object that owns this room.
   */
  async noteFailure() {
    const now = Date.now();
    const f = (await this.state.storage.get('fails')) || { n: 0, since: now };
    if (now - f.since > FAIL_WINDOW_MS) { f.n = 0; f.since = now; }
    f.n += 1;
    await this.state.storage.put('fails', f);
  }

  async tooManyFailures() {
    const f = await this.state.storage.get('fails');
    if (!f) return false;
    if (Date.now() - f.since > FAIL_WINDOW_MS) return false;
    return f.n >= MAX_FAILED_ADMITS;
  }

  async fetch(request) {
    const url = new URL(request.url);

    // Worker asks: is this ticket good for this room? (used to gate /turn)
    if (url.pathname === '/verify') {
      const rec = await this.ticket();
      const ok = rec && (await sha256Hex(url.searchParams.get('t'))) === rec.hashT;
      return new Response(null, { status: ok ? 200 : 403 });
    }

    if (request.headers.get('Upgrade') !== 'websocket') {
      return new Response('expected websocket', { status: 400 });
    }
    const role = url.searchParams.get('role') === 'station' ? 'station' : 'viewer';

    const pair = new WebSocketPair();
    const [client, server] = [pair[0], pair[1]];
    // pending: joined to the socket set but not yet admitted to the room.
    server.serializeAttachment({ role, pending: true, since: Date.now() });
    this.state.acceptWebSocket(server);
    // Close anything still unadmitted when the next alarm runs.
    const due = Date.now() + ADMIT_TIMEOUT_MS;
    const cur = await this.state.storage.getAlarm();
    if (cur === null || cur > due) await this.state.storage.setAlarm(due);
    return new Response(null, { status: 101, webSocket: client });
  }

  members() {
    return this.state.getWebSockets().filter((w) => !w.deserializeAttachment()?.pending);
  }

  async webSocketMessage(ws, raw) {
    if (typeof raw === 'string' ? raw.length > MAX_MSG : raw.byteLength > MAX_MSG) {
      ws.close(4009, 'message too large'); return;
    }
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    if (!msg || typeof msg !== 'object') return;
    const meta = ws.deserializeAttachment();
    if (!meta) return;

    // ---- admission ----
    //
    // Order matters here, and it did not used to. The ticket was re-written
    // before the room's capacity and single-Monitor checks ran, so an attacker
    // who knew only the room id could connect, be turned away with 4004
    // "monitor already running" — and still have re-keyed the channel on the
    // way out, locking every paired Viewer to 4008 until the 24h TTL expired.
    // Nothing that gets rejected may change stored state.
    if (meta.pending) {
      if (msg.type !== 'hello') { ws.close(4000, 'expected hello'); return; }
      if (await this.tooManyFailures()) { ws.close(4029, 'too many attempts'); return; }

      const rec = await this.ticket();
      const here = this.members();

      // 1. Capacity first — a refused socket must be a no-op.
      if (here.length >= MAX_PER_ROOM) { ws.close(4003, 'channel full'); return; }
      if (meta.role === 'station' &&
          here.filter((w) => w.deserializeAttachment()?.role === 'station').length >= MAX_STATIONS) {
        ws.close(4004, 'monitor already running'); return;
      }

      // 2. Then prove admission.
      //
      // `a` is sha256("tawny-auth-v1|" + channel key) — a second, independent
      // hash of the key under a different domain separator. It proves the
      // sender holds the key without revealing it, and unlike the room id it
      // cannot be learned by watching traffic: the room id travels in the
      // WebSocket URL, is written to the on-device diagnostics log, and until
      // this release went out in clear text on the LAN.
      //
      // It is stored with the ticket and expires with it, so a room whose
      // Monitor never comes back resets on its own rather than staying claimed
      // by whoever spoke first.
      let rekey = null;
      if (meta.role === 'viewer') {
        if (!rec || (await sha256Hex(msg.t)) !== rec.hashT) {
          await this.noteFailure();
          ws.close(4008, 'pairing expired'); return;
        }
      } else {
        const claimed = typeof msg.a === 'string' && HEX64.test(msg.a) ? msg.a : null;
        if (rec?.auth && claimed && claimed !== rec.auth) {
          await this.noteFailure();
          ws.close(4008, 'wrong channel key'); return;
        }
        // A channel this build has claimed cannot be re-keyed by a caller that
        // cannot prove the key. Older shells are still admitted below on a
        // ticket matching what is already stored.
        const mayRekey = claimed !== null || !rec?.auth;
        const hashT = typeof msg.hashT === 'string' && HEX64.test(msg.hashT) ? msg.hashT : null;

        if (hashT && mayRekey) {
          // Deferred: applied only once this socket is actually admitted.
          rekey = { hashT, auth: claimed || rec?.auth || null };
        } else if (rec) {
          if ((await sha256Hex(msg.t)) !== rec.hashT) {
            await this.noteFailure();
            ws.close(4008, 'pairing expired'); return;
          }
        } else {
          await this.noteFailure();
          ws.close(4008, 'no pairing ticket'); return;
        }
      }

      // 3. Admitted. Only now may stored state change.
      if (rekey && (!rec || rec.hashT !== rekey.hashT || rec.auth !== rekey.auth)) {
        await this.state.storage.put('ticket', {
          hashT: rekey.hashT, auth: rekey.auth, exp: Date.now() + TICKET_TTL_MS,
        });
        await this.state.storage.setAlarm(Date.now() + TICKET_TTL_MS + 60_000);
      }

      const id = hex(crypto.getRandomValues(new Uint8Array(6)));
      ws.serializeAttachment({ id, role: meta.role });
      send(ws, {
        type: 'welcome', id, role: meta.role,
        peers: here.map((w) => w.deserializeAttachment()).filter(Boolean)
          .map((m) => ({ id: m.id, role: m.role }))
      });
      for (const w of here) send(w, { type: 'peer-joined', id, role: meta.role });
      return;
    }

    // ---- relay ----
    if (!RELAY.has(msg.type) || typeof msg.to !== 'string') return;
    const target = this.members().find((w) => w.deserializeAttachment()?.id === msg.to);
    if (!target || target === ws) return;
    msg.from = meta.id;
    send(target, msg);
  }

  webSocketClose(ws) { this.announceLeft(ws); }
  webSocketError(ws) { this.announceLeft(ws); }

  announceLeft(ws) {
    const meta = ws.deserializeAttachment();
    if (!meta?.id) return;   // pending socket never joined
    for (const w of this.members()) {
      if (w !== ws) send(w, { type: 'peer-left', id: meta.id });
    }
  }

  async alarm() {
    // Sweep sockets that connected and never said hello.
    const cutoff = Date.now() - ADMIT_TIMEOUT_MS;
    for (const w of this.state.getWebSockets()) {
      const m = w.deserializeAttachment();
      if (m?.pending && (m.since || 0) < cutoff) {
        try { w.close(4008, 'no hello'); } catch {}
      }
    }

    const rec = await this.state.storage.get('ticket');
    if (!rec) return;
    // A Monitor that is plugged in and left alone — the whole point of the
    // product — registers its ticket once and never says hello again. Expiring
    // it out from under a live Monitor locked out every new Handheld with 4008
    // until someone restarted the phone. While the Monitor is here, the pairing
    // is by definition still current, so roll it forward instead.
    const stationHere = this.members()
      .some((w) => w.deserializeAttachment()?.role === 'station');
    if (stationHere) {
      rec.exp = Date.now() + TICKET_TTL_MS;
      await this.state.storage.put('ticket', rec);
      await this.state.storage.setAlarm(Date.now() + TICKET_TTL_MS + 60_000);
      return;
    }
    if (Date.now() > rec.exp) await this.state.storage.delete('ticket');
  }
}
