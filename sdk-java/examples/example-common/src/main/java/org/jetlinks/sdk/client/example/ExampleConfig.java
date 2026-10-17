package org.jetlinks.sdk.client.example;

import com.fasterxml.jackson.databind.*;

import org.jetlinks.sdk.client.*;
import org.jetlinks.sdk.client.SdkException.Code;
import org.jetlinks.sdk.client.notification.ApplicationState;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * 示例专用配置；凭据只从本地文件加载，不传给浏览器或打包进前端。
 *
 * @since 2.0
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
                    throw new SdkException(
                            Code.INVALID_STATE, "Cannot read receiver private key", e);
                }
            }
        throw new SdkException(Code.INVALID_ARGUMENT, "Unknown receiver key");
    }

    /**
     * 固定 API 应用通道；可额外配置演示所需的用户或管理 Token。
     *
     * @since 2.0
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
     * 模版信任配置；platformId 与平台模版保持一致，无需额外的区域发现配置。
     *
     * @since 2.0
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
