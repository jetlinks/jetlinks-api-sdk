package org.jetlinks.sdk.spring;

import org.jetlinks.sdk.command.CommandService;
import org.jetlinks.sdk.internal.BoundedBytes;
import org.jetlinks.sdk.notification.*;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.*;
import org.springframework.web.reactive.function.server.*;

import reactor.core.publisher.*;

/**
 * 按当前 Web 技术栈配置协议端点；需要显式提供通知验签/存储或命令服务 Bean。
 *
 * @since 2.0
 */
@Configuration(proxyBeanMethods = false)
public class ReceiverConfiguration {
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean({NotificationVerifier.class, NotificationStore.class})
    NotificationReceiver jetLinksNotificationReceiver(
            NotificationVerifier verifier, NotificationStore store) {
        return new NotificationReceiver(verifier, store);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
    @ConditionalOnClass(RouterFunction.class)
    static class ReactiveEndpoints {
        @Bean
        @ConditionalOnBean({NotificationVerifier.class, NotificationStore.class})
        RouterFunction<ServerResponse> jetLinksNotifications(
                NotificationReceiver receiver, Environment env) {
            return RouterFunctions.route(
                    RequestPredicates.POST(
                            env.getProperty("jetlinks.sdk.notification-path", "/jetlinks/events")),
                    request ->
                            body(request)
                                    .flatMap(
                                            bytes ->
                                                    receiver.receive(
                                                            request.headers().asHttpHeaders(),
                                                            bytes))
                                    .flatMap(accepted -> ServerResponse.noContent().build())
                                    .onErrorResume(
                                            e ->
                                                    ServerResponse.status(
                                                                    EndpointErrors
                                                                            .notificationStatus(e))
                                                            .build()));
        }

        @Bean
        @ConditionalOnBean(CommandService.class)
        RouterFunction<ServerResponse> jetLinksCommands(CommandService commands, Environment env) {
            String base = env.getProperty("jetlinks.sdk.command-base-path", "/api");
            return RouterFunctions.route(
                    RequestPredicates.GET(base + "/capabilities")
                            .or(RequestPredicates.POST(base + "/commands/1/{commandId}")),
                    request ->
                            body(request)
                                    .flatMap(
                                            bytes ->
                                                    commands.handle(
                                                            request.methodName(),
                                                            request.uri().getRawPath()
                                                                    + (request.uri().getRawQuery()
                                                                                    == null
                                                                            ? ""
                                                                            : "?"
                                                                                    + request.uri()
                                                                                            .getRawQuery()),
                                                            request.headers().asHttpHeaders(),
                                                            bytes))
                                    .flatMap(
                                            r ->
                                                    ServerResponse.status(r.status)
                                                            .headers(h -> r.headers.forEach(h::set))
                                                            .body(
                                                                    r.body.doFinally(
                                                                            s -> r.close()),
                                                                    byte[].class))
                                    .onErrorResume(
                                            e ->
                                                    ServerResponse.status(
                                                                    EndpointErrors.commandStatus(e))
                                                            .build()));
        }

        private static Mono<byte[]> body(ServerRequest request) {
            return BoundedBytes.collect(
                    request.bodyToFlux(org.springframework.core.io.buffer.DataBuffer.class)
                            .map(
                                    buffer -> {
                                        byte[] b = new byte[buffer.readableByteCount()];
                                        buffer.read(b);
                                        DataBufferUtils.release(buffer);
                                        return b;
                                    }),
                    1024 * 1024);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(name = "javax.servlet.http.HttpServletRequest")
    static class ServletEndpoints {
        @Bean
        @ConditionalOnBean({NotificationVerifier.class, NotificationStore.class})
        NotificationMvcEndpoint jetLinksNotificationMvc(NotificationReceiver receiver) {
            return new NotificationMvcEndpoint(receiver);
        }

        @Bean
        @ConditionalOnBean(CommandService.class)
        CommandMvcEndpoint jetLinksCommandMvc(CommandService service) {
            return new CommandMvcEndpoint(service);
        }
    }
}
