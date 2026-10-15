import { expect, type Page } from '@playwright/test';
import { crc32, deflateSync } from 'node:zlib';

export async function signIn(page: Page): Promise<void> {
  await page.goto('/');
  await page.getByLabel('Organization').fill('demo');
  await page.getByLabel('Email').fill('driver.blr@demo.invalid');
  await page.getByLabel('Password').fill('demo-password-for-tests');
  await page.getByRole('button', { name: 'Sign in', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'My tasks', level: 1 })).toBeVisible();
  await expect(page.getByTestId('task-card').first()).toBeVisible();
}

/** Waits until the service worker controls the page: the app shell is then precached and works offline. */
export async function waitForServiceWorker(page: Page): Promise<void> {
  await page.evaluate(async () => {
    await navigator.serviceWorker.ready;
  });
  await expect
    .poll(() => page.evaluate(async () => (await caches.keys()).length), { timeout: 20_000 })
    .toBeGreaterThan(0);
}

function pngChunk(type: string, data: Buffer): Buffer {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length);
  const body = Buffer.concat([Buffer.from(type, 'ascii'), data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(body));
  return Buffer.concat([len, body, crc]);
}

/** A real, decodable 16x16 PNG that also carries text and EXIF-style metadata chunks with a GPS-looking payload. */
export function pngWithMetadata(): Buffer {
  const w = 16;
  const h = 16;
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(w, 0);
  ihdr.writeUInt32BE(h, 4);
  ihdr[8] = 8;
  ihdr[9] = 2;
  const raw = Buffer.alloc((1 + w * 3) * h);
  for (let y = 0; y < h; y += 1) {
    for (let x = 0; x < w; x += 1) {
      const o = y * (1 + w * 3) + 1 + x * 3;
      raw[o] = 40 + x * 10;
      raw[o + 1] = 90;
      raw[o + 2] = 200 - y * 8;
    }
  }
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    pngChunk('IHDR', ihdr),
    pngChunk('tEXt', Buffer.from('Author\0Jane Operator GPSLatitude=12.9716 GPSLongitude=77.5946')),
    pngChunk('eXIf', Buffer.from('MM\0*GPSLatitude=12.9716')),
    pngChunk('IDAT', deflateSync(raw)),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);
}

/** Not decodable by the browser (exercises the byte-level fallback), with an EXIF/GPS APP1 segment. */
export function fakeJpegWithExif(): Buffer {
  const exif = Buffer.from('Exif\0\0MM\0*GPSLatitude=12.9716;GPSLongitude=77.5946');
  const app1 = Buffer.concat([
    Buffer.from([0xff, 0xe1, (exif.length + 2) >> 8, (exif.length + 2) & 0xff]),
    exif,
  ]);
  return Buffer.concat([
    Buffer.from([0xff, 0xd8, 0xff, 0xe0, 0, 16]),
    Buffer.from('JFIF\0\x01\x01\0\0\x01\0\x01\0\0', 'latin1'),
    app1,
    Buffer.from([0xff, 0xda, 0, 4, 1, 2, 0x11, 0x22, 0x33, 0xff, 0xd9]),
  ]);
}
