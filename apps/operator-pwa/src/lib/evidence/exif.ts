/**
 * Metadata stripping (ADR-76, privacy): a photo taken in a warehouse can carry the GPS position, the device model,
 * a timestamp, a thumbnail and an operator name. Evidence leaves the device without any of it. These are pure
 * byte-level functions (no canvas), used as a second line after the canvas re-encode in `image.ts` and as the
 * only line when the browser cannot decode the image.
 */
export type ImageMime = 'image/jpeg' | 'image/png' | 'image/webp';

const textDecoder = new TextDecoder('latin1');

function ascii(b: Uint8Array, off: number, len: number): string {
  return textDecoder.decode(b.subarray(off, Math.min(b.length, off + len)));
}

export function sniffMime(b: Uint8Array): ImageMime | null {
  if (b.length >= 3 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg';
  if (b.length >= 8 && b[0] === 0x89 && ascii(b, 1, 3) === 'PNG') return 'image/png';
  if (b.length >= 12 && ascii(b, 0, 4) === 'RIFF' && ascii(b, 8, 4) === 'WEBP') return 'image/webp';
  return null;
}

function concat(parts: Uint8Array[]): Uint8Array {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let o = 0;
  for (const p of parts) {
    out.set(p, o);
    o += p.length;
  }
  return out;
}

// JPEG: keep APP0 (JFIF) and APP2 (ICC colour profile); drop APP1 (EXIF/XMP), APP3..APP15 (IPTC, vendor data) and COM.
function stripJpeg(b: Uint8Array): Uint8Array {
  const parts: Uint8Array[] = [b.subarray(0, 2)];
  let i = 2;
  while (i + 4 <= b.length) {
    if (b[i] !== 0xff) {
      parts.push(b.subarray(i));
      return concat(parts);
    }
    const marker = b[i + 1] ?? 0;
    if (marker === 0xff) {
      i += 1; // fill byte
      continue;
    }
    if (marker === 0xd9 || marker === 0xda || (marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) {
      // End of image / start of scan: entropy-coded data follows, copy the rest verbatim.
      parts.push(b.subarray(i));
      return concat(parts);
    }
    const len = ((b[i + 2] ?? 0) << 8) | (b[i + 3] ?? 0);
    const end = Math.min(b.length, i + 2 + len);
    const drop = (marker >= 0xe1 && marker <= 0xef && marker !== 0xe2) || marker === 0xfe;
    if (!drop) parts.push(b.subarray(i, end));
    i = end;
  }
  parts.push(b.subarray(Math.min(i, b.length)));
  return concat(parts);
}

const PNG_DROP = new Set(['eXIf', 'tEXt', 'iTXt', 'zTXt', 'tIME']);

function stripPng(b: Uint8Array): Uint8Array {
  const parts: Uint8Array[] = [b.subarray(0, 8)];
  let i = 8;
  while (i + 12 <= b.length) {
    const len = ((b[i] ?? 0) * 2 ** 24 + ((b[i + 1] ?? 0) << 16) + ((b[i + 2] ?? 0) << 8) + (b[i + 3] ?? 0)) >>> 0;
    const type = ascii(b, i + 4, 4);
    const end = Math.min(b.length, i + 12 + len);
    if (!PNG_DROP.has(type)) parts.push(b.subarray(i, end));
    i = end;
  }
  return concat(parts);
}

function stripWebp(b: Uint8Array): Uint8Array {
  const parts: Uint8Array[] = [];
  let i = 12;
  while (i + 8 <= b.length) {
    const type = ascii(b, i, 4);
    const len = ((b[i + 4] ?? 0) | ((b[i + 5] ?? 0) << 8) | ((b[i + 6] ?? 0) << 16) | ((b[i + 7] ?? 0) << 24)) >>> 0;
    const end = Math.min(b.length, i + 8 + len + (len & 1));
    if (type !== 'EXIF' && type !== 'XMP ') {
      const chunk = b.slice(i, end);
      if (type === 'VP8X' && chunk.length > 8) chunk[8] = (chunk[8] ?? 0) & ~(0x08 | 0x04); // EXIF + XMP flags
      parts.push(chunk);
    }
    i = end;
  }
  const body = concat(parts);
  const out = new Uint8Array(12 + body.length);
  out.set(b.subarray(0, 12));
  out.set(body, 12);
  const riffSize = out.length - 8;
  out[4] = riffSize & 0xff;
  out[5] = (riffSize >> 8) & 0xff;
  out[6] = (riffSize >> 16) & 0xff;
  out[7] = (riffSize >>> 24) & 0xff;
  return out;
}

/** Returns a copy of the image without EXIF/GPS/XMP/IPTC/comment/text metadata. Unknown formats throw. */
export function stripMetadata(bytes: Uint8Array): Uint8Array {
  switch (sniffMime(bytes)) {
    case 'image/jpeg':
      return stripJpeg(bytes);
    case 'image/png':
      return stripPng(bytes);
    case 'image/webp':
      return stripWebp(bytes);
    case null:
      throw new Error('Unsupported image format (JPEG, PNG or WebP only)');
  }
}

/** True when the file still carries EXIF/XMP/IPTC/text metadata (used to verify a result and in tests). */
export function hasMetadata(bytes: Uint8Array): boolean {
  const mime = sniffMime(bytes);
  if (mime === 'image/jpeg') {
    let i = 2;
    while (i + 4 <= bytes.length && bytes[i] === 0xff) {
      const marker = bytes[i + 1] ?? 0;
      if (marker === 0xd9 || marker === 0xda) return false;
      const len = ((bytes[i + 2] ?? 0) << 8) | (bytes[i + 3] ?? 0);
      if ((marker >= 0xe1 && marker <= 0xef && marker !== 0xe2) || marker === 0xfe) return true;
      i += 2 + len;
    }
    return false;
  }
  if (mime === 'image/png') {
    let i = 8;
    while (i + 12 <= bytes.length) {
      const len =
        ((bytes[i] ?? 0) * 2 ** 24 + ((bytes[i + 1] ?? 0) << 16) + ((bytes[i + 2] ?? 0) << 8) + (bytes[i + 3] ?? 0)) >>>
        0;
      if (PNG_DROP.has(ascii(bytes, i + 4, 4))) return true;
      i += 12 + len;
    }
    return false;
  }
  if (mime === 'image/webp') {
    let i = 12;
    while (i + 8 <= bytes.length) {
      const type = ascii(bytes, i, 4);
      if (type === 'EXIF' || type === 'XMP ') return true;
      const len =
        ((bytes[i + 4] ?? 0) | ((bytes[i + 5] ?? 0) << 8) | ((bytes[i + 6] ?? 0) << 16) | ((bytes[i + 7] ?? 0) << 24)) >>>
        0;
      i += 8 + len + (len & 1);
    }
    return false;
  }
  return false;
}
