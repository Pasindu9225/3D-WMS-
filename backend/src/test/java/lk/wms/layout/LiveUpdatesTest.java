package lk.wms.layout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.servlet.http.Cookie;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lk.wms.ApiTest;
import lk.wms.layout.api.LiveConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.nio.charset.StandardCharsets;
import org.springframework.messaging.converter.SimpleMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * v0.3 task 7: live bin status over STOMP, against the real server (random port) with real cookies.
 * Warehouse WH1 with one published rack (12 bins).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LiveUpdatesTest extends ApiTest {

    @LocalServerPort
    int port;

    @Autowired
    LiveConfig live;

    long tenant;
    Cookie admin;
    lk.wms.auth.domain.AppUser adminUser;

    @Autowired
    lk.wms.notify.service.NotificationService notifications;
    String warehouse;
    String binId;
    final WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    final BlockingQueue<String> received = new LinkedBlockingQueue<>();
    final BlockingQueue<String> errors = new LinkedBlockingQueue<>();

    @BeforeEach
    void publishedWarehouse() throws Exception {
        client.setMessageConverter(new SimpleMessageConverter()); // raw bytes: we read the JSON ourselves
        tenant = newTenant(2);
        adminUser = newUser(tenant, "TENANT_ADMIN");
        admin = login(adminUser);
        warehouse = read(send(admin, post("/api/v1/warehouses").content(
                "{\"code\":\"WH1\",\"name\":\"Main\",\"widthM\":40,\"lengthM\":20,\"wallHeightM\":8}"))
                .andExpect(status().isCreated()), "$.id");
        String type = read(send(admin, post("/api/v1/rack-templates").content("{\"code\":\"PAL\",\"name\":\"Pallet\","
                + "\"widthMm\":2700,\"depthMm\":1100,\"heightMm\":6000,\"levels\":4,\"binsPerLevel\":3,"
                + "\"maxWeightPerLevelG\":1000000}")).andExpect(status().isCreated()), "$.id");
        String floor = "[{\"x\":0,\"y\":0},{\"x\":40000,\"y\":0},{\"x\":40000,\"y\":20000},{\"x\":0,\"y\":20000}]";
        send(admin, put("/api/v1/warehouses/" + warehouse + "/layout").content("{\"baseRevision\":0,\"layout\":{\"floor\":"
                + floor + ",\"openings\":[],\"zones\":[{\"code\":\"A\",\"type\":\"STORAGE\",\"name\":\"A\",\"polygon\":" + floor
                + "}],\"racks\":[{\"code\":\"R01\",\"templateId\":\"" + type
                + "\",\"zone\":\"A\",\"x\":5000,\"y\":5000,\"rotation\":0}],\"settings\":{\"minAisleMm\":1200}}}"))
                .andExpect(status().isOk());
        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}"))
                .andExpect(status().isOk());
        List<String> ids = com.jayway.jsonpath.JsonPath.read(send(admin, get("/api/v1/warehouses/" + warehouse + "/scene"))
                .andReturn().getResponse().getContentAsString(), "$.bins.ids");
        binId = ids.get(0);
    }

    @AfterEach
    void stop() {
        client.stop();
    }

    @Test
    void aBinEditIsPushedToEveryoneWatchingTheWarehouseAfterCommit() throws Exception {
        StompSession session = connect(admin, "http://localhost:" + port);
        subscribe(session, "/topic/wh/" + warehouse + "/bins");
        Thread.sleep(300); // let the subscription register before the edit
        send(admin, patch("/api/v1/bins/" + binId).content("{\"blocked\":true,\"pickFace\":true}")).andExpect(status().isOk());
        String push = received.poll(5, TimeUnit.SECONDS);
        assertThat(push).as("errors: %s", errors).contains("\"type\":\"BIN\"", "\"id\":\"" + binId + "\"", "\"flags\":3");

        send(admin, post("/api/v1/warehouses/" + warehouse + "/layout/publish").content("{\"baseRevision\":1}"));
        // publishing again needs a draft; a refused edit must not push anything
        send(admin, patch("/api/v1/bins/" + binId).content("{\"maxWeightG\":0}")).andExpect(status().isBadRequest());
        assertThat(received.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void anotherCompanyCannotListenToTheWarehouse() throws Exception {
        Cookie otherAdmin = login(newUser(newTenant(3), "TENANT_ADMIN"));
        StompSession session = connect(otherAdmin, "http://localhost:" + port);
        subscribe(session, "/topic/wh/" + warehouse + "/bins");
        assertThat(errors.poll(5, TimeUnit.SECONDS)).isNotNull(); // ERROR frame, socket closed
        send(admin, patch("/api/v1/bins/" + binId).content("{\"blocked\":true}")).andExpect(status().isOk());
        assertThat(received.poll(1, TimeUnit.SECONDS)).isNull();
    }

    @Test
    void myNotificationsArePushedToMeOnlyAfterCommit() throws Exception {
        StompSession session = connect(admin, "http://localhost:" + port);
        subscribe(session, "/topic/user/" + adminUser.getPublicId() + "/notifications");
        Thread.sleep(300);
        lk.wms.common.tenant.TenantContext.callAs(tenant, () -> {
            notifications.taskAssigned(adminUser.getId(), "PICK_LIST", "PL-000001", "/worker/pick/x");
            return null;
        });
        assertThat(received.poll(5, TimeUnit.SECONDS)).as("errors: %s", errors).isEqualTo("{\"type\":\"NOTIFICATION\"}");
        session.disconnect();
        Thread.sleep(300); // the socket-limit test counts open sockets
    }

    @Test
    void nobodyCanListenToSomeoneElsesNotifications() throws Exception {
        lk.wms.auth.domain.AppUser colleague = newUser(tenant, "PICKER");
        StompSession session = connect(admin, "http://localhost:" + port); // even a company admin
        subscribe(session, "/topic/user/" + colleague.getPublicId() + "/notifications");
        assertThat(errors.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void otherTopicsAndSendingAreRefused() throws Exception {
        StompSession session = connect(admin, "http://localhost:" + port);
        subscribe(session, "/topic/everything");
        assertThat(errors.poll(5, TimeUnit.SECONDS)).isNotNull();

        StompSession again = connect(admin, "http://localhost:" + port);
        again.send("/topic/wh/" + warehouse + "/bins", "{\"type\":\"BIN\",\"id\":\"x\",\"flags\":1}".getBytes(StandardCharsets.UTF_8));
        assertThat(errors.poll(5, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void foreignOriginsAndMissingCookiesCannotConnect() {
        assertThatThrownBy(() -> connect(admin, "https://evil.example")).hasMessageContaining("403");
        assertThatThrownBy(() -> connect(null, "http://localhost:" + port)).isNotNull();
    }

    @Test
    void signingOutClosesTheSocketAtTheNextCheck() throws Exception {
        connect(admin, "http://localhost:" + port);
        Thread.sleep(300);
        live.closeStaleSockets();
        assertThat(errors.poll(500, TimeUnit.MILLISECONDS)).isNull(); // still signed in: kept open
        send(admin, post("/api/v1/auth/logout")).andExpect(status().is2xxSuccessful());
        live.closeStaleSockets();
        assertThat(errors.poll(5, TimeUnit.SECONDS)).isNotNull(); // closed; the browser must sign in again
    }

    @Test
    void oneSignInGetsAtMostFiveSockets() throws Exception {
        for (int i = 0; i < 5; i++) {
            connect(admin, "http://localhost:" + port);
        }
        Thread.sleep(300);
        assertThat(live.openSockets()).isEqualTo(5);
        assertThatThrownBy(() -> connect(admin, "http://localhost:" + port)) // the sixth is closed at once
                .hasMessageContaining("Connection closed");
        assertThat(live.openSockets()).isEqualTo(5);
    }

    private StompSession connect(Cookie cookie, String origin) throws Exception {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        headers.setOrigin(origin);
        if (cookie != null) {
            headers.add("Cookie", cookie.getName() + "=" + cookie.getValue());
        }
        return client.connectAsync("ws://localhost:" + port + "/ws", headers, new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders h, Object payload) {
                errors.add(String.valueOf(payload)); // ERROR frames arrive here
            }

            @Override
            public void handleException(StompSession s, StompCommand c, StompHeaders h, byte[] p, Throwable e) {
                errors.add(String.valueOf(e));
            }

            @Override
            public void handleTransportError(StompSession s, Throwable e) {
                errors.add(String.valueOf(e));
            }
        }).get(5, TimeUnit.SECONDS);
    }

    private void subscribe(StompSession session, String topic) {
        session.subscribe(topic, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return byte[].class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add(new String((byte[]) payload, StandardCharsets.UTF_8));
            }
        });
    }
}
