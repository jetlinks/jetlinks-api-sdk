package org.jetlinks.sdk.client.command;

/** 当前可信应用的实际服务检查结果；传输异常不能转换为 DOWN。 @since 2.0 */
public class ServiceHealth {
    public enum Status {
        UP,
        DOWN
    }

    public Status status;

    /** 可公开的状态说明，不包含内部诊断或凭据。 */
    public String message;

    public ServiceHealth() {}

    public ServiceHealth(Status status, String message) {
        this.status = status;
        this.message = message;
    }
}
