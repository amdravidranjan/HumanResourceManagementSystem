package com.ssn.hrms.notification;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.ssn.hrms.common.CurrentUser;
import com.ssn.hrms.config.NodeOnly;

@NodeOnly
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping
    public Map<String, Object> mine(@RequestAttribute("user") CurrentUser user, @RequestParam(defaultValue = "20") int limit) {
        return notifications.recent(user.employeeId(), limit);
    }

    @PostMapping("/{id}/read")
    public Map<String, Object> read(@RequestAttribute("user") CurrentUser user, @PathVariable String id) {
        notifications.markRead(user.employeeId(), id);
        return Map.of("ok", true);
    }

    @PostMapping("/read-all")
    public Map<String, Object> readAll(@RequestAttribute("user") CurrentUser user) {
        return Map.of("updated", notifications.markAllRead(user.employeeId()));
    }
}
