package io.vorg.api.ops;

import java.nio.charset.StandardCharsets;

/**
 * Cheap, dependency-free sniffing of an uploaded image (ADR-76): which format it is by magic bytes, and whether it still
 * carries EXIF / GPS metadata. The PWA strips both before upload; the server refuses a file that still has them rather
 * than storing location data of a person by accident.
 */
public final class ImageSniffer {

    public enum Kind {
        JPEG("image/jpeg"), PNG("image/png"), WEBP("image/webp"), UNKNOWN("");

        private final String mime;

        Kind(String mime) {
            this.mime = mime;
        }

        public String mime() {
            return mime;
        }
    }

    private ImageSniffer() {}

    public static Kind kind(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Kind.JPEG;
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return Kind.PNG;
        }
        if (b.length >= 12 && ascii(b, 0, 4).equals("RIFF") && ascii(b, 8, 4).equals("WEBP")) {
            return Kind.WEBP;
        }
        return Kind.UNKNOWN;
    }

    /** True when the file still contains an EXIF block (which is where GPS lives). */
    public static boolean hasExif(byte[] b, Kind kind) {
        return switch (kind) {
            case JPEG -> jpegHasExif(b);
            case PNG -> pngHasExif(b);
            case WEBP -> webpHasExif(b);
            case UNKNOWN -> false;
        };
    }

    private static boolean jpegHasExif(byte[] b) {
        int i = 2;
        while (i + 4 <= b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                return false;
            }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xD9 || marker == 0xDA) { // end of image / start of scan: metadata segments are over
                return false;
            }
            int len = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
            if (marker == 0xE1 && i + 10 <= b.length && ascii(b, i + 4, 4).equals("Exif")) {
                return true;
            }
            i += 2 + len;
        }
        return false;
    }

    private static boolean pngHasExif(byte[] b) {
        int i = 8;
        while (i + 12 <= b.length) {
            long len = ((b[i] & 0xFFL) << 24) | ((b[i + 1] & 0xFFL) << 16) | ((b[i + 2] & 0xFFL) << 8)
                    | (b[i + 3] & 0xFFL);
            String type = ascii(b, i + 4, 4);
            if (type.equals("eXIf")) {
                return true;
            }
            if (type.equals("IDAT") || type.equals("IEND")) {
                return false;
            }
            i += (int) Math.min(Integer.MAX_VALUE / 2, len + 12);
        }
        return false;
    }

    private static boolean webpHasExif(byte[] b) {
        int i = 12;
        while (i + 8 <= b.length) {
            String type = ascii(b, i, 4);
            long len = (b[i + 4] & 0xFFL) | ((b[i + 5] & 0xFFL) << 8) | ((b[i + 6] & 0xFFL) << 16)
                    | ((b[i + 7] & 0xFFL) << 24);
            if (type.equals("EXIF")) {
                return true;
            }
            i += (int) Math.min(Integer.MAX_VALUE / 2, 8 + len + (len & 1));
        }
        return false;
    }

    private static String ascii(byte[] b, int off, int len) {
        return new String(b, off, Math.min(len, b.length - off), StandardCharsets.ISO_8859_1);
    }
}
