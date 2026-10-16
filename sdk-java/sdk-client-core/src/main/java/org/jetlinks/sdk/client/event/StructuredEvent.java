package org.jetlinks.sdk.client.event;

import org.jetlinks.sdk.client.model.Extensible;

import java.util.Map;

/**
 * 通知公共信封。subject 描述业务事实，接收应用身份来自验签后的 Header。
 *
 * @since 2.0
 */
public class StructuredEvent<T> extends Extensible {
    public String id, type, version, subject;
    public Long occurredAt, processedAt;
    public T data;
    /** 随事件一起验签的公开上下文，例如 tenantId；与业务 data 独立。 */
    public Map<String, Object> extensions;
}
