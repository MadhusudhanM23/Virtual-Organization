import { hasMetadata, sniffMime, stripMetadata } from './exif';

/**
 * Prepares a captured or chosen photo for the evidence queue (ADR-76):
 *  1. decode with the browser (orientation applied, since stripping EXIF drops the rotation tag);
 *  2. scale so the longer side is at most {@link MAX_SIDE_PX}; re-encode as JPEG, lowering quality until the file is
 *     at most {@link TARGET_BYTES}. A canvas re-encode carries no EXIF/GPS by construction;
 *  3. run the byte-level stripper anyway and verify, so the guarantee does not depend on the canvas;
 *  4. hash (SHA-256) so the server can verify the bytes it receives.
 * When the browser cannot decode the file, a JPEG/PNG/WebP under the hard limit is stripped at byte level instead.
 */
export const MAX_SIDE_PX = 1600;
/** Aim for 1.5 MiB so base64 (x 4/3) stays well under typical request limits. */
export const TARGET_BYTES = 1_572_864;
/** The server's hard limit (docs/api/field-sync.md). */
export const MAX_BYTES = 2 * 1024 * 1024;

export interface PreparedEvidence {
  contentType: string;
  data: ArrayBuffer;
  sha256: string;
  size: number;
  width: number | null;
  height: number | null;
}

export class EvidenceTooLargeError extends Error {
  constructor() {
    super('The photo is too large even after shrinking.');
    this.name = 'EvidenceTooLargeError';
  }
}

export class UnsupportedImageError extends Error {
  constructor() {
    super('Only JPEG, PNG or WebP photos can be used.');
    this.name = 'UnsupportedImageError';
  }
}

export async function sha256Hex(data: ArrayBuffer): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', data);
  return Array.from(new Uint8Array(digest), (b) => b.toString(16).padStart(2, '0')).join('');
}

function toArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  return bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
}

async function reencode(
  file: Blob,
): Promise<{ bytes: Uint8Array; width: number; height: number } | null> {
  if (typeof createImageBitmap !== 'function' || typeof document === 'undefined') return null;
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file, { imageOrientation: 'from-image' });
  } catch {
    return null;
  }
  const scale = Math.min(1, MAX_SIDE_PX / Math.max(bitmap.width, bitmap.height));
  const width = Math.max(1, Math.round(bitmap.width * scale));
  const height = Math.max(1, Math.round(bitmap.height * scale));
  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');
  if (!ctx) return null;
  ctx.drawImage(bitmap, 0, 0, width, height);
  bitmap.close();
  for (const quality of [0.85, 0.72, 0.6, 0.48, 0.36]) {
    const blob = await new Promise<Blob | null>((resolve) =>
      canvas.toBlob(resolve, 'image/jpeg', quality),
    );
    if (!blob) return null;
    if (blob.size <= TARGET_BYTES)
      return { bytes: new Uint8Array(await blob.arrayBuffer()), width, height };
  }
  return null;
}

export async function prepareEvidence(file: Blob): Promise<PreparedEvidence> {
  const original = new Uint8Array(await file.arrayBuffer());
  if (!sniffMime(original)) throw new UnsupportedImageError();

  const re = await reencode(file);
  let bytes: Uint8Array;
  let width: number | null = null;
  let height: number | null = null;
  if (re) {
    bytes = stripMetadata(re.bytes);
    width = re.width;
    height = re.height;
  } else {
    if (original.length > MAX_BYTES) throw new EvidenceTooLargeError();
    bytes = stripMetadata(original);
  }
  if (hasMetadata(bytes)) throw new Error('Metadata could not be removed from the photo.');
  if (bytes.length > MAX_BYTES) throw new EvidenceTooLargeError();

  const data = toArrayBuffer(bytes);
  return {
    contentType: sniffMime(bytes) ?? 'image/jpeg',
    data,
    sha256: await sha256Hex(data),
    size: bytes.length,
    width,
    height,
  };
}
