package org.jetlinks.sdk.client.spring;

import org.jetlinks.sdk.client.SdkException;

/** 同一协议的 MVC/WebFlux 端点共用错误映射；响应不暴露原始异常或密钥。 */
final class EndpointErrors {
    private EndpointErrors() {}

    static int notificationStatus(Throwable error) {
        if (error instanceof SdkException) {
            switch (((SdkException) error).code) {
                case LIMIT_EXCEEDED:
                    return 413;
                case AUTHENTICATION:
                case INVALID_ARGUMENT:
                case SERIALIZATION:
                case CRYPTOGRAPHY:
                    return 401;
                default:
                    return 503;
            }
        }
        // 扩展实现可能仍使用 JDK 异常，保持可靠受理失败可重试。
        return error instanceof SecurityException || error instanceof IllegalArgumentException
                ? 401
                : 503;
    }

    static int commandStatus(Throwable error) {
        if (error instanceof SdkException) {
            switch (((SdkException) error).code) {
                case LIMIT_EXCEEDED:
                    return 413;
                case AUTHENTICATION:
                    return 401;
                case INVALID_ARGUMENT:
                case SERIALIZATION:
                    return 400;
                default:
                    return 500;
            }
        }
        if (error instanceof SecurityException) return 401;
        return error instanceof IllegalArgumentException || error instanceof java.io.IOException
                ? 400
                : 500;
    }
}
