package org.jetlinks.sdk.example;

import com.fasterxml.jackson.databind.*;

import org.jetlinks.sdk.*;
import org.jetlinks.sdk.notification.ApplicationState;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * Example-only configuration. Credentials stay in a local file, not the browser or shared UI
 * bundle. @since 2.0
 */
public final class ExampleConfig {
    public String host = "127.0.0.1",
            publicBaseUrl = "http://127.0.0.1:8090",
            uiDir = "examples/shared-ui/dist",
            database = "data/example";
    public int port = 8090;
    public List<ApiApplication> apiApplications = new ArrayList<>();
    public List<Template> templates = new ArrayList<>();

    public static ExampleConfig load(String file) throws java.io.IOException {
        return Json.MAPPER.readValue(Files.readAllBytes(Paths.get(file)), ExampleConfig.class);
    }

    public String privateKey(String keyId) {
        for (Template t : templates)
            if (keyId.equals(t.receiverKeyId)) {
                try {
                    return new String(
                            Files.readAllBytes(Paths.get(t.privateKeyFile)),
                            StandardCharsets.US_ASCII);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Cannot read receiver private key", e);
                }
            }
        throw new IllegalArgumentException("Unknown receiver key");
    }

    /**
     * Fixed API application channel, optionally with separately authorized user/management Tokens
     * for the demonstration.
     */
    public static final class ApiApplication {
        public String contextId,
                applicationId,
                apiBaseUrl,
                accessKey,
                accessSecret,
                managementToken,
                userToken;

        public ApplicationState state() {
            ApplicationState s = new ApplicationState();
            s.applicationId = applicationId;
            s.apiBaseUrl = apiBaseUrl;
            s.accessKey = accessKey;
            s.accessSecret = accessSecret;
            s.keyRevision = 1;
            return s;
        }
    }

    /**
     * Template trust bootstrap; platformId matches the platform template, not an additional region
     * discovery setting.
     */
    public static final class Template {
        public String platformId,
                templateId,
                notificationKeyId,
                notificationSecret,
                receiverKeyId,
                privateKeyFile,
                userToken;
    }
}
