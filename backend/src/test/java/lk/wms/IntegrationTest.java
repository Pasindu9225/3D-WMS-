package lk.wms;

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

    static {
        MYSQL.start(); // stopped by Testcontainers' Ryuk when the JVM exits
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.password", () -> FLYWAY_PASSWORD);
    }
}
