package org.jetlinks.sdk.spring;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.jetlinks.sdk.SdkException;
import org.jetlinks.sdk.SdkException.Code;
import org.jetlinks.sdk.crypto.Signatures;
import org.jetlinks.sdk.notification.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.server.RouterFunction;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

class NotificationErrorsTest {
    @Configuration(proxyBeanMethods = false)
    static class Receivers {
        @Bean
        NotificationVerifier verifier() {
            return new NotificationVerifier(
                    (p, a, k) -> Mono.just(TrustedKey.api("app", "secret")));
        }

        @Bean
        NotificationStore store() {
            return n ->
                    Mono.error(() -> new SdkException(Code.INVALID_STATE, "Storage unavailable"));
        }
    }

    private static HttpHeaders signed(byte[] body) {
        HttpHeaders headers = new HttpHeaders();
        String time = Long.toString(System.currentTimeMillis());
        headers.set("Content-Type", "application/json");
        headers.set("X-Signature-Version", "1");
        headers.set("X-Application-Id", "app");
        headers.set("X-Key-Id", "key");
        headers.set("X-Timestamp", time);
        headers.set("X-Event-Id", "event");
        headers.set(
                "X-Signature",
                Signatures.sign(
                        "secret", "1", "app", "", "key", time, "event", Signatures.sha256(body)));
        return headers;
    }

    @Test
    void mvcAndWebfluxKeepRejectionsAndRetryableStorageFailuresDistinct() throws Exception {
        byte[] valid = "{\"id\":\"event\"}".getBytes(StandardCharsets.UTF_8);
        byte[] malformed = "{".getBytes(StandardCharsets.UTF_8);
        Receivers beans = new Receivers();
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new NotificationMvcEndpoint(
                                        new NotificationReceiver(beans.verifier(), beans.store())))
                        .build();
        mvc.perform(post("/jetlinks/events").headers(signed(valid)).content(valid))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/jetlinks/events").headers(signed(malformed)).content(malformed))
                .andExpect(status().isUnauthorized());

        new ReactiveWebApplicationContextRunner()
                .withUserConfiguration(Receivers.class, JetLinksAutoConfiguration.class)
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            WebTestClient webflux =
                                    WebTestClient.bindToRouterFunction(
                                                    context.getBean(
                                                            "jetLinksNotifications",
                                                            RouterFunction.class))
                                            .build();
                            webflux.post()
                                    .uri("/jetlinks/events")
                                    .headers(h -> h.addAll(signed(valid)))
                                    .bodyValue(valid)
                                    .exchange()
                                    .expectStatus()
                                    .isEqualTo(503);
                            webflux.post()
                                    .uri("/jetlinks/events")
                                    .headers(h -> h.addAll(signed(malformed)))
                                    .bodyValue(malformed)
                                    .exchange()
                                    .expectStatus()
                                    .isUnauthorized();
                            webflux.post()
                                    .uri("/jetlinks/events")
                                    .bodyValue(new byte[1024 * 1024 + 1])
                                    .exchange()
                                    .expectStatus()
                                    .isEqualTo(413);
                        });
    }
}
