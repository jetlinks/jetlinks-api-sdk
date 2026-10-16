package org.jetlinks.sdk;

import org.jetlinks.sdk.model.Page;
import org.jetlinks.sdk.subscription.*;
import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Subscription administration with the caller's explicit management identity. No implicit
 * enable/retry. @since 2.0
 */
public final class Subscriptions {
    private final JetLinksReactiveClient client;
    private final String base;

    public Subscriptions(JetLinksReactiveClient client, String applicationId) {
        this.client = client;
        base = "/application/" + ApiRequest.segment(applicationId) + "/subscriptions";
    }

    public Mono<Page<Subscription>> query(Query query) {
        return client.request(ApiRequest.post(base + "/_query", query),
                new TypeRef<Page<Subscription>>() {});
    }

    public Mono<Subscription> get(String id) {
        return client.request(ApiRequest.get(base + "/" + ApiRequest.segment(id)), Subscription.class);
    }

    public Mono<String> create(SubscriptionConfig body) {
        return client.request(ApiRequest.post(base, body), String.class);
    }

    public Mono<Integer> update(String id, SubscriptionConfig body) {
        return client.request(ApiRequest.of("PUT", base + "/" + ApiRequest.segment(id)).body(body), Integer.class);
    }

    public Mono<Integer> enable(String id, boolean enable) {
        return client.request(
                ApiRequest.post(
                        base + "/" + ApiRequest.segment(id) + (enable ? "/_enable" : "/_disable"),
                        null), Integer.class);
    }

    public Mono<Integer> delete(String id) {
        return client.request(ApiRequest.of("DELETE", base + "/" + ApiRequest.segment(id)), Integer.class);
    }

    public Mono<SubscriptionValidation> validate(SubscriptionConfig body) {
        return client.request(ApiRequest.post(base + "/_validate", body), SubscriptionValidation.class);
    }

    public Mono<List<EventProvider>> events() {
        return client.request(ApiRequest.get(base + "/events"), new TypeRef<List<EventProvider>>() {});
    }

    public Mono<List<DeliveryChannel>> channels() {
        return client.request(ApiRequest.get(base + "/channels"), new TypeRef<List<DeliveryChannel>>() {});
    }

    public Mono<Page<DeliveryTask>> tasks(String id, Query query) {
        return client.request(
                ApiRequest.post(base + "/" + ApiRequest.segment(id) + "/tasks/_query", query),
                new TypeRef<Page<DeliveryTask>>() {});
    }

    public Mono<Page<DeliveryRecord>> records(String id, Query query) {
        return client.request(
                ApiRequest.post(base + "/" + ApiRequest.segment(id) + "/records/_query", query),
                new TypeRef<Page<DeliveryRecord>>() {});
    }

    public Mono<DeliveryPayload> payload(String id, String task, int attempt) {
        return client.request(
                ApiRequest.get(
                        base
                                + "/"
                                + ApiRequest.segment(id)
                                + "/tasks/"
                                + ApiRequest.segment(task)
                                + "/attempts/"
                                + attempt
                                + "/payload"), DeliveryPayload.class);
    }
}
