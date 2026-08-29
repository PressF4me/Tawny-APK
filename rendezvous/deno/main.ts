// Tawny rendezvous — Deno Deploy alternative to the Cloudflare Worker.
//
// SECONDARY target. There is no per-room actor and no durable storage here, so
// room membership and the admission ticket live in module scope:
//   * Pin the Deno Deploy project to ONE region (Settings → Region).
//   * Relay fan-out is mirrored across the region's isolates via BroadcastChannel;
//     ticket registration and membership are NOT — under light traffic Deno keeps
//     one warm isolate per region, which is the assumption. For anything heavier,
//     use the Cloudflare Worker (../worker.js + ../room.js).
//
// Same wire protocol as room.js: a socket must send {type:'hello'} first and
// pass admission before it is joined or told about anyone. A Handheld's hello
// carries the ticket `t`; the Watcher's carries `hashT = sha256(t)`.
//
// Env: STUN_URLS, TURN_MODE, ALLOWED_ORIGINS, TURN_STATIC_SECRET, TURN_URLS

const ROOM_RE = /^[a-f0-9]{32}$/;
const TICKET_RE = /^[A-Za-z0-9_-]{8,64}$/;
const HEX64 = /^[a-f0-9]{64}$/;
// "cameras", "meta" and "camera-control" were missing here — the same bug that
// made the lens picker, remote zoom and pet-name sync inert on the other relays.
const RELAY = new Set([
  "offer", "answer", "ice", "bye", "chime", "chime-ack", "talking",
  "cameras", "meta", "camera-control",
]);
const MAX_PER_ROOM = 6;
const MAX_STATIONS = 1;
const TICKET_TTL = 24 * 60 * 60_000;

const env = (k: string) => Deno.env.get(k) ?? "";
const list = (v: string) => v.split(",").map((s) => s.trim()).filter(Boolean);

type Peer = { id: string; role: string; ws: WebSocket };
const rooms = new Map<string, Map<string, Peer>>();
const tickets = new Map<string, { hashT: string; exp: number }>();

const bus = new BroadcastChannel("tawny");
bus.onmessage = (e) => fanout(e.data, true);

function wsSend(ws: WebSocket, obj: unknown) {
  try { if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj)); } catch { /* gone */ }
}

// Deliver a relay/announce to local peers, and (unless it came from the bus)
// mirror it to the other isolates in this region.
function fanout(m: any, fromBus = false) {
  const peers = rooms.get(m.room);
  if (peers) {
    if (m.kind === "relay") {
      const t = peers.get(m.to);
      if (t) wsSend(t.ws, m.msg);
    } else if (m.kind === "announce") {
      for (const p of peers.values()) if (p.id !== m.except) wsSend(p.ws, m.msg);
    }
  }
  if (!fromBus) bus.postMessage(m);
}

async function sha256hex(s: string) {
  const d = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(String(s ?? "")));
  return [...new Uint8Array(d)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
function ticketFor(room: string) {
  const rec = tickets.get(room);
  return rec && Date.now() <= rec.exp ? rec : null;
}

function cors(extra: Record<string, string> = {}) {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET, OPTIONS",
    "access-control-allow-headers": "content-type",
    ...extra,
  };
}
const json = (o: unknown, status = 200, headers: Record<string, string> = {}) =>
  new Response(JSON.stringify(o), {
    status,
    headers: { "content-type": "application/json; charset=utf-8", "cache-control": "no-store", ...headers },
  });

function originOk(req: Request) {
  const o = req.headers.get("Origin");
  if (!o || o === "null") return true;
  try {
    const h = new URL(o);
    if (h.hostname === "127.0.0.1" || h.hostname === "localhost") return true;
    if (h.host === new URL(req.url).host) return true;
    return list(env("ALLOWED_ORIGINS")).includes(o);
  } catch {
    return false;
  }
}

async function turnCreds() {
  const secret = env("TURN_STATIC_SECRET");
  const urls = list(env("TURN_URLS"));
  if (!secret || !urls.length) return null;
  const ttl = 3600;
  const username = String(Math.floor(Date.now() / 1000) + ttl);
  const key = await crypto.subtle.importKey(
    "raw", new TextEncoder().encode(secret),
    { name: "HMAC", hash: "SHA-1" }, false, ["sign"],
  );
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(username));
  const credential = btoa(String.fromCharCode(...new Uint8Array(sig)));
  return { iceServers: [{ urls, username, credential }], ttl };
}

Deno.serve(async (req) => {
  const url = new URL(req.url);

  if (req.method === "OPTIONS") return new Response(null, { headers: cors() });
  if (url.pathname === "/healthz") return json({ ok: true });
  if (url.pathname === "/config.json") {
    return json(
      { stun: list(env("STUN_URLS")), turnMode: env("TURN_MODE") || "auto", authRequired: false },
      200, cors(),
    );
  }
  if (url.pathname === "/turn") {
    const room = url.searchParams.get("room") || "";
    if (!ROOM_RE.test(room)) return json({ error: "bad room" }, 400, cors());
    const rec = ticketFor(room);
    if (!rec || (await sha256hex(url.searchParams.get("t") || "")) !== rec.hashT) {
      return json({ error: "not paired" }, 403, cors());
    }
    const c = await turnCreds();
    return c ? json(c, 200, cors()) : json({ error: "no turn configured" }, 404, cors());
  }
  if (url.pathname !== "/ws") return new Response("not found", { status: 404 });

  if (req.headers.get("upgrade")?.toLowerCase() !== "websocket") {
    return new Response("expected websocket", { status: 426 });
  }
  if (!originOk(req)) return new Response("forbidden", { status: 403 });

  const room = url.searchParams.get("room") || "";
  const role = url.searchParams.get("role") === "station" ? "station" : "viewer";
  if (!ROOM_RE.test(room)) return new Response("bad room", { status: 400 });

  const { socket, response } = Deno.upgradeWebSocket(req);
  const id = crypto.randomUUID().slice(0, 12);
  let joined = false;

  const admit = async (msg: any) => {
    const rec = ticketFor(room);
    if (role === "viewer") {
      if (!rec || (await sha256hex(msg.t)) !== rec.hashT) { socket.close(4008, "pairing expired"); return; }
    } else {
      if (rec) {
        if ((await sha256hex(msg.t)) !== rec.hashT) { socket.close(4008, "pairing expired"); return; }
      } else if (typeof msg.hashT === "string" && HEX64.test(msg.hashT)) {
        tickets.set(room, { hashT: msg.hashT, exp: Date.now() + TICKET_TTL });
      } else {
        socket.close(4008, "no pairing ticket"); return;
      }
    }

    const peers = rooms.get(room) ?? new Map<string, Peer>();
    rooms.set(room, peers);
    if (peers.size >= MAX_PER_ROOM) { socket.close(4003, "channel full"); return; }
    if (role === "station" && [...peers.values()].some((p) => p.role === "station")) {
      socket.close(4004, "monitor already running"); return;
    }

    joined = true;
    peers.set(id, { id, role, ws: socket });
    socket.send(JSON.stringify({
      type: "welcome", id, role,
      peers: [...peers.values()].filter((p) => p.id !== id).map((p) => ({ id: p.id, role: p.role })),
    }));
    fanout({ room, kind: "announce", except: id, msg: { type: "peer-joined", id, role } });
  };

  socket.onmessage = async (e) => {
    let msg: any;
    try { msg = JSON.parse(e.data); } catch { return; }
    if (!msg || typeof msg !== "object") return;
    if (!joined) {
      if (msg.type !== "hello") { socket.close(4000, "expected hello"); return; }
      await admit(msg);
      return;
    }
    if (!RELAY.has(msg.type) || typeof msg.to !== "string") return;
    msg.from = id;
    fanout({ room, kind: "relay", to: msg.to, msg });
  };
  const gone = () => {
    if (!joined) return;
    rooms.get(room)?.delete(id);
    fanout({ room, kind: "announce", except: id, msg: { type: "peer-left", id } });
    if (!rooms.get(room)?.size) rooms.delete(room);
  };
  socket.onclose = gone;
  socket.onerror = gone;

  return response;
});

setInterval(() => {
  const now = Date.now();
  for (const [room, rec] of tickets) if (now > rec.exp) tickets.delete(room);
}, 60_000);
