package org.jetlinks.sdk.event;

import org.jetlinks.sdk.model.Extensible;

/** Public notification envelope. Subject describes the fact, not the receiving application. @since 2.0 */
public class StructuredEvent<T> extends Extensible {
    public String id, type, version, subject;
    public Long occurredAt, processedAt;
    public T data;
}
