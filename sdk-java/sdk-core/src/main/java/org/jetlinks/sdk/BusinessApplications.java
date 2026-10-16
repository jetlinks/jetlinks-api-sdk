package org.jetlinks.sdk;

import org.jetlinks.sdk.model.*;
import java.util.List;

import reactor.core.publisher.*;

/**
 * Explicit business application queries. Permissions remain enforced by the platform; no local
 * permission engine. @since 2.0
 */
public final class BusinessApplications {
    private final JetLinksReactiveClient client;
    private final String id;

    public BusinessApplications(JetLinksReactiveClient client, String applicationId) {
        this.client = client;
        id = ApiRequest.segment(applicationId);
    }

    public Flux<Member> members() {
        return client.pages("/user/detail/business_application/" + id + "/_query", new Query(), Member.class);
    }

    public Flux<Role> roles() {
        return client.pages("/role/_query", new Query().eq("applicationId", decode(id)), Role.class);
    }

    private String decode(String v) {
        try {
            return java.net.URLDecoder.decode(v, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Flux<UserRole> userRoles() {
        return relation("user-roles", UserRole.class);
    }

    public Flux<ApiGrant> grants() {
        return relation("api-grants", ApiGrant.class);
    }

    private <T> Flux<T> relation(String name, Class<T> type) {
        return client.pages("/business-application/" + id + "/" + name + "/_query", new Query(), type);
    }

    public Flux<RoleAuthorization> roleAuthorization(String role) {
        return client.pages(
                "/business-application/"
                        + id
                        + "/roles/"
                        + ApiRequest.segment(role)
                        + "/authorization/_query",
                new Query(), RoleAuthorization.class);
    }

    public Flux<RoleMenu> roleMenus(String role) {
        return client.pages(
                "/business-application/"
                        + id
                        + "/roles/"
                        + ApiRequest.segment(role)
                        + "/menus/_query",
                new Query(), RoleMenu.class);
    }

    public Mono<List<AssetAccesses>> assets(String role) {
        return client.request(
                ApiRequest.get("/role/" + ApiRequest.segment(role) + "/asset-accesses"),
                new TypeRef<List<AssetAccesses>>() {});
    }

    public Mono<Void> syncCredential() {
        return client.request(
                ApiRequest.post("/business-application/" + id + "/credential/_sync", null)).then();
    }
}
