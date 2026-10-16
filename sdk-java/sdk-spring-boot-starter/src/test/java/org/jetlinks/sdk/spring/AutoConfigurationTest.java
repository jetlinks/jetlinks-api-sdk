package org.jetlinks.sdk.spring;

import static org.junit.jupiter.api.Assertions.*;

import org.jetlinks.sdk.*;
import org.jetlinks.sdk.crypto.Signatures;
import org.jetlinks.sdk.notification.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.*;
import org.springframework.context.annotation.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.server.RouterFunction;

import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.*;

class AutoConfigurationTest {
    @Configuration(proxyBeanMethods = false)
    static class ReceiverBeans {
        @Bean
        NotificationVerifier verifier() {
            return new NotificationVerifier(
                    (p, a, k) -> Mono.just(TrustedKey.api("app", "secret")));
        }

        @Bean
        NotificationStore store() {
            return n -> Mono.just(true);
        }
    }

    @Test
    void noEndpointsOrClientsWithoutConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(JetLinksAutoConfiguration.class)
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            assertEquals(
                                    0, ctx.getBeansOfType(JetLinksReactiveClient.class).size());
                            assertEquals(0, ctx.getBeansOfType(NotificationReceiver.class).size());
                        });
    }

    @Test
    void dualClientsShareConfiguredProvider() {
        new ApplicationContextRunner()
                .withUserConfiguration(JetLinksAutoConfiguration.class)
                .withPropertyValues(
                        "jetlinks.sdk.base-url=http://127.0.0.1:1",
                        "jetlinks.sdk.key=key",
                        "jetlinks.sdk.secret=secret",
                        "jetlinks.sdk.auth-mode=local")
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            assertNotNull(ctx.getBean(JetLinksClient.class));
                            assertNotNull(ctx.getBean(JetLinksReactiveClient.class));
                        });
    }

    @Test
    void servletReceiverUsesSameVerifiedStore() {
        new WebApplicationContextRunner()
                .withUserConfiguration(ReceiverBeans.class, JetLinksAutoConfiguration.class)
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            NotificationMvcEndpoint endpoint =
                                    ctx.getBean(NotificationMvcEndpoint.class);
                            org.springframework.test.web.servlet.MockMvc client =
                                    MockMvcBuilders.standaloneSetup(endpoint).build();
                            byte[] body = "{\"id\":\"event\"}".getBytes(StandardCharsets.UTF_8);
                            String time = Long.toString(System.currentTimeMillis());
                            client.perform(
                                            MockMvcRequestBuilders.post("/jetlinks/events")
                                                    .contentType("application/json")
                                                    .content(body)
                                                    .header("X-Signature-Version", "1")
                                                    .header("X-Application-Id", "app")
                                                    .header("X-Key-Id", "key")
                                                    .header("X-Timestamp", time)
                                                    .header("X-Event-Id", "event")
                                                    .header(
                                                            "X-Signature",
                                                            Signatures.sign(
                                                                    "secret",
                                                                    "1",
                                                                    "app",
                                                                    "",
                                                                    "key",
                                                                    time,
                                                                    "event",
                                                                    Signatures.sha256(body))))
                                    .andExpect(MockMvcResultMatchers.status().isNoContent());
                            client.perform(
                                            MockMvcRequestBuilders.post("/jetlinks/events")
                                                    .contentType("application/json")
                                                    .content(body))
                                    .andExpect(MockMvcResultMatchers.status().isUnauthorized());
                            client.perform(
                                            MockMvcRequestBuilders.post("/jetlinks/events")
                                                    .contentType("application/json")
                                                    .content(new byte[1024 * 1024 + 1]))
                                    .andExpect(MockMvcResultMatchers.status().isPayloadTooLarge());
                        });
    }

    @Test
    void reactiveReceiverUsesVerifiedStore() {
        new ReactiveWebApplicationContextRunner()
                .withUserConfiguration(ReceiverBeans.class, JetLinksAutoConfiguration.class)
                .run(
                        ctx -> {
                            assertNull(ctx.getStartupFailure());
                            RouterFunction<?> routes =
                                    ctx.getBean("jetLinksNotifications", RouterFunction.class);
                            WebTestClient client =
                                    WebTestClient.bindToRouterFunction(routes).build();
                            byte[] body = "{\"id\":\"event\"}".getBytes(StandardCharsets.UTF_8);
                            String time = Long.toString(System.currentTimeMillis());
                            client.post()
                                    .uri("/jetlinks/events")
                                    .header("X-Signature-Version", "1")
                                    .header("X-Application-Id", "app")
                                    .header("X-Key-Id", "key")
                                    .header("X-Timestamp", time)
                                    .header("X-Event-Id", "event")
                                    .header(
                                            "X-Signature",
                                            Signatures.sign(
                                                    "secret",
                                                    "1",
                                                    "app",
                                                    "",
                                                    "key",
                                                    time,
                                                    "event",
                                                    Signatures.sha256(body)))
                                    .bodyValue(body)
                                    .exchange()
                                    .expectStatus()
                                    .isNoContent();
                            client.post()
                                    .uri("/jetlinks/events")
                                    .bodyValue(body)
                                    .exchange()
                                    .expectStatus()
                                    .isUnauthorized();
                        });
    }
}
