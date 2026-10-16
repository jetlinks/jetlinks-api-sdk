package org.jetlinks.sdk.event;

import org.jetlinks.sdk.model.Extensible;

/**
 * 通知公共信封。subject 描述业务事实，接收应用身份来自验签后的 Header。
 *
 * @since 2.0
 */
public class StructuredEvent<T> extends Extensible {
    public String id, type, version, subject;
    public Long occurredAt, processedAt;
    public T data;
}
