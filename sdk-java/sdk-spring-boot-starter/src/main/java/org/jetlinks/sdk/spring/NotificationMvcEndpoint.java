package org.jetlinks.sdk.spring;

import org.jetlinks.sdk.notification.NotificationReceiver;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/**
 * Servlet adapter for the same verified/durable notification pipeline; blocking is confined to the
 * servlet boundary. @since 2.0
 */
@RestController
public final class NotificationMvcEndpoint {
    private final NotificationReceiver receiver;

    public NotificationMvcEndpoint(NotificationReceiver receiver) {
        this.receiver = receiver;
    }

    @PostMapping("${jetlinks.sdk.notification-path:/jetlinks/events}")
    public ResponseEntity<Void> receive(
            javax.servlet.http.HttpServletRequest request, @RequestHeader HttpHeaders headers) {
        try {
            receiver.receive(headers, ServletBodies.read(request)).block(Duration.ofSeconds(30));
            return ResponseEntity.noContent().build();
        } catch (ServletBodies.TooLarge e) {
            return ResponseEntity.status(413).build();
        } catch (SecurityException | IllegalArgumentException e) {
            return ResponseEntity.status(401).build();
        } catch (RuntimeException | java.io.IOException e) {
            return ResponseEntity.status(503).build();
        }
    }
}
