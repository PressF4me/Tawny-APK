// Two Tailscale nodes advertising the same (or an overlapping) subnet route
// is an unsupported configuration — see
// https://tailscale.com/kb/1019/subnets#subnet-relay-with-overlapping-routes.
// Tailscale does not reject it: it picks a "primary" router per route and can
// flip between the two candidates, which from inside the house looks like
// every device's LAN traffic randomly stalling — reported to us as "the
// internet goes in a loop". A lot of people already run Tailscale with a
// router for their NAS, their Pi-hole, or a previous Tawny box, so this is
// the ordinary case for a second box on the same network, not a rare one.
//
// This module is the one place that decides "does advertising this route
// collide with something already on the tailnet" — used both from
// docker/entrypoint.sh (a boot-time decision: advertise, or stay quiet) and
// from server.js (a live one: what does /setup tell the operator right now,
// and what does the "advertise anyway" button actually check).

/** Parse "a.b.c.d/n" into its [network, broadcast] integers, or null. */
export function cidrRange(cidr) {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})\/(\d{1,2})$/.exec(String(cidr || ''));
  if (!m) return null;
  const octets = m.slice(1, 5).map(Number);
  const bits = Number(m[5]);
  if (octets.some((o) => o < 0 || o > 255) || bits < 0 || bits > 32) return null;
  const ip = octets.reduce((a, o) => (a << 8) + o, 0) >>> 0;
  const mask = bits === 0 ? 0 : (0xFFFFFFFF << (32 - bits)) >>> 0;
  const network = (ip & mask) >>> 0;
  const broadcast = (network | (~mask >>> 0)) >>> 0;
  return { network, broadcast };
}

export function cidrsOverlap(a, b) {
  const ra = cidrRange(a);
  const rb = cidrRange(b);
  if (!ra || !rb) return false;
  return ra.network <= rb.broadcast && rb.network <= ra.broadcast;
}

/**
 * Which of `routes` (CIDR strings this node is about to advertise, or already
 * does) are covered — wholly or in part — by a route some *other* peer in the
 * tailnet already carries. `peers` is `Object.values(status.Peer || {})` from
 * `tailscale status --json`: each peer's AllowedIPs includes its own approved
 * subnet routes alongside its bare Tailscale address, so a /32 (a host, not a
 * route) is excluded, as is anything that isn't an IPv4 CIDR.
 */
export function findRouteConflicts(peers, routes) {
  const mine = (routes || [])
    .map((r) => ({ cidr: r, range: cidrRange(r) }))
    .filter((r) => r.range);
  if (!mine.length) return [];

  const conflicts = [];
  for (const peer of peers || []) {
    const name = (peer && (peer.HostName || String(peer.DNSName || '').replace(/\.$/, ''))) ||
      'another device on your tailnet';
    for (const cidr of (peer && peer.AllowedIPs) || []) {
      if (typeof cidr !== 'string' || cidr.includes(':') || cidr.endsWith('/32')) continue;
      const range = cidrRange(cidr);
      if (!range) continue;
      for (const m of mine) {
        if (cidrsOverlap(m.cidr, cidr)) conflicts.push({ route: m.cidr, peer: name, peerRoute: cidr });
      }
      void range;
    }
  }
  return conflicts;
}

// CLI mode, for docker/entrypoint.sh, which has no easy way to hold a JS
// object across the shell/node boundary otherwise:
//   node docker/route-conflict.js <routes-comma-separated> < status.json
// Prints a JSON array (possibly empty) of conflicts to stdout.
if (import.meta.url === `file://${process.argv[1]}`) {
  const routes = String(process.argv[2] || '').split(',').map((s) => s.trim()).filter(Boolean);
  let raw = '';
  process.stdin.on('data', (d) => { raw += d; });
  process.stdin.on('end', () => {
    let peers = [];
    try { peers = Object.values(JSON.parse(raw).Peer || {}); } catch { /* no status yet */ }
    process.stdout.write(JSON.stringify(findRouteConflicts(peers, routes)));
  });
}
