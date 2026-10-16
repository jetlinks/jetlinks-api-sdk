package org.jetlinks.sdk.spring;

import org.jetlinks.sdk.command.CommandService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

/**
 * Servlet async streaming adapter; closing or disconnecting cancels command output and releases its
 * budget. @since 2.0
 */
@RestController
public final class CommandMvcEndpoint {
    private final CommandService service;

    public CommandMvcEndpoint(CommandService service) {
        this.service = service;
    }

    @RequestMapping(
            value = {
                "${jetlinks.sdk.command-base-path:/api}/capabilities",
                "${jetlinks.sdk.command-base-path:/api}/commands/1/{commandId}"
            },
            method = {RequestMethod.GET, RequestMethod.POST})
    public ResponseEntity<StreamingResponseBody> command(
            HttpServletRequest request, @RequestHeader HttpHeaders headers) {
        try {
            byte[] body = ServletBodies.read(request);
            String target =
                    request.getRequestURI()
                            + (request.getQueryString() == null
                                    ? ""
                                    : "?" + request.getQueryString());
            CommandService.Response result =
                    service.handle(request.getMethod(), target, headers, body)
                            .block(Duration.ofSeconds(30));
            ResponseEntity.BodyBuilder response = ResponseEntity.status(result.status);
            result.headers.forEach(response::header);
            return response.body(
                    output -> {
                        try (Stream<byte[]> stream = result.body.toStream(1)) {
                            java.util.Iterator<byte[]> iterator = stream.iterator();
                            while (iterator.hasNext()) {
                                output.write(iterator.next());
                                output.flush();
                            }
                        } finally {
                            result.close();
                        }
                    });
        } catch (ServletBodies.TooLarge e) {
            return ResponseEntity.status(413).build();
        } catch (SecurityException e) {
            return ResponseEntity.status(401).build();
        } catch (java.io.IOException e) {
            return ResponseEntity.status(400).build();
        }
    }
}
