package com.estatevista.property.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import com.estatevista.common.exception.ApiException;
import com.estatevista.common.exception.ErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Deterministic SD-30 processor tests. Every image is generated in memory; no
 * provider, server, or database is involved. The orientation assertions inspect
 * actual decoded pixel quadrants, so a parsed-but-unapplied EXIF tag fails.
 */
class PropertyImageProcessorTest {

    private static final int WIDTH = 240;
    private static final int HEIGHT = 200;
    private static final int RED = 0;
    private static final int GREEN = 1;
    private static final int BLUE = 2;
    private static final int YELLOW = 3;

    /* Destinations TL, TR, BL, BR after applying each EXIF orientation. */
    private static final int[][] EXPECTED_QUADRANTS = {
            {RED, GREEN, BLUE, YELLOW},   // 1: unchanged
            {GREEN, RED, YELLOW, BLUE},   // 2: mirror horizontal
            {YELLOW, BLUE, GREEN, RED},   // 3: rotate 180
            {BLUE, YELLOW, RED, GREEN},   // 4: mirror vertical
            {RED, BLUE, GREEN, YELLOW},   // 5: transpose
            {BLUE, RED, YELLOW, GREEN},   // 6: rotate 90 clockwise
            {YELLOW, GREEN, BLUE, RED},   // 7: transverse
            {GREEN, YELLOW, RED, BLUE}    // 8: rotate 270 clockwise
    };

    private final PropertyImageProcessor processor = new PropertyImageProcessor();

    @Test
    void jpegOrientationIsBakedIntoPixelsForAllEightValues() throws Exception {
        for (int orientation = 1; orientation <= 8; orientation++) {
            SanitizedImage sanitized = sanitize(
                    "oriented.jpg", "image/jpeg", jpegWithOrientation(orientation, false));
            BufferedImage output = decode(sanitized.bytes());
            int expectedWidth = orientation >= 5 ? HEIGHT : WIDTH;
            int expectedHeight = orientation >= 5 ? WIDTH : HEIGHT;

            assertThat(sanitized.extension()).as("orientation %s extension", orientation)
                    .isEqualTo("jpeg");
            assertThat(sanitized.contentType()).as("orientation %s MIME", orientation)
                    .isEqualTo("image/jpeg");
            assertThat(output.getWidth()).as("orientation %s output width", orientation)
                    .isEqualTo(expectedWidth);
            assertThat(output.getHeight()).as("orientation %s output height", orientation)
                    .isEqualTo(expectedHeight);
            assertThat(sanitized.width()).as("orientation %s reported width", orientation)
                    .isEqualTo(expectedWidth);
            assertThat(sanitized.height()).as("orientation %s reported height", orientation)
                    .isEqualTo(expectedHeight);
            assertThat(quadrants(output)).as("orientation %s pixel quadrants", orientation)
                    .containsExactly(EXPECTED_QUADRANTS[orientation - 1]);
            assertThat(contains(sanitized.bytes(), "Exif".getBytes(StandardCharsets.US_ASCII)))
                    .as("orientation %s EXIF stripped", orientation)
                    .isFalse();
        }
    }

    @Test
    void bigEndianExifOrientationIsHonouredAndStripped() throws Exception {
        SanitizedImage sanitized = sanitize(
                "big-endian.jpg", "image/jpeg", jpegWithOrientation(6, true));

        assertThat(decode(sanitized.bytes()).getWidth()).isEqualTo(HEIGHT);
        assertThat(decode(sanitized.bytes()).getHeight()).isEqualTo(WIDTH);
        assertThat(quadrants(decode(sanitized.bytes())))
                .containsExactly(EXPECTED_QUADRANTS[5]);
        assertThat(contains(sanitized.bytes(), "Exif".getBytes(StandardCharsets.US_ASCII)))
                .isFalse();
    }

    @Test
    void unsupportedOrientationValueIsIgnored() throws Exception {
        SanitizedImage sanitized = sanitize(
                "invalid-orientation.jpg", "image/jpeg", jpegWithOrientation(9, false));
        BufferedImage output = decode(sanitized.bytes());

        assertThat(output.getWidth()).isEqualTo(WIDTH);
        assertThat(output.getHeight()).isEqualTo(HEIGHT);
        assertThat(quadrants(output)).containsExactly(EXPECTED_QUADRANTS[0]);
    }

    @Test
    void webpExifOrientationIsAppliedAndMetadataIsStripped() throws Exception {
        SanitizedImage sanitized = sanitize(
                "oriented.webp", "image/webp", webpWithOrientation(6, false));
        BufferedImage output = decode(sanitized.bytes());

        assertThat(sanitized.extension()).isEqualTo("webp");
        assertThat(sanitized.contentType()).isEqualTo("image/webp");
        assertThat(output.getWidth()).isEqualTo(HEIGHT);
        assertThat(output.getHeight()).isEqualTo(WIDTH);
        assertThat(quadrants(output)).containsExactly(EXPECTED_QUADRANTS[5]);
        assertThat(contains(sanitized.bytes(), "EXIF".getBytes(StandardCharsets.US_ASCII)))
                .isFalse();
    }

    @Test
    void pngRegressionPreservesFormatDimensionsAndPixels() throws Exception {
        SanitizedImage sanitized = sanitize("plain.png", "image/png", imageBytes("png", quadrantsImage()));
        BufferedImage output = decode(sanitized.bytes());

        assertThat(sanitized.extension()).isEqualTo("png");
        assertThat(sanitized.contentType()).isEqualTo("image/png");
        assertThat(output.getWidth()).isEqualTo(WIDTH);
        assertThat(output.getHeight()).isEqualTo(HEIGHT);
        assertThat(quadrants(output)).containsExactly(EXPECTED_QUADRANTS[0]);
    }

    @Test
    void webpRegressionPreservesFormatAndDimensionsWithoutMetadata() throws Exception {
        SanitizedImage sanitized = sanitize("plain.webp", "image/webp", imageBytes("webp", quadrantsImage()));
        BufferedImage output = decode(sanitized.bytes());

        assertThat(sanitized.extension()).isEqualTo("webp");
        assertThat(sanitized.contentType()).isEqualTo("image/webp");
        assertThat(output.getWidth()).isEqualTo(WIDTH);
        assertThat(output.getHeight()).isEqualTo(HEIGHT);
        assertThat(quadrants(output)).containsExactly(EXPECTED_QUADRANTS[0]);
        assertThat(contains(sanitized.bytes(), "EXIF".getBytes(StandardCharsets.US_ASCII)))
                .isFalse();
    }

    @Test
    void oversizedFileIsRejectedBeforeDecoding() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "huge.jpg", "image/jpeg", new byte[PropertyImageProcessor.MAX_FILE_BYTES + 1]);

        assertThatThrownBy(() -> processor.sanitize(file))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    @Test
    void dimensionLimitsAreAppliedToTheCorrectedImage() throws Exception {
        BufferedImage tooSmall = new BufferedImage(180, WIDTH, BufferedImage.TYPE_INT_RGB);
        byte[] source = jpegWithOrientation(6, false, imageBytes("jpg", tooSmall));

        assertThatThrownBy(() -> sanitize("too-small.jpg", "image/jpeg", source))
                .isInstanceOf(ApiException.class)
                .extracting(exception -> ((ApiException) exception).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_ERROR);
    }

    private SanitizedImage sanitize(String filename, String contentType, byte[] bytes) {
        return processor.sanitize(new MockMultipartFile("file", filename, contentType, bytes));
    }

    private static BufferedImage decode(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).as("sanitized image is decodable").isNotNull();
        return image;
    }

    private static BufferedImage quadrantsImage() {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        int[] red = {220, 30, 30};
        int[] green = {30, 210, 30};
        int[] blue = {30, 30, 220};
        int[] yellow = {220, 220, 30};

        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                int[] color = x < WIDTH / 2
                        ? (y < HEIGHT / 2 ? red : blue)
                        : (y < HEIGHT / 2 ? green : yellow);
                image.setRGB(x, y, (color[0] << 16) | (color[1] << 8) | color[2]);
            }
        }
        return image;
    }

    private static int[] quadrants(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int[][] points = {
                {width / 4, height / 4},
                {width * 3 / 4, height / 4},
                {width / 4, height * 3 / 4},
                {width * 3 / 4, height * 3 / 4}
        };
        int[] result = new int[4];

        int[][] prototypes = {{220, 30, 30}, {30, 210, 30}, {30, 30, 220}, {220, 220, 30}};

        for (int index = 0; index < points.length; index++) {
            int rgb = image.getRGB(points[index][0], points[index][1]);
            int r = (rgb >> 16) & 0xff;
            int g = (rgb >> 8) & 0xff;
            int b = rgb & 0xff;
            long bestDistance = Long.MAX_VALUE;
            int best = RED;

            for (int color = 0; color < prototypes.length; color++) {
                int dr = r - prototypes[color][0];
                int dg = g - prototypes[color][1];
                int db = b - prototypes[color][2];
                long distance = (long) dr * dr + (long) dg * dg + (long) db * db;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = color;
                }
            }
            result[index] = best;
        }
        return result;
    }

    private static byte[] imageBytes(String format, BufferedImage image) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, format, output))
                .as("%s ImageIO writer is available", format)
                .isTrue();
        return output.toByteArray();
    }

    private static byte[] jpegWithOrientation(int orientation, boolean bigEndian) throws Exception {
        return jpegWithOrientation(orientation, bigEndian, imageBytes("jpg", quadrantsImage()));
    }

    private static byte[] jpegWithOrientation(
            int orientation, boolean bigEndian, byte[] base) throws Exception {
        byte[] payload = exifPayload(orientation, bigEndian);
        ByteArrayOutputStream segment = new ByteArrayOutputStream();
        segment.write(0xff);
        segment.write(0xe1);
        writeU16(segment, payload.length + 2, true);
        segment.writeBytes(payload);
        byte[] segmentBytes = segment.toByteArray();

        byte[] result = new byte[base.length + segmentBytes.length];
        System.arraycopy(base, 0, result, 0, 2);
        System.arraycopy(segmentBytes, 0, result, 2, segmentBytes.length);
        System.arraycopy(base, 2, result, 2 + segmentBytes.length, base.length - 2);
        return result;
    }

    private static byte[] webpWithOrientation(int orientation, boolean bigEndian) throws Exception {
        byte[] base = imageBytes("webp", quadrantsImage());
        byte[] payload = exifPayload(orientation, bigEndian);
        ByteArrayOutputStream chunk = new ByteArrayOutputStream();
        chunk.writeBytes("EXIF".getBytes(StandardCharsets.US_ASCII));
        writeU32(chunk, payload.length, false);
        chunk.writeBytes(payload);
        if ((payload.length & 1) != 0) {
            chunk.write(0);
        }
        byte[] chunkBytes = chunk.toByteArray();

        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.writeBytes("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeU32(result, 4 + base.length - 12 + chunkBytes.length, false);
        result.writeBytes("WEBP".getBytes(StandardCharsets.US_ASCII));
        result.write(base, 12, base.length - 12);
        result.writeBytes(chunkBytes);
        return result.toByteArray();
    }

    private static byte[] exifPayload(int orientation, boolean bigEndian) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.writeBytes("Exif\0\0".getBytes(StandardCharsets.US_ASCII));
        output.writeBytes(bigEndian
                ? new byte[] {'M', 'M'}
                : new byte[] {'I', 'I'});
        writeU16(output, 42, bigEndian);
        writeU32(output, 8, bigEndian);
        writeU16(output, 1, bigEndian);
        writeU16(output, 0x0112, bigEndian);
        writeU16(output, 3, bigEndian);
        writeU32(output, 1, bigEndian);
        writeU16(output, orientation, bigEndian);
        output.write(0);
        writeU32(output, 0, bigEndian);
        return output.toByteArray();
    }

    private static void writeU16(ByteArrayOutputStream output, int value, boolean bigEndian) {
        if (bigEndian) {
            output.write((value >>> 8) & 0xff);
            output.write(value & 0xff);
        } else {
            output.write(value & 0xff);
            output.write((value >>> 8) & 0xff);
        }
    }

    private static void writeU32(ByteArrayOutputStream output, long value, boolean bigEndian) {
        int[] shifts = bigEndian ? new int[] {24, 16, 8, 0} : new int[] {0, 8, 16, 24};
        for (int shift : shifts) {
            output.write((int) ((value >>> shift) & 0xff));
        }
    }

    private static boolean contains(byte[] haystack, byte[] needle) {
        outer:
        for (int offset = 0; offset <= haystack.length - needle.length; offset++) {
            for (int index = 0; index < needle.length; index++) {
                if (haystack[offset + index] != needle[index]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }
}
