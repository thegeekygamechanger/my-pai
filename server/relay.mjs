// Pocket Agent sync relay.
//
// Devices that share a pairing code meet in a "room" and pass each other encrypted messages through
// this process. It is a switchboard and nothing more:
//   - it keeps NOTHING: no database, no files, no message log. A message exists here only for the
//     moment it takes to hand it to the other devices that are connected right now;
//   - it cannot read anything: messages are encrypted on the devices with a key derived from the
//     pairing code, which never comes here. The room name is a one-way hash of that code;
//   - it has no accounts and no dependencies: one file, plain Node.
//
//   GET  /v1/r/<room>/s?d=<device>   a long-lived event stream of what the others send
//   POST /v1/r/<room>/p?d=<device>   one message for the others (opaque bytes)
//   GET  /healthz
//
// Settings (environment): PORT (8790), HOST (127.0.0.1), MAX_ROOM (5 devices), MAX_BODY (262144 bytes),
// MAX_ROOMS (20000), MAX_PER_IP (40 open streams from one address).

import http from 'node:http';

const PORT = Number(process.env.PORT || 8790);
const HOST = process.env.HOST || '127.0.0.1';
const MAX_ROOM = Number(process.env.MAX_ROOM || 5);
const MAX_BODY = Number(process.env.MAX_BODY || 262144);
const MAX_ROOMS = Number(process.env.MAX_ROOMS || 20000);
const MAX_PER_IP = Number(process.env.MAX_PER_IP || 40);
// A device that cannot keep up is dropped rather than letting its backlog grow in memory.
const MAX_BACKLOG = 12 * 1024 * 1024;
// Per room: at most this many bytes and posts in any 10 seconds.
const RATE_BYTES = 24 * 1024 * 1024;
const RATE_POSTS = 600;

/** room -> Map(device -> {res, ip}) */
const rooms = new Map();
/** room -> {t, bytes, posts} */
const rates = new Map();
const perIp = new Map();

const ROOM = /^[0-9a-f]{64}$/;
const DEVICE = /^[A-Za-z0-9-]{8,64}$/;

function ipOf(req) {
  // Behind nginx and Cloudflare the real address is in a header; fall back to the socket.
  return String(req.headers['cf-connecting-ip'] || req.headers['x-real-ip'] || req.socket.remoteAddress || '');
}

function reply(res, status, body) {
  const text = body === undefined ? '' : JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json', 'cache-control': 'no-store', 'content-length': Buffer.byteLength(text) });
  res.end(text);
}

function peers(room) {
  const members = rooms.get(room);
  if (!members) return;
  const line = `event: p\ndata: ${JSON.stringify({ ids: [...members.keys()] })}\n\n`;
  for (const m of members.values()) m.res.write(line);
}

function leave(room, device, res) {
  const members = rooms.get(room);
  if (!members) return;
  const m = members.get(device);
  if (!m || m.res !== res) return;
  members.delete(device);
  perIp.set(m.ip, Math.max(0, (perIp.get(m.ip) || 1) - 1));
  if (perIp.get(m.ip) === 0) perIp.delete(m.ip);
  if (members.size === 0) { rooms.delete(room); rates.delete(room); } else peers(room);
}

function stream(req, res, room, device) {
  const ip = ipOf(req);
  let members = rooms.get(room);
  if (!members) {
    if (rooms.size >= MAX_ROOMS) return reply(res, 503, { error: 'busy' });
    members = new Map();
    rooms.set(room, members);
  }
  const old = members.get(device);
  if (!old && members.size >= MAX_ROOM) return reply(res, 409, { error: 'room full' });
  if (!old && (perIp.get(ip) || 0) >= MAX_PER_IP) return reply(res, 429, { error: 'too many connections' });
  // The same device connecting again (after a network change) replaces its old stream.
  if (old) { members.delete(device); perIp.set(old.ip, Math.max(0, (perIp.get(old.ip) || 1) - 1)); old.res.end(); }
  res.writeHead(200, {
    'content-type': 'text/event-stream', 'cache-control': 'no-store, no-transform', connection: 'keep-alive',
    'x-accel-buffering': 'no',
  });
  res.write(': ok\n\n');
  members.set(device, { res, ip });
  perIp.set(ip, (perIp.get(ip) || 0) + 1);
  req.on('close', () => leave(room, device, res));
  peers(room);
}

function post(req, res, room, device) {
  const members = rooms.get(room);
  // Only a device that is in the room may speak in it.
  if (!members || !members.has(device)) return reply(res, 403, { error: 'not in room' });
  const now = Date.now();
  let rate = rates.get(room);
  if (!rate || now - rate.t > 10000) { rate = { t: now, bytes: 0, posts: 0 }; rates.set(room, rate); }
  if (rate.posts >= RATE_POSTS || rate.bytes >= RATE_BYTES) return reply(res, 429, { error: 'slow down' });
  rate.posts++;
  const chunks = [];
  let size = 0;
  let refused = false;
  req.on('data', (c) => {
    if (refused) return;
    size += c.length;
    if (size > MAX_BODY) { refused = true; reply(res, 413, { error: 'too large' }); req.destroy(); return; }
    chunks.push(c);
  });
  req.on('end', () => {
    if (refused) return;
    rate.bytes += size;
    const line = `event: m\ndata: ${Buffer.concat(chunks).toString('base64')}\n\n`;
    let n = 0;
    for (const [id, m] of members) {
      if (id === device) continue;
      if (m.res.writableLength > MAX_BACKLOG) { m.res.destroy(); continue; }
      m.res.write(line);
      n++;
    }
    reply(res, 200, { n });
  });
  req.on('error', () => {});
}

const server = http.createServer((req, res) => {
  try {
    const url = new URL(req.url, 'http://x');
    if (url.pathname === '/healthz') return reply(res, 200, { ok: true, rooms: rooms.size });
    const m = /^\/v1\/r\/([^/]+)\/(s|p)$/.exec(url.pathname);
    const device = url.searchParams.get('d') || '';
    if (!m || !ROOM.test(m[1]) || !DEVICE.test(device)) return reply(res, 404, { error: 'not found' });
    if (m[2] === 's' && req.method === 'GET') return stream(req, res, m[1], device);
    if (m[2] === 'p' && req.method === 'POST') return post(req, res, m[1], device);
    return reply(res, 405, { error: 'method' });
  } catch {
    try { reply(res, 400, { error: 'bad request' }); } catch { /* the connection is gone */ }
  }
});

// A heartbeat keeps idle streams open through proxies and lets clients notice a dead line.
setInterval(() => {
  for (const members of rooms.values()) for (const m of members.values()) m.res.write(': hb\n\n');
}, 20000).unref();

server.headersTimeout = 20000;
server.requestTimeout = 0;
server.keepAliveTimeout = 65000;
server.listen(PORT, HOST, () => console.log(`pocket-agent relay on ${HOST}:${PORT}`));
