package lk.wms;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * v1.0 task 9: the prod profile serves the app on localhost only, health only on a separate localhost port, and logs
 * to a rotating file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "server.port=0", "management.server.port=0", "wms.app.base-url=https://wms.example.lk", "logging.file.name=target/wms-prod-test.log"})
@ActiveProfiles("prod")
class ProductionProfileTest extends IntegrationTest {

    @LocalServerPort
    int appPort;

    @LocalManagementPort
    int managementPort;

    @Value("${server.address}")
    String address;

    @Value("${management.server.address}")
    String managementAddress;

    private static int get(int port, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    @Test
    void appAndHealthListenOnLocalhostOnlyAndHealthIsNotOnTheAppPort() throws Exception {
        assertThat(address).isEqualTo("127.0.0.1");
        assertThat(managementAddress).isEqualTo("127.0.0.1");
        assertThat(managementPort).isNotEqualTo(appPort);
        assertThat(get(managementPort, "/actuator/health")).isEqualTo(200);
        assertThat(get(appPort, "/actuator/health")).isIn(401, 403, 404); // not served where Nginx forwards
        assertThat(get(managementPort, "/actuator/env")).isIn(401, 404); // only health is exposed
    }

    @Test
    void prodFileRotatesLogsAndRequiresTheRealAppAddress() throws Exception {
        Properties prod = new Properties();
        try (var in = Files.newInputStream(Path.of("src/main/resources/application-prod.properties"))) {
            prod.load(in);
        }
        assertThat(prod.getProperty("logging.logback.rollingpolicy.max-file-size")).isEqualTo("20MB");
        assertThat(prod.getProperty("logging.logback.rollingpolicy.max-history")).isEqualTo("14");
        assertThat(prod.getProperty("logging.logback.rollingpolicy.total-size-cap")).isEqualTo("1GB");
        assertThat(prod.getProperty("wms.app.base-url")).isEqualTo("${WMS_APP_BASE_URL}"); // no localhost fallback
    }
}
