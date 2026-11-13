package lk.wms;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Base for tests that need the database. One MySQL 8.4 container per test run, set up with the
 * same init script as production, so tests run as wms_app and migrate as wms_flyway (SEC-27).
 */
@SpringBootTest
public abstract class IntegrationTest {

    protected static final String APP_PASSWORD = "app-test-pw";
    protected static final String FLYWAY_PASSWORD = "flyway-test-pw";

    protected static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4")
            .withDatabaseName("wms")
            .withEnv("WMS_APP_PASSWORD", APP_PASSWORD)
            .withEnv("WMS_FLYWAY_PASSWORD", FLYWAY_PASSWORD)
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../deploy/mysql/init/01-wms-users.sh", 0755),
                    "/docker-entrypoint-initdb.d/01-wms-users.sh")
            .withCommand("--skip-log-bin", "--character-set-server=utf8mb4",
                    "--collation-server=utf8mb4_0900_ai_ci");

    private static final Path KEYS = testKeys();

    static {
        MYSQL.start(); // stopped by Testcontainers' Ryuk when the JVM exits
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.password", () -> FLYWAY_PASSWORD);
        registry.add("wms.jwt.private-key", () -> KEYS.resolve("jwt.key").toUri().toString());
        registry.add("wms.jwt.public-key", () -> KEYS.resolve("jwt.pub").toUri().toString());
        // high limits so suites do not trip each other; RateLimitTest checks the limiter directly
        registry.add("wms.rate-limit.global-per-minute", () -> "100000");
        registry.add("wms.rate-limit.login-per-minute", () -> "100000");
        registry.add("wms.rate-limit.user-per-minute", () -> "100000");
    }

    /** Fresh RS256 key pair per test run, so tests never depend on real keys. */
    private static Path testKeys() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            Path dir = Files.createTempDirectory("wms-test-keys");
            Files.writeString(dir.resolve("jwt.key"), pem("PRIVATE KEY", kp.getPrivate().getEncoded()));
            Files.writeString(dir.resolve("jwt.pub"), pem("PUBLIC KEY", kp.getPublic().getEncoded()));
            return dir;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
