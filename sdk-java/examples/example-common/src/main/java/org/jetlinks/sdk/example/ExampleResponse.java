package org.jetlinks.sdk.example;

import org.jetlinks.sdk.Json;

import reactor.core.publisher.Flux;

import java.util.*;

/**
 * Transport-neutral Example response; adapters stream body and propagate cancellation. @since 2.0
 */
public final class ExampleResponse {
    public final int status;
    public final Map<String, String> headers;
    public final Flux<byte[]> body;

    public ExampleResponse(int status, Map<String, String> headers, Flux<byte[]> body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    public static ExampleResponse json(Object value) {
        return new ExampleResponse(
                200,
                Collections.singletonMap("Content-Type", "application/json"),
                Flux.just(Json.bytes(value)));
    }

    public static ExampleResponse error(int status, String message) {
        return new ExampleResponse(
                status,
                Collections.singletonMap("Content-Type", "application/json"),
                Flux.just(Json.bytes(Collections.singletonMap("error", message))));
    }

    public static ExampleResponse redirect(String location, String cookie) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Location", location);
        if (cookie != null) h.put("Set-Cookie", cookie);
        return new ExampleResponse(302, h, Flux.empty());
    }
}
