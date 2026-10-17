package org.jetlinks.sdk.client;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * SDK 统一异常。通过 {@link Code} 判断失败类别，原始原因通过 {@link #getCause()} 获取。 远端响应可能含敏感数据，仅在 {@link #response}
 * 中显式提供，不拼入默认消息。 调用方的处理器、存储或认证扩展抛出的异常保留原样。
 *
 * @since 2.0
 */
public final class SdkException extends RuntimeException {
    /** 稳定的错误类别，避免调用方依赖异常消息判断分支。 */
    public enum Code {
        /** 请求参数或本地配置无效。 */
        INVALID_ARGUMENT,
        /** 客户端或应用当前状态不允许此操作。 */
        INVALID_STATE,
        /** 身份、签名或凭据归属校验失败。 */
        AUTHENTICATION,
        /** JSON 编解码或响应结构不符合约定。 */
        SERIALIZATION,
        /** 签名计算、密钥解析或凭据解密失败。 */
        CRYPTOGRAPHY,
        /** 请求、结果或快照超过容量限制。 */
        LIMIT_EXCEEDED,
        /** 平台业务错误或 HTTP 错误响应。 */
        REMOTE_ERROR,
        /** HTTP 连接、传输或超时失败。 */
        TRANSPORT
    }

    public final Code code;

    /** 远端 HTTP 或平台状态码；本地失败为 0。 */
    public final int status;

    /** 远端错误正文；本地失败为空。 */
    public final JsonNode response;

    public SdkException(Code code, String message) {
        this(code, message, null);
    }

    public SdkException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = 0;
        this.response = null;
    }

    public SdkException(int status, JsonNode response) {
        super("JetLinks request failed (status " + status + ")");
        this.code = Code.REMOTE_ERROR;
        this.status = status;
        this.response = response;
    }
}
