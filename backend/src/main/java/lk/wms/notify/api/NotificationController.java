package lk.wms.notify.api;

import jakarta.validation.constraints.Size;
import java.util.Map;
import lk.wms.common.security.CurrentUser;
import lk.wms.common.web.PageResponse;
import lk.wms.notify.service.NotificationService;
import org.springframework.data.domain.Sort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** My notifications (v1.2). Everyone signed in has their own and can only reach their own (SEC-09/10). */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public NotificationService.Inbox mine(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return notifications.mine(CurrentUser.require(), PageResponse.request(page, size, null, Map.of(), Sort.unsorted()));
    }

    @PostMapping("/{id}/read")
    @PreAuthorize("isAuthenticated()")
    public NotificationService.View read(@PathVariable @Size(max = 26) String id) {
        return notifications.read(CurrentUser.require(), id);
    }

    @PostMapping("/read-all")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Integer> readAll() {
        return Map.of("read", notifications.readAll(CurrentUser.require()));
    }
}
