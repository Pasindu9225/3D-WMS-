package lk.wms.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import lk.wms.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * F1 (security review), through real Tomcat (MockMvc skips the RemoteIpValve). Behind the local proxy
 * the client IP comes from X-Forwarded-For, so a request forwarded for an outside client is not
 * "localhost" any more and cannot reach the localhost-only health endpoint.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ForwardedClientIpTest extends IntegrationTest {

    @LocalServerPort
    int port;

    @Value("${server.tomcat.remoteip.internal-proxies}")
    String trustedProxies;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void forwardedIpFromTheLocalProxyIsUsed() throws Exception {
        assertThat(health(null)).isEqualTo(200);                // the server itself
        assertThat(health("203.0.113.9")).isIn(401, 403);       // Nginx forwarding an outside client
    }

    @Test
    void onlyTheLocalProxyIsTrusted() {
        // Spring's default also trusts 10/8, 172.16/12 and 192.168/16, so an office PC could forge
        // X-Forwarded-For. Only loopback may act as the proxy.
        assertThat(trustedProxies).isEqualTo("127\\.0\\.0\\.1|0:0:0:0:0:0:0:1");
    }

    private int health(String forwardedFor) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/actuator/health"));
        if (forwardedFor != null) {
            req.header("X-Forwarded-For", forwardedFor);
        }
        return http.send(req.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
