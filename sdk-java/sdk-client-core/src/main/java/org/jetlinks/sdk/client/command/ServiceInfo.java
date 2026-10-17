package org.jetlinks.sdk.client.command;

/** 服务名称、发布版本与可选描述，不表示平台应用或协议版本。 @since 2.0 */
public class ServiceInfo {
    public String name, version, description;

    public ServiceInfo() {}

    public ServiceInfo(String name, String version, String description) {
        this.name = name;
        this.version = version;
        this.description = description;
    }
}
