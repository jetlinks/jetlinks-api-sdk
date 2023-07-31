package org.jetlinks.sdk.model;

import lombok.AllArgsConstructor;
import org.hswebframework.web.utils.HttpParameterConverter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import javax.annotation.Nonnull;
import java.util.Collections;
import java.util.Map;

@AllArgsConstructor
public class SimpleApiRequest<T> implements ApiRequest<T> {

    private final String uri;
    private final HttpMethod method;
    private final Object body;
    private final MultiValueMap<String, String> parameters;
    private final ResolvableType responseType;


    public static <T> DefaultApiRequestBuilder<T> builder() {
        return new DefaultApiRequestBuilder<T>();
    }


    @Nonnull
    @Override
    public String uri() {
        return uri;
    }

    @Nonnull
    @Override
    public HttpMethod method() {
        return method;
    }

    @Override
    public MultiValueMap<String, String> parameters() {
        return parameters;
    }

    @Override
    public Object body() {
        return body;
    }

    @Override
    public ResolvableType responseType() {
        return responseType;
    }


    public static class DefaultApiRequestBuilder<T> {
        private String uri;
        private HttpMethod method;
        private Object body;
        private final MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
        private ResolvableType responseType;

        DefaultApiRequestBuilder() {
        }

        public DefaultApiRequestBuilder<T> uri(String uri) {
            this.uri = uri;
            return this;
        }

        public DefaultApiRequestBuilder<T> post() {
            return method(HttpMethod.POST);
        }

        public DefaultApiRequestBuilder<T> get() {
            return method(HttpMethod.GET);
        }

        public DefaultApiRequestBuilder<T> patch() {
            return method(HttpMethod.PATCH);
        }

        public DefaultApiRequestBuilder<T> method(HttpMethod method) {
            this.method = method;
            return this;
        }

        public DefaultApiRequestBuilder<T> body(Object body) {
            this.body = body;
            return this;
        }

        public DefaultApiRequestBuilder<T> parameters(Object obj) {
            return parameters(
                    new HttpParameterConverter(obj).convert()
            );
        }

        public DefaultApiRequestBuilder<T> parameters(Map<String, String> parameters) {
            parameters.forEach(this::parameter);
            return this;
        }

        public DefaultApiRequestBuilder<T> parameter(String name, String value) {
            this.parameters.put(name, Collections.singletonList(value));
            return this;
        }

        public DefaultApiRequestBuilder<T> parameters(MultiValueMap<String, String> parameters) {
            this.parameters.putAll(parameters);
            return this;
        }

        public DefaultApiRequestBuilder<T> responseType(Class<T> type) {
            return responseType(ResolvableType.forType(type));
        }

        public DefaultApiRequestBuilder<T> responseType(ResolvableType responseType) {
            this.responseType = responseType;
            return this;
        }

        public SimpleApiRequest<T> build() {
            return new SimpleApiRequest<T>(uri, method, body, parameters, responseType);
        }

        public String toString() {
            return "DefaultApiRequest.DefaultApiRequestBuilder(uri=" + this.uri + ", method=" + this.method + ", body=" + this.body + ", parameters=" + this.parameters + ", responseType=" + this.responseType + ")";
        }
    }
}
