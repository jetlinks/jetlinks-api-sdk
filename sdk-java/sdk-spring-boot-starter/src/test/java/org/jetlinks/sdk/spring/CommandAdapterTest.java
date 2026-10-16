package org.jetlinks.sdk.spring;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.jetlinks.sdk.command.CommandService;
import org.jetlinks.sdk.crypto.Signatures;
import org.jetlinks.sdk.notification.ApplicationState;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.*;
import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.reactive.function.server.RouterFunction;

import reactor.core.publisher.Mono;

import java.util.Arrays;

class CommandAdapterTest {
    private static final String TARGET = "/api/commands/1/Echo",
            ID = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Configuration(proxyBeanMethods = false)
    static class Commands {
        @Bean
        CommandService commands() {
            return new CommandService(
                            (platform, application) -> {
                                ApplicationState state = new ApplicationState();
                                state.platformId = "east";
                                state.applicationId = "app";
                                state.templateId = "template";
                                state.apiBaseUrl = "https://platform.example";
                                state.accessKey = "key";
                                state.accessSecret = "secret";
                                return Mono.just(state);
                            },
                            2)
                    .register("Echo", false, (state, input) -> Mono.just(input));
        }
    }

    private HttpHeaders headers() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Arrays.asList(MediaType.APPLICATION_JSON));
        headers.set("X-Signature-Version", "1");
        headers.set("X-Platform-Id", "east");
        headers.set("X-Application-Id", "app");
        headers.set("X-Key-Id", "key");
        String time = Long.toString(System.currentTimeMillis());
        headers.set("X-Timestamp", time);
        headers.set("X-Request-Id", ID);
        headers.set(
                "X-Signature",
                Signatures.sign(
                        "secret",
                        "http-command",
                        "1",
                        "request",
                        "app",
                        "east",
                        "key",
                        time,
                        ID,
                        "POST",
                        TARGET,
                        "application/json",
                        "application/json"));
        return headers;
    }

    @Test
    void mvcAndWebfluxExposeSignedCommandResponses() {
        new ReactiveWebApplicationContextRunner()
                .withUserConfiguration(Commands.class, JetLinksAutoConfiguration.class)
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            WebTestClient client =
                                    WebTestClient.bindToRouterFunction(
                                                    context.getBean(
                                                            "jetLinksCommands",
                                                            RouterFunction.class))
                                            .build();
                            client.post()
                                    .uri(TARGET)
                                    .headers(h -> h.addAll(headers()))
                                    .bodyValue("{\"message\":\"hello\"}")
                                    .exchange()
                                    .expectStatus()
                                    .isOk()
                                    .expectHeader()
                                    .exists("X-Signature")
                                    .expectHeader()
                                    .valueEquals("X-Platform-Id", "east")
                                    .expectBody()
                                    .jsonPath("$.message")
                                    .isEqualTo("hello")
                                    .jsonPath("$.applicationId")
                                    .isEqualTo("app");
                            assertEquals(0, context.getBean(CommandService.class).inFlight());
                            client.post()
                                    .uri(TARGET)
                                    .bodyValue("{}")
                                    .exchange()
                                    .expectStatus()
                                    .isUnauthorized();
                            client.post()
                                    .uri(TARGET)
                                    .bodyValue(new byte[1024 * 1024 + 1])
                                    .exchange()
                                    .expectStatus()
                                    .isEqualTo(413);
                        });
        new WebApplicationContextRunner()
                .withUserConfiguration(Commands.class, JetLinksAutoConfiguration.class)
                .run(
                        context -> {
                            assertNull(context.getStartupFailure());
                            MockMvc client =
                                    MockMvcBuilders.standaloneSetup(
                                                    context.getBean(CommandMvcEndpoint.class))
                                            .build();
                            MvcResult pending =
                                    client.perform(
                                                    post(TARGET)
                                                            .headers(headers())
                                                            .content("{\"message\":\"hello\"}"))
                                            .andExpect(request().asyncStarted())
                                            .andReturn();
                            client.perform(asyncDispatch(pending))
                                    .andExpect(status().isOk())
                                    .andExpect(header().exists("X-Signature"))
                                    .andExpect(jsonPath("$.message").value("hello"))
                                    .andExpect(jsonPath("$.applicationId").value("app"));
                            client.perform(post(TARGET).content(new byte[1024 * 1024 + 1]))
                                    .andExpect(status().isPayloadTooLarge());
                            client.perform(post(TARGET).content("{}"))
                                    .andExpect(status().isUnauthorized());
                            assertEquals(0, context.getBean(CommandService.class).inFlight());
                        });
    }
}
