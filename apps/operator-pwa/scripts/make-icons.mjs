// Generates the PWA icons from code (no external assets): a navy tile with a ring and a check mark.
// Usage: node scripts/make-icons.mjs   (writes public/icons/*.png and public/favicon.svg)
// Pure Node: a tiny rasteriser (2x2 supersampling) and a PNG writer on node:zlib.
import { Buffer } from 'node:buffer';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import process from 'node:process';
import { fileURLToPath } from 'node:url';
import { crc32, deflateSync } from 'node:zlib';

const root = join(dirname(fileURLToPath(import.meta.url)), '..', 'public');
const NAVY = [0x0b, 0x1b, 0x34];
const WHITE = [0xff, 0xff, 0xff];
const ACCENT = [0x8b, 0xb8, 0xff];

function distToSegment(px, py, ax, ay, bx, by) {
  const dx = bx - ax;
  const dy = by - ay;
  const t = Math.max(0, Math.min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)));
  return Math.hypot(px - (ax + t * dx), py - (ay + t * dy));
}

/** Colour of the icon at (x, y) in unit coordinates, or null for a transparent corner. */
function sample(x, y, { rounded }) {
  if (rounded) {
    const r = 0.18;
    const cx = Math.min(Math.max(x, r), 1 - r);
    const cy = Math.min(Math.max(y, r), 1 - r);
    if (Math.hypot(x - cx, y - cy) > r) return null;
  }
  // Ring
  const ring = Math.abs(Math.hypot(x - 0.5, y - 0.5) - 0.3);
  if (ring < 0.03) return WHITE;
  // Check mark
  const d = Math.min(distToSegment(x, y, 0.37, 0.51, 0.47, 0.61), distToSegment(x, y, 0.47, 0.61, 0.64, 0.4));
  if (d < 0.035) return ACCENT;
  return NAVY;
}

function render(size, options) {
  const rows = [];
  for (let py = 0; py < size; py += 1) {
    const row = Buffer.alloc(1 + size * 4);
    for (let px = 0; px < size; px += 1) {
      let r = 0;
      let g = 0;
      let b = 0;
      let a = 0;
      for (const [ox, oy] of [
        [0.25, 0.25],
        [0.75, 0.25],
        [0.25, 0.75],
        [0.75, 0.75],
      ]) {
        const c = sample((px + ox) / size, (py + oy) / size, options);
        if (c) {
          r += c[0];
          g += c[1];
          b += c[2];
          a += 1;
        }
      }
      const o = 1 + px * 4;
      if (a > 0) {
        row[o] = Math.round(r / a);
        row[o + 1] = Math.round(g / a);
        row[o + 2] = Math.round(b / a);
        row[o + 3] = Math.round((a / 4) * 255);
      }
    }
    rows.push(row);
  }
  return Buffer.concat(rows);
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

function png(size, options) {
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8; // bit depth
  ihdr[9] = 6; // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(render(size, options), { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

mkdirSync(join(root, 'icons'), { recursive: true });
const files = {
  'icon-192.png': png(192, { rounded: true }),
  'icon-512.png': png(512, { rounded: true }),
  'icon-maskable-512.png': png(512, { rounded: false }),
  'apple-touch-icon.png': png(180, { rounded: false }),
};
for (const [name, data] of Object.entries(files)) writeFileSync(join(root, 'icons', name), data);

writeFileSync(
  join(root, 'favicon.svg'),
  `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64"><rect width="64" height="64" rx="12" fill="#0b1b34"/><circle cx="32" cy="32" r="19.2" fill="none" stroke="#fff" stroke-width="3.8"/><path d="M23.7 32.6l6.4 6.4 10.8-12.8" fill="none" stroke="#8bb8ff" stroke-width="4.5" stroke-linecap="round" stroke-linejoin="round"/></svg>\n`,
);
process.stdout.write(`icons written to ${join(root, 'icons')}
`);
