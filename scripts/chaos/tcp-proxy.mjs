// Chaos helper: a plain TCP forwarder (Plan 11.2). Putting it between a WebSocket client and the API lets the chaos
// script drop the WebSocket path (kill this process: every socket through it is cut) without touching the API.
//   node scripts/chaos/tcp-proxy.mjs --listen 8281 --target 8181
import net from 'node:net';

const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, v, i, a) => (v.startsWith('--') ? [...acc, [v.slice(2), a[i + 1]]] : acc), []),
);
const listen = Number(args.listen);
const target = Number(args.target);
net
  .createServer((client) => {
    const upstream = net.connect(target, '127.0.0.1');
    client.pipe(upstream);
    upstream.pipe(client);
    const end = () => {
      client.destroy();
      upstream.destroy();
    };
    client.on('error', end);
    upstream.on('error', end);
    client.on('close', end);
    upstream.on('close', end);
  })
  .listen(listen, '127.0.0.1', () => console.log(`proxy 127.0.0.1:${listen} -> 127.0.0.1:${target}`));
