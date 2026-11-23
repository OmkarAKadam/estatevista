package com.estatevista.property.image;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;
import com.estatevista.common.response.ApiErrorResponse.FieldErrorDetail;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * SD-30 upload validator and sanitizer.
 *
 * <p>All supported payloads are decoded, bounded, redrawn, and re-encoded so
 * EXIF/GPS/comments/profiles and untrusted container chunks cannot reach
 * external storage. A bounded, dependency-free parser reads the EXIF
 * orientation tag (JPEG APP1 / WebP EXIF chunk) before decoding; the corrected
 * pixel transform is baked into the canonical image so no viewer has to trust
 * source metadata. WebP uses a maintained ImageIO reader/writer that decodes
 * real pixel data; animated payloads and malformed containers are rejected.</p>
 */
@Component
public class PropertyImageProcessor {

    public static final int MAX_FILE_BYTES = 5 * 1024 * 1024;
    public static final int MIN_DIMENSION = 200;
    public static final int MAX_DIMENSION = 6000;
    public static final long MAX_PIXELS = 40_000_000L;

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("jpg", "jpeg", "png", "webp");
    private static final Set<String> ALLOWED_CONTENT_TYPES =
            Set.of("image/jpeg", "image/png", "image/webp");

    public SanitizedImage sanitize(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("file", "is required");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw tooLarge();
        }

        String filename = file.getOriginalFilename();
        String extension = extensionOf(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw unsupported();
        }

        String declaredType = normalizeContentType(file.getContentType());
        if (!ALLOWED_CONTENT_TYPES.contains(declaredType)) {
            throw unsupported();
        }

        byte[] input;
        try {
            input = file.getBytes();
        } catch (IOException exception) {
            throw invalid("file", "could not be read");
        }

        Format format = detectFormat(input);
        if (!declaredType.equals(format.contentType())) {
            throw invalid("file", "content type does not match the image content");
        }
        if (!format.extensions().contains(extension)) {
            throw invalid("file", "file extension does not match the image content");
        }

        int orientation = sourceOrientation(input, format);

        return switch (format) {
            case JPEG -> reencode(input, "jpg", "jpeg", "image/jpeg", false, orientation);
            case PNG -> reencode(input, "png", "png", "image/png", true, orientation);
            case WEBP -> reencodeWebP(input, orientation);
        };
    }

    private static SanitizedImage reencode(
            byte[] input, String formatName, String extension,
            String contentType, boolean rejectAnimation, int orientation) {
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(
                new ByteArrayInputStream(input))) {
            ImageReader reader = ImageIO.getImageReaders(imageInput).next();
            try {
                reader.setInput(imageInput, false, true);
                if (rejectAnimation && reader.getNumImages(true) > 1) {
                    throw invalid("file", "animated images are not accepted");
                }
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                validateDimensions(outputWidth(width, height, orientation),
                        outputHeight(width, height, orientation));

                BufferedImage decoded;
                try {
                    decoded = reader.read(0);
                } catch (OutOfMemoryError error) {
                    throw invalid("file", "image requires too much memory to decode");
                }
                if (decoded == null || decoded.getWidth() < 1 || decoded.getHeight() < 1) {
                    throw invalid("file", "image could not be decoded");
                }

                int type = "jpeg".equals(extension)
                        ? BufferedImage.TYPE_INT_RGB
                        : BufferedImage.TYPE_INT_ARGB;
                BufferedImage canonical = applyOrientation(decoded, orientation, type);

                ImageWriter writer = ImageIO.getImageWritersByFormatName(formatName).next();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
                    writer.setOutput(imageOutput);
                    // Passing no image metadata prevents EXIF, GPS, comments,
                    // and embedded profiles from being copied to the output.
                    writer.write(null, new IIOImage(canonical, null, null), null);
                } finally {
                    writer.dispose();
                }
                byte[] result = output.toByteArray();
                if (result.length > MAX_FILE_BYTES) {
                    throw tooLarge();
                }
                return new SanitizedImage(
                        result, extension, contentType,
                        canonical.getWidth(), canonical.getHeight());
            } finally {
                reader.dispose();
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("file", "image could not be decoded or sanitized");
        }
    }

    private static SanitizedImage reencodeWebP(byte[] input, int orientation) {
        validateWebPContainer(input, false);
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(
                new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByMIMEType("image/webp");
            if (!readers.hasNext()) {
                throw invalid("file", "WebP decoding is not available");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, false, true);
                int imageCount = reader.getNumImages(true);
                if (imageCount != 1) {
                    throw invalid("file", "animated images are not accepted");
                }

                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateDimensions(width, height);
                validateDimensions(outputWidth(width, height, orientation),
                        outputHeight(width, height, orientation));

                BufferedImage decoded;
                try {
                    decoded = reader.read(0);
                } catch (OutOfMemoryError error) {
                    throw invalid("file", "image requires too much memory to decode");
                }
                if (decoded == null || decoded.getWidth() < 1 || decoded.getHeight() < 1) {
                    throw invalid("file", "image could not be decoded");
                }

                BufferedImage canonical = applyOrientation(
                        decoded, orientation, BufferedImage.TYPE_INT_ARGB);

                Iterator<ImageWriter> writers = ImageIO.getImageWritersByMIMEType("image/webp");
                if (!writers.hasNext()) {
                    throw invalid("file", "WebP encoding is not available");
                }
                ImageWriter writer = writers.next();
                ByteArrayOutputStream output = new ByteArrayOutputStream(
                        Math.min(input.length, MAX_FILE_BYTES));
                try (ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
                    writer.setOutput(imageOutput);
                    // No source metadata or thumbnails are passed to the writer.
                    writer.write(null, new IIOImage(canonical, null, null), null);
                } finally {
                    writer.dispose();
                }

                byte[] result = output.toByteArray();
                if (result.length > MAX_FILE_BYTES) {
                    throw tooLarge();
                }
                validateWebPContainer(result, true);
                return new SanitizedImage(
                        result, "webp", "image/webp",
                        canonical.getWidth(), canonical.getHeight());
            } finally {
                reader.dispose();
            }
        } catch (ApiException exception) {
            throw exception;
        } catch (IOException | RuntimeException | UnsatisfiedLinkError | NoClassDefFoundError exception) {
            throw invalid("file", "image could not be decoded or sanitized");
        } catch (OutOfMemoryError error) {
            throw invalid("file", "image requires too much memory to decode");
        }
    }

    /**
     * Reads the EXIF orientation tag from JPEG APP1 or WebP EXIF chunks. The
     * scan is bounded by marker/chunk count and declared segment lengths; no
     * undeclared or oversized structure is followed. Unreadable or unsupported
     * orientation data is ignored and treated as orientation 1.
     */
    private static int sourceOrientation(byte[] input, Format format) {
        return switch (format) {
            case JPEG -> jpegOrientation(input);
            case WEBP -> webpOrientation(input);
            case PNG -> 1;
        };
    }

    private static int jpegOrientation(byte[] input) {
        int offset = 2;
        int scanned = 0;

        while (offset + 4 <= input.length && scanned++ < 64) {
            if ((input[offset] & 0xff) != 0xff) {
                break;
            }
            int marker = input[offset + 1] & 0xff;
            if (marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
                offset += 2;
                continue;
            }
            if (marker == 0xd9 || marker == 0xda) {
                break;
            }

            int length = ((input[offset + 2] & 0xff) << 8) | (input[offset + 3] & 0xff);
            if (length < 2 || offset + 2L + length > input.length) {
                break;
            }
            int payload = offset + 4;
            int payloadLength = length - 2;
            if (marker == 0xe1 && hasExifHeader(input, payload, payloadLength)) {
                int orientation = tiffOrientation(input, payload + 6, payloadLength - 6);
                if (orientation > 0) {
                    return orientation;
                }
            }
            offset += 2 + length;
        }
        return 1;
    }

    private static int webpOrientation(byte[] input) {
        if (input.length < 20 || !matches(input, 0, "RIFF") || !matches(input, 8, "WEBP")) {
            return 1;
        }
        int offset = 12;
        int chunks = 0;

        while (offset + 8 <= input.length && chunks++ < 64) {
            String chunkName = ascii(input, offset);
            long size = u32(input, offset + 4);
            long paddedSize = size + (size & 1L);
            if (size > Integer.MAX_VALUE || offset + 8L + paddedSize > input.length) {
                break;
            }
            if ("EXIF".equals(chunkName)) {
                int payload = offset + 8;
                int payloadLength = (int) size;
                if (hasExifHeader(input, payload, payloadLength)) {
                    int orientation = tiffOrientation(input, payload + 6, payloadLength - 6);
                    if (orientation > 0) {
                        return orientation;
                    }
                }
            }
            offset += 8 + (int) paddedSize;
        }
        return 1;
    }

    private static boolean hasExifHeader(byte[] input, int offset, int available) {
        return available >= 6
                && input[offset] == 'E' && input[offset + 1] == 'x'
                && input[offset + 2] == 'i' && input[offset + 3] == 'f'
                && input[offset + 4] == 0 && input[offset + 5] == 0;
    }

    private static int tiffOrientation(byte[] input, int base, int length) {
        if (length < 8) {
            return 1;
        }
        boolean little = input[base] == 'I' && input[base + 1] == 'I';
        boolean big = input[base] == 'M' && input[base + 1] == 'M';
        if (!little && !big) {
            return 1;
        }
        if (readU16(input, base + 2, little) != 42) {
            return 1;
        }

        long ifdOffset = readU32(input, base + 4, little);
        if (ifdOffset < 8 || ifdOffset > length - 2) {
            return 1;
        }
        int position = base + (int) ifdOffset;
        int entries = readU16(input, position, little);
        if (entries > 128) {
            return 1;
        }
        for (int index = 0; index < entries; index++) {
            int entry = position + 2 + index * 12;
            if (entry + 12 > base + length) {
                break;
            }
            if (readU16(input, entry, little) != 0x0112) {
                continue;
            }
            int type = readU16(input, entry + 2, little);
            long count = readU32(input, entry + 4, little);
            if (type != 3 || count != 1) {
                return 1;
            }
            int value = readU16(input, entry + 8, little);
            return value >= 1 && value <= 8 ? value : 1;
        }
        return 1;
    }

    private static int readU16(byte[] input, int offset, boolean littleEndian) {
        int first = input[offset] & 0xff;
        int second = input[offset + 1] & 0xff;
        return littleEndian ? first | (second << 8) : (first << 8) | second;
    }

    private static long readU32(byte[] input, int offset, boolean littleEndian) {
        long first = input[offset] & 0xffL;
        long second = input[offset + 1] & 0xffL;
        long third = input[offset + 2] & 0xffL;
        long fourth = input[offset + 3] & 0xffL;
        return littleEndian
                ? first | (second << 8) | (third << 16) | (fourth << 24)
                : (first << 24) | (second << 16) | (third << 8) | fourth;
    }

    private static int outputWidth(int width, int height, int orientation) {
        return orientation >= 5 ? height : width;
    }

    private static int outputHeight(int width, int height, int orientation) {
        return orientation >= 5 ? width : height;
    }

    /** Bakes the EXIF orientation into canonical pixels using no extra dependency. */
    private static BufferedImage applyOrientation(
            BufferedImage decoded, int orientation, int type) {
        int width = decoded.getWidth();
        int height = decoded.getHeight();
        BufferedImage canonical = new BufferedImage(
                outputWidth(width, height, orientation),
                outputHeight(width, height, orientation),
                type);
        Graphics2D graphics = canonical.createGraphics();
        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_OFF);
            graphics.drawImage(decoded, orientationTransform(orientation, width, height), null);
        } finally {
            graphics.dispose();
        }
        return canonical;
    }

    private static AffineTransform orientationTransform(int orientation, int width, int height) {
        return switch (orientation) {
            case 2 -> new AffineTransform(-1, 0, 0, 1, width, 0);
            case 3 -> new AffineTransform(-1, 0, 0, -1, width, height);
            case 4 -> new AffineTransform(1, 0, 0, -1, 0, height);
            case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0);
            case 6 -> new AffineTransform(0, 1, -1, 0, height, 0);
            case 7 -> new AffineTransform(0, -1, -1, 0, height, width);
            case 8 -> new AffineTransform(0, -1, 1, 0, 0, width);
            default -> new AffineTransform();
        };
    }

    private static void validateWebPContainer(byte[] input, boolean requireCleanOutput) {
        if (input.length < 20
                || !matches(input, 0, "RIFF")
                || !matches(input, 8, "WEBP")) {
            if (requireCleanOutput) {
                throw invalid("file", "image could not be re-encoded safely");
            }
            throw unsupported();
        }
        if (u32(input, 4) != input.length - 8L) {
            throw invalid("file", "invalid WebP container size");
        }

        boolean hasImageChunk = false;
        int offset = 12;
        while (offset + 8 <= input.length) {
            String chunkName = ascii(input, offset);
            long chunkSize = u32(input, offset + 4);
            long dataStart = offset + 8L;
            long paddedSize = chunkSize + (chunkSize & 1L);
            if (chunkSize > Integer.MAX_VALUE || dataStart + paddedSize > input.length) {
                throw invalid("file", "invalid WebP chunk length");
            }

            if ("ANIM".equals(chunkName) || "ANMF".equals(chunkName)) {
                throw invalid("file", "animated images are not accepted");
            }
            if (requireCleanOutput && isMetadataChunk(chunkName)) {
                throw invalid("file", "image metadata was not removed");
            }
            if ("VP8 ".equals(chunkName) || "VP8L".equals(chunkName)
                    || "VP8X".equals(chunkName)) {
                if ("VP8X".equals(chunkName) && chunkSize >= 1
                        && (input[(int) dataStart] & 0x02) != 0) {
                    throw invalid("file", "animated images are not accepted");
                }
                hasImageChunk = true;
            }
            offset += 8 + (int) paddedSize;
        }

        if (!hasImageChunk || offset != input.length) {
            throw invalid("file", "WebP image data is missing or malformed");
        }
    }

    private static boolean isMetadataChunk(String chunkName) {
        return "EXIF".equals(chunkName)
                || "XMP ".equals(chunkName)
                || "ICCP".equals(chunkName)
                || "JUMB".equals(chunkName);
    }

    private static void validateDimensions(int width, int height) {
        if (width < MIN_DIMENSION || height < MIN_DIMENSION
                || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw invalid(
                    "file",
                    "image dimensions must be between 200x200 and 6000x6000 pixels");
        }
        if ((long) width * height > MAX_PIXELS) {
            throw invalid("file", "image exceeds 40 megapixels");
        }
    }

    private static Format detectFormat(byte[] input) {
        if (input.length >= 3
                && (input[0] & 0xff) == 0xff
                && (input[1] & 0xff) == 0xd8
                && (input[2] & 0xff) == 0xff) {
            return Format.JPEG;
        }
        if (input.length >= 8
                && (input[0] & 0xff) == 0x89
                && input[1] == 'P' && input[2] == 'N' && input[3] == 'G'
                && input[4] == '\r' && input[5] == '\n'
                && input[6] == 0x1a && input[7] == '\n') {
            return Format.PNG;
        }
        if (input.length >= 12 && matches(input, 0, "RIFF") && matches(input, 8, "WEBP")) {
            return Format.WEBP;
        }
        throw unsupported();
    }

    private static String extensionOf(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String safeName = filename.replace('\\', '/');
        int slash = safeName.lastIndexOf('/');
        if (slash >= 0) {
            safeName = safeName.substring(slash + 1);
        }
        int dot = safeName.lastIndexOf('.');
        if (dot < 0 || dot == safeName.length() - 1) {
            return "";
        }
        return safeName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return "";
        }
        int separator = contentType.indexOf(';');
        String value = separator < 0 ? contentType : contentType.substring(0, separator);
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean matches(byte[] input, int offset, String expected) {
        if (offset + expected.length() > input.length) {
            return false;
        }
        for (int index = 0; index < expected.length(); index++) {
            if (input[offset + index] != (byte) expected.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static String ascii(byte[] input, int offset) {
        StringBuilder value = new StringBuilder(4);
        for (int index = 0; index < 4; index++) {
            value.append((char) (input[offset + index] & 0xff));
        }
        return value.toString();
    }

    private static long u32(byte[] input, int offset) {
        return (input[offset] & 0xffL)
                | ((input[offset + 1] & 0xffL) << 8)
                | ((input[offset + 2] & 0xffL) << 16)
                | ((input[offset + 3] & 0xffL) << 24);
    }

    private static ApiException tooLarge() {
        return new ApiException(
                ErrorCode.PAYLOAD_TOO_LARGE,
                "The image exceeds the 5 MB limit.");
    }

    private static ApiException invalid(String field, String message) {
        return new ApiException(
                ErrorCode.VALIDATION_ERROR,
                ErrorCode.VALIDATION_ERROR.defaultMessage(),
                List.of(new FieldErrorDetail(field, message)));
    }

    private static ApiException unsupported() {
        return new ApiException(
                ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Only JPEG, PNG, and WebP images are supported.");
    }

    private enum Format {
        JPEG(Set.of("jpg", "jpeg"), "image/jpeg"),
        PNG(Set.of("png"), "image/png"),
        WEBP(Set.of("webp"), "image/webp");

        private final Set<String> extensions;
        private final String contentType;

        Format(Set<String> extensions, String contentType) {
            this.extensions = extensions;
            this.contentType = contentType;
        }

        Set<String> extensions() {
            return extensions;
        }

        String contentType() {
            return contentType;
        }
    }
}
