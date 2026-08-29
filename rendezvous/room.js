// Tawny rendezvous — one Durable Object per channel room.
//
// A faithful reduction of server.js's signaling relay: it introduces peers and
// forwards addressed control messages, and it never sees media or the channel
// key. The room id is sha256("tawny-room-v1|" + key) truncated to 32 hex,
// computed on the device — this DO only ever sees that opaque id and a
// per-pairing admission ticket.
//
// Admission (fail-closed): every socket must send {type:'hello'} as its first
// frame before it is joined to the room or told about anyone. A Handheld's hello
// must carry a ticket `t` whose sha256 matches the one the Watcher registered
// (in its own hello's `hashT`). No ticket ⇒ no admission.
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
    server.serializeAttachment({ role, pending: true });
    this.state.acceptWebSocket(server);
    return new Response(null, { status: 101, webSocket: client });
  }

  members() {
    return this.state.getWebSockets().filter((w) => !w.deserializeAttachment()?.pending);
  }

  async webSocketMessage(ws, raw) {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }
    if (!msg || typeof msg !== 'object') return;
    const meta = ws.deserializeAttachment();
    if (!meta) return;

    // ---- admission ----
    if (meta.pending) {
      if (msg.type !== 'hello') { ws.close(4000, 'expected hello'); return; }
      const rec = await this.ticket();

      if (meta.role === 'viewer') {
        if (!rec || (await sha256Hex(msg.t)) !== rec.hashT) { ws.close(4008, 'pairing expired'); return; }
      } else { // station
        // The Monitor is the authority on the current ticket: a hello carrying a
        // well-formed hashT always (re)writes the record.
        //
        // This used to be write-once for TICKET_TTL_MS, which bricked a channel
        // for a full day whenever the Monitor's ticket changed under it — an app
        // data wipe, "Start over", a re-pair, or a stale record from an earlier
        // install. Neither side could ever satisfy the stored hash again, so the
        // Monitor got 4008 and every Handheld off the LAN saw "Monitor isn't on
        // yet", with no way to recover except waiting out the TTL.
        //
        // It gives nothing away: the room id is sha256("tawny-room-v1|" + key),
        // so anyone who can address this room already holds the channel key —
        // the ticket was never what kept them out.
        if (typeof msg.hashT === 'string' && HEX64.test(msg.hashT)) {
          if (!rec || rec.hashT !== msg.hashT) {
            await this.state.storage.put('ticket', { hashT: msg.hashT, exp: Date.now() + TICKET_TTL_MS });
            await this.state.storage.setAlarm(Date.now() + TICKET_TTL_MS + 60_000);
          }
        } else if (rec) {
          // Older shells send only `t`; honour it against the stored hash.
          if ((await sha256Hex(msg.t)) !== rec.hashT) { ws.close(4008, 'pairing expired'); return; }
        } else {
          ws.close(4008, 'no pairing ticket'); return;
        }
      }

      const here = this.members();
      if (here.length >= MAX_PER_ROOM) { ws.close(4003, 'channel full'); return; }
      if (meta.role === 'station' &&
          here.some((w) => w.deserializeAttachment()?.role === 'station')) {
        ws.close(4004, 'monitor already running'); return;
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
