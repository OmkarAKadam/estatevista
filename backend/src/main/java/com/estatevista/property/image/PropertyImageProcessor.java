package com.estatevista.property.image;

import java.awt.Graphics2D;
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
 * external storage. WebP uses a maintained ImageIO reader/writer that decodes
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

        return switch (format) {
            case JPEG -> reencode(input, "jpg", "jpeg", "image/jpeg", false);
            case PNG -> reencode(input, "png", "png", "image/png", true);
            case WEBP -> reencodeWebP(input);
        };
    }

    private static SanitizedImage reencode(
            byte[] input, String formatName, String extension,
            String contentType, boolean rejectAnimation) {
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
                BufferedImage canonical = new BufferedImage(
                        decoded.getWidth(), decoded.getHeight(), type);
                Graphics2D graphics = canonical.createGraphics();
                try {
                    graphics.drawImage(decoded, 0, 0, null);
                } finally {
                    graphics.dispose();
                }

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

    private static SanitizedImage reencodeWebP(byte[] input) {
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

                BufferedImage decoded;
                try {
                    decoded = reader.read(0);
                } catch (OutOfMemoryError error) {
                    throw invalid("file", "image requires too much memory to decode");
                }
                if (decoded == null || decoded.getWidth() < 1 || decoded.getHeight() < 1) {
                    throw invalid("file", "image could not be decoded");
                }

                BufferedImage canonical = new BufferedImage(
                        decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = canonical.createGraphics();
                try {
                    graphics.drawImage(decoded, 0, 0, null);
                } finally {
                    graphics.dispose();
                }

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
