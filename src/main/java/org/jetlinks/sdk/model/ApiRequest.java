package org.jetlinks.sdk.model;

import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.util.MultiValueMap;

import javax.annotation.Nonnull;

public interface ApiRequest<T> {

    @Nonnull
    String uri();

    @Nonnull
    HttpMethod method();

    MultiValueMap<String, String> parameters();

    Object body();

    @SuppressWarnings("all")
    default ResolvableType responseType() {
        return ResolvableType
                .forClass(ApiRequest.class,this.getClass())
                .getGeneric(0);
    }
}
