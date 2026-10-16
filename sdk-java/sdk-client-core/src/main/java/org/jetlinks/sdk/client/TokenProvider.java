package org.jetlinks.sdk.client;

import reactor.core.publisher.Mono;

/**
 * 绑定单一服务地址、应用、用途和 Scope 的认证来源，不应跨身份复用。
 *
 * @since 2.0
 */
public interface TokenProvider {
    /**
     * 订阅时获取 Token。返回空值表示匿名，失败中止请求；实现必须非阻塞。
     *
     * @since 2.0
     */
    Mono<String> token();

    /** 使缓存凭据失效，不重放业务请求。 */
    default void invalidate() {}

    static TokenProvider bearer(String token) {
        return () -> Mono.just(token);
    }

    static TokenProvider anonymous() {
        return Mono::empty;
    }
}
