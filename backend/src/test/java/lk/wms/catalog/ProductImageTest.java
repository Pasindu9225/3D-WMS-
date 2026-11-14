package lk.wms.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import lk.wms.ApiTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/** v0.4 task 1b: product pictures and the upload rules SEC-40..45. */
class ProductImageTest extends ApiTest {

    long tenant;
    Cookie admin;
    String product;

    @BeforeEach
    void productWithoutPicture() throws Exception {
        tenant = newTenant(1);
        admin = login(newUser(tenant, "TENANT_ADMIN"));
        product = read(send(admin, post("/api/v1/products").content("{\"sku\":\"OIL-1L\",\"name\":\"Coconut oil\","
                + "\"baseUom\":\"EA\",\"trackBatch\":false,\"trackExpiry\":false,\"units\":[]}"))
                .andExpect(status().isCreated()), "$.id");
    }

    private ResultActions upload(Cookie who, String name, String type, byte[] bytes) throws Exception {
        return mvc.perform(multipart("/api/v1/products/" + product + "/image")
                .file(new MockMultipartFile("file", name, type, bytes)).with(csrf()).cookie(who));
    }

    static byte[] image(int w, int h, String format) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    /** A JPEG with a comment segment right after the start marker, standing in for EXIF GPS data. */
    static byte[] jpegWithSecret() throws Exception {
        byte[] jpeg = image(2400, 1200, "jpeg");
        byte[] text = "GPS 6.9271N 79.8612E SECRET".getBytes(StandardCharsets.US_ASCII);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2); // FF D8
        out.write(0xFF);
        out.write(0xFE); // COM
        out.write((text.length + 2) >> 8);
        out.write((text.length + 2) & 0xFF);
        out.write(text);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    private long filesOnDisk() throws Exception {
        try (Stream<java.nio.file.Path> s = Files.walk(FILES)) {
            return s.filter(p -> p.toString().endsWith(".jpg")).count();
        }
    }

    @Test
    void aPhotoIsReEncodedResizedStrippedAndServedSafely() throws Exception {
        upload(admin, "oil.jpg", "image/jpeg", jpegWithSecret()).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasImage").value(true));
        byte[] served = send(admin, get("/api/v1/products/" + product + "/image")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/jpeg"))
                .andExpect(header().string("Content-Disposition", "inline; filename=\"OIL-1L.jpg\""))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn().getResponse().getContentAsByteArray();
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(served));
        assertThat(img.getWidth()).isEqualTo(1200); // was 2400 x 1200
        assertThat(img.getHeight()).isEqualTo(600);
        assertThat(new String(served, StandardCharsets.ISO_8859_1)).doesNotContain("SECRET"); // the original is gone
    }

    @Test
    void onlyRealJpegAndPngWithMatchingNameAndTypeAreAccepted() throws Exception {
        long before = filesOnDisk(); // the folder is shared by all tests
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        upload(admin, "pic.png", "image/png", html).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>".getBytes(StandardCharsets.UTF_8);
        upload(admin, "pic.svg", "image/svg+xml", svg).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
        byte[] png = image(10, 10, "png");
        upload(admin, "pic.jpg", "image/jpeg", png).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_MISMATCH"));
        upload(admin, "pic.png", "image/jpeg", png).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_MISMATCH"));
        upload(admin, "pic.webp", "image/webp", png).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_TYPE_NOT_ALLOWED"));
        upload(admin, "pic.png", "image/png", new byte[0]).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FILE_EMPTY"));
        assertThat(filesOnDisk()).isEqualTo(before); // nothing was written
    }

    @Test
    void hugeOrBrokenPicturesAreRefusedWithoutDecodingThem() throws Exception {
        upload(admin, "wide.png", "image/png", image(17_000, 1, "png")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_TOO_LARGE"));
        byte[] broken = image(50, 50, "png");
        for (int i = 40; i < broken.length; i++) {
            broken[i] = 7; // valid signature, garbage after it
        }
        upload(admin, "broken.png", "image/png", broken).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IMAGE_UNREADABLE"));
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(image(5, 5, "png"), 0, big, 0, 8);
        upload(admin, "big.png", "image/png", big).andExpect(status().isContentTooLarge())
                .andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void replacingOrRemovingThePictureDeletesTheOldFile() throws Exception {
        long before = filesOnDisk();
        upload(admin, "a.png", "image/png", image(300, 200, "png")).andExpect(status().isOk());
        upload(admin, "b.png", "image/png", image(200, 300, "png")).andExpect(status().isOk());
        assertThat(filesOnDisk()).isEqualTo(before + 1); // the first one is gone
        mvc.perform(delete("/api/v1/products/" + product + "/image").with(csrf()).cookie(admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.hasImage").value(false));
        assertThat(filesOnDisk()).isEqualTo(before);
        send(admin, get("/api/v1/products/" + product + "/image")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("IMAGE_NOT_FOUND"));
    }

    @Test
    void picturesAreOnlyForTheCompanyAndOnlyManagersChangeThem() throws Exception {
        upload(admin, "a.png", "image/png", image(30, 20, "png")).andExpect(status().isOk());
        Cookie viewer = login(newUser(tenant, "VIEWER"));
        send(viewer, get("/api/v1/products/" + product + "/image")).andExpect(status().isOk());
        upload(viewer, "b.png", "image/png", image(30, 20, "png")).andExpect(status().isForbidden());
        Cookie other = login(newUser(newTenant(1), "TENANT_ADMIN"));
        send(other, get("/api/v1/products/" + product + "/image")).andExpect(status().isNotFound());
        upload(other, "c.png", "image/png", image(30, 20, "png")).andExpect(status().isNotFound());
    }

    @Test
    void uploadsAreLimitedTo20PerMinutePerUser() throws Exception {
        Cookie busy = login(newUser(tenant, "TENANT_ADMIN"));
        byte[] png = image(10, 10, "png");
        for (int i = 0; i < 20; i++) {
            upload(busy, "p.png", "image/png", png).andExpect(status().isOk());
        }
        upload(busy, "p.png", "image/png", png).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_UPLOADS"));
        upload(admin, "p.png", "image/png", png).andExpect(status().isOk()); // someone else is not affected
    }

    @Test
    void theUploadAnswerCarriesTheNewVersionSoTheNextSaveIsAccepted() throws Exception {
        int version = read(upload(admin, "a.png", "image/png", image(30, 20, "png")).andExpect(status().isOk()), "$.version");
        org.assertj.core.api.Assertions.assertThat(version).isEqualTo(1);
        send(admin, org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/products/" + product)
                .content("{\"name\":\"Coconut oil (bottle)\",\"baseUom\":\"EA\",\"trackBatch\":false,\"trackExpiry\":false,"
                        + "\"units\":[],\"version\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasImage").value(true));
    }
}
