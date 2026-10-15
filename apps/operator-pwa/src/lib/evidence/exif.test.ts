import { describe, expect, it } from 'vitest';
import { hasMetadata, sniffMime, stripMetadata } from './exif';

const enc = (s: string) => Array.from(s, (c) => c.charCodeAt(0));

function jpegWith(segments: { marker: number; payload: number[] }[]): Uint8Array {
  const out: number[] = [0xff, 0xd8];
  for (const s of segments) {
    const len = s.payload.length + 2;
    out.push(0xff, s.marker, len >> 8, len & 0xff, ...s.payload);
  }
  out.push(0xff, 0xda, 0, 4, 1, 2, 0x11, 0x22, 0x33, 0xff, 0xd9);
  return new Uint8Array(out);
}

const GPS = enc('Exif\0\0MM\0*GPSLatitude=12.97;GPSLongitude=77.59');

describe('EXIF / GPS stripping', () => {
  it('removes the EXIF (GPS) segment from a JPEG but keeps JFIF, the ICC profile and the image data', () => {
    const src = jpegWith([
      { marker: 0xe0, payload: enc('JFIF\0\x01\x01\0\0\x01\0\x01\0\0') },
      { marker: 0xe1, payload: GPS },
      { marker: 0xe1, payload: enc('http://ns.adobe.com/xap/1.0/\0<x:xmpmeta/>') },
      { marker: 0xe2, payload: enc('ICC_PROFILE\0\x01\x01data') },
      { marker: 0xed, payload: enc('Photoshop 3.0\0IPTC') },
      { marker: 0xfe, payload: enc('operator: Jane Doe') },
      { marker: 0xdb, payload: [0, 1, 2, 3] },
    ]);
    expect(hasMetadata(src)).toBe(true);
    const out = stripMetadata(src);
    expect(hasMetadata(out)).toBe(false);
    const text = new TextDecoder('latin1').decode(out);
    expect(text).not.toContain('GPSLatitude');
    expect(text).not.toContain('Jane Doe');
    expect(text).not.toContain('xmpmeta');
    expect(text).not.toContain('IPTC');
    expect(text).toContain('JFIF');
    expect(text).toContain('ICC_PROFILE');
    // entropy-coded scan data and EOI are intact
    expect(Array.from(out.slice(-5))).toEqual([0x11, 0x22, 0x33, 0xff, 0xd9]);
    expect(sniffMime(out)).toBe('image/jpeg');
  });

  it('leaves an already clean JPEG byte-for-byte unchanged', () => {
    const clean = jpegWith([{ marker: 0xe0, payload: enc('JFIF\0\x01\x01\0\0\x01\0\x01\0\0') }]);
    expect(Array.from(stripMetadata(clean))).toEqual(Array.from(clean));
  });

  it('removes eXIf and text chunks from a PNG and keeps the pixels', () => {
    const chunk = (type: string, data: number[]) => [
      0,
      0,
      data.length >> 8,
      data.length & 0xff,
      ...enc(type),
      ...data,
      0,
      0,
      0,
      0,
    ];
    const src = new Uint8Array([
      0x89,
      ...enc('PNG\r\n\x1a\n'),
      ...chunk('IHDR', [0, 0, 0, 1, 0, 0, 0, 1, 8, 2, 0, 0, 0]),
      ...chunk('eXIf', enc('MM\0*GPS')),
      ...chunk('tEXt', enc('Author\0Jane')),
      ...chunk('IDAT', [1, 2, 3]),
      ...chunk('IEND', []),
    ]);
    expect(hasMetadata(src)).toBe(true);
    const out = stripMetadata(src);
    expect(hasMetadata(out)).toBe(false);
    const text = new TextDecoder('latin1').decode(out);
    expect(text).toContain('IDAT');
    expect(text).not.toContain('eXIf');
    expect(text).not.toContain('Jane');
  });

  it('removes EXIF/XMP chunks from a WebP, fixes the RIFF size and clears the VP8X flags', () => {
    const le = (n: number) => [n & 0xff, (n >> 8) & 0xff, (n >> 16) & 0xff, (n >>> 24) & 0xff];
    const chunk = (type: string, data: number[]) => [
      ...enc(type),
      ...le(data.length),
      ...data,
      ...(data.length & 1 ? [0] : []),
    ];
    const body = [
      ...enc('WEBP'),
      ...chunk('VP8X', [0x0c, 0, 0, 0, 0, 0, 0, 0, 0, 0]),
      ...chunk('EXIF', enc('MM\0*GPS!')),
      ...chunk('XMP ', enc('<xmp/>')),
      ...chunk('VP8 ', [9, 9, 9, 9]),
    ];
    const src = new Uint8Array([...enc('RIFF'), ...le(body.length), ...body]);
    expect(hasMetadata(src)).toBe(true);
    const out = stripMetadata(src);
    expect(hasMetadata(out)).toBe(false);
    const view = new DataView(out.buffer, out.byteOffset, out.byteLength);
    expect(view.getUint32(4, true)).toBe(out.length - 8);
    expect(out[20]! & 0x0c).toBe(0); // VP8X flags: EXIF and XMP bits cleared
    expect(new TextDecoder('latin1').decode(out)).toContain('VP8 ');
  });

  it('rejects anything that is not JPEG, PNG or WebP', () => {
    expect(sniffMime(new Uint8Array([1, 2, 3, 4]))).toBeNull();
    expect(() => stripMetadata(new Uint8Array([1, 2, 3, 4]))).toThrow(/Unsupported/);
  });
});
