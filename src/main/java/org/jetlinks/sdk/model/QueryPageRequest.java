package org.jetlinks.sdk.model;

import org.hswebframework.ezorm.core.dsl.Query;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.hswebframework.web.utils.HttpParameterConverter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.function.Consumer;

public abstract class QueryPageRequest<T, Self extends QueryPageRequest<T, Self>> implements ApiRequest<PagerResult<T>> {

    private QueryParamEntity query = new QueryParamEntity();

    public Self expr(String condition) {
        query = new QueryParamEntity();
        query.setWhere(condition);
        return castSelf();
    }

    public Self dsl(Consumer<Query<?, QueryParamEntity>> dslHandler) {
        Query<?, QueryParamEntity> dsl = query.toQuery();
        dslHandler.accept(dsl);
        return castSelf();
    }

    @Override
    public MultiValueMap<String, String> parameters() {
        if (method() == HttpMethod.GET) {
            MultiValueMap<String, String> parameters = new LinkedMultiValueMap<>();
            new HttpParameterConverter(query)
                    .convert()
                    .forEach(parameters::add);
            return parameters;
        }
        return null;
    }

    @Override
    public Object body() {
        return query;
    }

    @SuppressWarnings("all")
    protected Self castSelf() {
        return (Self) this;
    }

    @Override
    public ResolvableType responseType() {
        return ResolvableType.forClassWithGenerics(
                PagerResult.class,
                ResolvableType
                        .forClass(QueryPageRequest.class,this.getClass())
                        .getGeneric(0)
        );
    }
}
