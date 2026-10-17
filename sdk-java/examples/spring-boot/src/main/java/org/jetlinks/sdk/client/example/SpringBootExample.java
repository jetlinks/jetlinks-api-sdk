package org.jetlinks.sdk.client.example;

import org.jetlinks.sdk.client.internal.BoundedBytes;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.*;
import org.springframework.web.server.*;

import java.util.*;

/**
 * Spring Boot WebFlux 启动入口，与独立 Java 示例共用业务实现。
 *
 * @since 2.0
 */
@SpringBootApplication
public class SpringBootExample {
    public static void main(String[] args) throws Exception {
        ExampleConfig config =
                ExampleConfig.load(System.getProperty("example.config", "example.local.json"));
        SpringApplication application = new SpringApplication(SpringBootExample.class);
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("server.port", config.port);
        defaults.put("server.address", config.host);
        application.setDefaultProperties(defaults);
        application.run(args);
    }

    @Bean(destroyMethod = "close")
    ExampleService exampleService() throws Exception {
        ExampleConfig config =
                ExampleConfig.load(System.getProperty("example.config", "example.local.json"));
        ExampleService service = new ExampleService(config);
        service.start();
        return service;
    }

    @Bean
    WebFilter exampleAdapter(ExampleService service) {
        return (exchange, chain) -> {
            ServerHttpRequest request = exchange.getRequest();
            ServerHttpResponse response = exchange.getResponse();
            String target =
                    request.getURI().getRawPath()
                            + (request.getURI().getRawQuery() == null
                                    ? ""
                                    : "?" + request.getURI().getRawQuery());
            return BoundedBytes.collect(
                            request.getBody()
                                    .map(
                                            buffer -> {
                                                byte[] b = new byte[buffer.readableByteCount()];
                                                buffer.read(b);
                                                org.springframework.core.io.buffer.DataBufferUtils
                                                        .release(buffer);
                                                return b;
                                            }),
                            1024 * 1024)
                    .flatMap(
                            body ->
                                    service.handle(
                                            request.getMethod().name(),
                                            target,
                                            request.getHeaders(),
                                            body))
                    .flatMap(
                            result -> {
                                response.setStatusCode(HttpStatusCode.valueOf(result.status));
                                result.headers.forEach((k, v) -> response.getHeaders().set(k, v));
                                return response.writeWith(
                                        result.body.map(
                                                bytes -> response.bufferFactory().wrap(bytes)));
                            });
        };
    }
}
