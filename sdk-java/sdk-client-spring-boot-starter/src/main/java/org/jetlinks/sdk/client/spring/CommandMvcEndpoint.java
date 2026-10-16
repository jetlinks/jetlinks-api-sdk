package org.jetlinks.sdk.client.spring;

import jakarta.servlet.http.HttpServletRequest;

import org.jetlinks.sdk.client.command.CommandService;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.util.stream.Stream;

/**
 * Servlet 异步流适配；关闭或断开连接会取消命令输出并释放并发名额。
 *
 * @since 2.0
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
        } catch (RuntimeException | java.io.IOException e) {
            return ResponseEntity.status(EndpointErrors.commandStatus(e)).build();
        }
    }
}
