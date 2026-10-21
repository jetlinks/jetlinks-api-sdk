package org.jetlinks.sdk.client.event;

/**
 * 第三方应用通知的数据包装；payload 保留原始业务事实。
 *
 * @since 2.0
 */
public class BusinessApplicationEventData<T> {
    public ApplicationInfo application;
    public String sourceEventId;
    public T payload;
}
