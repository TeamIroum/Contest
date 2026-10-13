package kr.iroum.inspection.notification;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import kr.iroum.inspection.common.web.CurrentUser;

/** 관리자 알림(기능정의서 OPS-05). service/: 원본에 없던 API다. */
@RestController
public class AdminNotificationController {

    private final NotificationService notifications;

    public AdminNotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/v1/admin/notifications")
    NotificationService.Inbox list(Authentication auth, @RequestParam(required = false) Boolean read) {
        return notifications.list(CurrentUser.of(auth), read);
    }

    @PutMapping("/api/v1/admin/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void read(Authentication auth, @PathVariable UUID id) {
        notifications.markRead(CurrentUser.of(auth), id);
    }

    @PutMapping("/api/v1/admin/notifications/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void readAll(Authentication auth) {
        notifications.markRead(CurrentUser.of(auth), null);
    }
}
