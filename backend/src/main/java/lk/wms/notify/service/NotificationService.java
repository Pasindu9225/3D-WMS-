package lk.wms.notify.service;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lk.wms.auth.service.UserService;
import lk.wms.common.errors.ApiException;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.tenant.TenantContext;
import lk.wms.layout.service.WarehouseService;
import lk.wms.notify.domain.Notification;
import lk.wms.notify.repo.NotificationRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * In-app notifications (v1.2 task 5). Other modules call {@link #taskAssigned} and {@link #toWatchers}; each person
 * reads and marks only their own. After the row is committed, an empty "something new" push goes to
 * /topic/user/{userPublicId}/notifications; the app then reloads the list over HTTPS.
 */
@Service
public class NotificationService {

    public record View(String id, String kind, Map<String, Object> params, String link, Instant createdAt, boolean read) {
    }

    public record Inbox(List<View> items, int page, int size, long total, long unread) {
    }

    /** Published inside the writing transaction; pushed only once it commits. */
    public record Added(long userId) {
    }

    public record Push(String type) {
    }

    /** Who sees warehouse alerts (low stock, expiry): the people who see the Dashboard of that warehouse. */
    static final String WATCHERS = "reports:read";

    private final NotificationRepository notifications;
    private final UserService users;
    private final WarehouseService warehouses;
    private final ApplicationEventPublisher events;
    private final SimpMessagingTemplate broker;
    private final JsonMapper json;

    public NotificationService(NotificationRepository notifications, UserService users, WarehouseService warehouses,
            ApplicationEventPublisher events, SimpMessagingTemplate broker, JsonMapper json) {
        this.notifications = notifications;
        this.users = users;
        this.warehouses = warehouses;
        this.events = events;
        this.broker = broker;
        this.json = json;
    }

    /** A pick list or a wave was given to this person ("PICK_LIST" or "WAVE"). Runs in the caller's transaction. */
    @Transactional
    public void taskAssigned(long userId, String task, String number, String link) {
        add(userId, Notification.Kind.TASK_ASSIGNED, Map.of("task", task, "number", number), link,
                "TASK:" + task + ":" + number + ":" + lk.wms.common.Ulid.next()); // every new assignment counts
    }

    /** To everyone who sees this warehouse's Dashboard; once per person and dedupe key. Returns how many got it. */
    @Transactional
    public int toWatchers(long warehouseId, Notification.Kind kind, Map<String, Object> params, String link, String dedupe) {
        Set<Long> here = new HashSet<>(warehouses.userIdsIn(warehouseId));
        int sent = 0;
        for (UserService.Holder h : users.holdersOf(TenantContext.require(), WATCHERS)) {
            if ((h.admin() || here.contains(h.id())) && add(h.id(), kind, params, link, dedupe)) {
                sent++;
            }
        }
        return sent;
    }

    private boolean add(long userId, Notification.Kind kind, Map<String, Object> params, String link, String dedupe) {
        if (notifications.existsByUserIdAndDedupe(userId, dedupe)) {
            return false;
        }
        notifications.save(new Notification(userId, kind, json.writeValueAsString(params), link, dedupe));
        events.publishEvent(new Added(userId));
        return true;
    }

    @TransactionalEventListener
    void push(Added e) {
        UserService.UserView u = users.viewsByInternalId(List.of(e.userId())).get(e.userId());
        if (u != null) {
            broker.convertAndSend(topic(u.id()), new Push("NOTIFICATION"));
        }
    }

    public static String topic(String userPublicId) {
        return "/topic/user/" + userPublicId + "/notifications";
    }

    // ---------------------------------------------------------------- my notifications

    @Transactional(readOnly = true)
    public Inbox mine(CurrentUser cu, Pageable pageable) {
        Page<Notification> page = notifications.findByUserIdOrderByCreatedAtDescIdDesc(cu.userId(), pageable);
        return new Inbox(page.map(this::view).getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                notifications.countByUserIdAndReadAtIsNull(cu.userId()));
    }

    @Transactional
    public View read(CurrentUser cu, String publicId) {
        Notification n = notifications.findByPublicIdAndUserId(publicId, cu.userId())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "Notification not found"));
        n.markRead();
        notifications.flush();
        return view(n);
    }

    @Transactional
    public int readAll(CurrentUser cu) {
        return notifications.readAll(cu.userId(), Instant.now());
    }

    /** Old notifications go after 90 days (the daily job). */
    @Transactional
    public int deleteOld() {
        return notifications.deleteOlderThan(Instant.now().minus(java.time.Duration.ofDays(90)));
    }

    private View view(Notification n) {
        Map<String, Object> params = json.readValue(n.getParams(), new TypeReference<Map<String, Object>>() { });
        return new View(n.getPublicId(), n.getKind().name(), params, n.getLink(), n.getCreatedAt(), n.getReadAt() != null);
    }
}
