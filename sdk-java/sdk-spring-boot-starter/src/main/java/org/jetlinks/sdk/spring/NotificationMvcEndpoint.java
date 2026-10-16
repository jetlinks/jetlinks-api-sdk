package org.jetlinks.sdk.spring;

import org.jetlinks.sdk.notification.NotificationReceiver;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * Servlet 通知适配；复用验签和可靠受理流程，阻塞仅限 Servlet 边界。
 *
 * @since 2.0
 */
@RestController
public final class NotificationMvcEndpoint {
    private final NotificationReceiver receiver;

    public NotificationMvcEndpoint(NotificationReceiver receiver) {
        this.receiver = receiver;
    }

    @PostMapping("${jetlinks.sdk.notification-path:/jetlinks/events}")
    public ResponseEntity<Void> receive(
            jakarta.servlet.http.HttpServletRequest request, @RequestHeader HttpHeaders headers) {
        try {
            receiver.receive(headers, ServletBodies.read(request)).block(Duration.ofSeconds(30));
            return ResponseEntity.noContent().build();
        } catch (RuntimeException | java.io.IOException e) {
            return ResponseEntity.status(EndpointErrors.notificationStatus(e)).build();
        }
    }
}
