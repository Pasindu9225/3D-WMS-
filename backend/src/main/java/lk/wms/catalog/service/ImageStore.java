package lk.wms.catalog.service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import lk.wms.common.errors.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Uploaded images (SEC-40..44). Only JPEG and PNG are accepted; the extension, the declared type and the first
 * bytes must all agree; the picture is decoded at reduced resolution (so a 100-megapixel "image bomb" cannot use up
 * memory), resized to at most 1200 px and written as a fresh JPEG. What was uploaded is never stored or served,
 * which drops EXIF (GPS), comments and anything hidden after the image data.
 */
@Component
public class ImageStore {

    /** Stored result: relative path under the files folder, size and pixel dimensions. */
    public record Stored(String path, int sizeBytes, int width, int height) {
    }

    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_SIDE_IN = 16_000; // larger than any phone camera; beyond this it is a bomb, not a photo
    static final int MAX_SIDE_OUT = 1200;

    private final Path root;

    public ImageStore(@Value("${wms.files.dir}") String dir) {
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    public Stored store(long tenantId, String filename, String declaredType, byte[] bytes) {
        if (bytes.length == 0) {
            throw bad("FILE_EMPTY", "The file is empty");
        }
        if (bytes.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "FILE_TOO_LARGE", "Files can be at most 5 MB");
        }
        String byExtension = typeOfExtension(filename);
        String byBytes = typeOfBytes(bytes);
        if (byExtension == null || byBytes == null) {
            throw bad("FILE_TYPE_NOT_ALLOWED", "Only JPEG and PNG pictures are allowed");
        }
        String declared = declaredType == null ? "" : declaredType.toLowerCase(Locale.ROOT).split(";")[0].trim();
        if (!byExtension.equals(byBytes) || !declared.equals(byBytes)) {
            throw bad("FILE_TYPE_MISMATCH", "The file name, type and content do not match");
        }
        BufferedImage image = decode(bytes, byBytes.equals("image/png") ? "png" : "jpeg");
        BufferedImage fitted = fit(image);
        byte[] jpeg = encodeJpeg(fitted);
        String path = tenantId + "/" + UUID.randomUUID() + ".jpg";
        write(path, jpeg);
        return new Stored(path, jpeg.length, fitted.getWidth(), fitted.getHeight());
    }

    public byte[] read(String path) {
        try {
            return Files.readAllBytes(resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public void delete(String path) {
        try {
            Files.deleteIfExists(resolve(path));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String typeOfExtension(String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        return name.endsWith(".png") ? "image/png" : null;
    }

    /** Magic bytes (SEC-41): JPEG starts FF D8 FF, PNG with its 8-byte signature. SVG/HTML/anything else: null. */
    static String typeOfBytes(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
        if (b.length >= 8) {
            for (int i = 0; i < 8; i++) {
                if (b[i] != png[i]) {
                    return null;
                }
            }
            return "image/png";
        }
        return null;
    }

    /** Reads the size from the header first, then decodes with subsampling so memory stays small. */
    private static BufferedImage decode(byte[] bytes, String format) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(format);
            if (!readers.hasNext()) {
                throw bad("IMAGE_UNREADABLE", "This picture cannot be read");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_SIDE_IN || h > MAX_SIDE_IN) {
                    throw bad("IMAGE_TOO_LARGE", "Pictures can be at most 16,000 pixels on a side");
                }
                ImageReadParam param = reader.getDefaultReadParam();
                int step = Math.max(1, Math.max(w, h) / MAX_SIDE_OUT); // e.g. 4000 px -> every 3rd pixel
                param.setSourceSubsampling(step, step, 0, 0);
                BufferedImage image = reader.read(0, param);
                if (image == null) {
                    throw bad("IMAGE_UNREADABLE", "This picture cannot be read");
                }
                return image;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw bad("IMAGE_UNREADABLE", "This picture cannot be read");
        }
    }

    /** At most 1200 px on the long side, on white (PNG transparency has no meaning in a JPEG). */
    private static BufferedImage fit(BufferedImage src) {
        double scale = Math.min(1.0, MAX_SIDE_OUT / (double) Math.max(src.getWidth(), src.getHeight()));
        int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
        int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static byte[] encodeJpeg(BufferedImage image) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    /** Written to a temp file and moved into place, so a half-written file is never served. */
    private void write(String path, byte[] bytes) {
        Path target = resolve(path);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), "upload-", ".tmp");
            Files.write(tmp, bytes);
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Paths come from our own database, but stay inside the files folder whatever they contain. */
    private Path resolve(String path) {
        Path p = root.resolve(path).normalize();
        if (!p.startsWith(root)) {
            throw new IllegalStateException("file path outside the files folder");
        }
        return p;
    }

    private static ApiException bad(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }
}
