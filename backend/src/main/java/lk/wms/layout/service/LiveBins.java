package lk.wms.layout.service;

import java.time.Instant;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Pushes bin changes to everyone watching the warehouse in 3D, only after the change is committed (a rolled
 * back edit must never reach screens). Topic: /topic/wh/{warehouse}/bins.
 */
@Component
public class LiveBins {

    /** A bin's status flags changed (blocked = 1, pick face = 2, same digits as /bin-status). */
    public record BinChanged(String warehouseId, String binId, int flags) {
    }

    /** A new layout was published: viewers reload the scene. */
    public record LayoutPublished(String warehouseId, Instant publishedAt) {
    }

    public record Push(String type, String id, Integer flags, String publishedAt) {
    }

    private final SimpMessagingTemplate broker;

    public LiveBins(SimpMessagingTemplate broker) {
        this.broker = broker;
    }

    @TransactionalEventListener
    void on(BinChanged e) {
        broker.convertAndSend(topic(e.warehouseId()), new Push("BIN", e.binId(), e.flags(), null));
    }

    @TransactionalEventListener
    void on(LayoutPublished e) {
        broker.convertAndSend(topic(e.warehouseId()), new Push("LAYOUT", null, null, e.publishedAt().toString()));
    }

    static String topic(String warehouseId) {
        return "/topic/wh/" + warehouseId + "/bins";
    }
}
