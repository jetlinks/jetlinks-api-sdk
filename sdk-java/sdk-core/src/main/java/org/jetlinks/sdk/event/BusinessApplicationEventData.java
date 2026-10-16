package org.jetlinks.sdk.event;

/** Third-party delivery data; payload retains the original business fact. @since 2.0 */
public class BusinessApplicationEventData<T> {
    public ApplicationInfo application;
    public String sourceEventId;
    public T payload;
}
