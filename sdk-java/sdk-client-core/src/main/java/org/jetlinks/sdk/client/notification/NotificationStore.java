package org.jetlinks.sdk.client.notification;

import reactor.core.publisher.Mono;

/**
 * 可靠受理的原子边界；实现应在同一持久化事务中更新 inbox、归属和应用状态。
 *
 * @since 2.0
 */
public interface NotificationStore {
    /**
     * 验签后原子保存事件及绑定/状态变更。true 表示新受理，false 表示持久化记录已存在。 实现不能阻塞事件循环；失败必须传播并返回非 2xx，供平台重试。
     * 后续业务处理可异步执行，但重试不得重复产生副作用。
     *
     * @since 2.0
     * @see NotificationReceiver
     */
    Mono<Boolean> accept(VerifiedNotification notification);
}
