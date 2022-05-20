package org.jetlinks.sdk.model.device;

import org.hswebframework.ezorm.core.dsl.Query;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.QueryPageRequest;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;

import javax.annotation.Nonnull;
import java.util.function.Consumer;

/**
 * 查询设备列表
 *
 * <pre>{@code
 * ApiResponse<PagerResult<DeviceInfo>> response = client
 *                 .request(QueryDeviceRequest
 *                                  .of(query -> query
 *                                          .where("productId", "demo-device")
 *                                          .doPaging(0, 10)));
 *
 * }</pre>
 *
 * @since 1.0
 */
public class QueryDeviceRequest extends QueryPageRequest<DeviceInfo, QueryDeviceRequest> {

    private final static ResolvableType RESPONSE_TYPE = ResolvableType
            .forClassWithGenerics(
                    PagerResult.class,
                    DeviceInfo.class
            );

    public QueryDeviceRequest() {
    }

    public static QueryDeviceRequest of(Consumer<Query<?, QueryParamEntity>> query) {
        return new QueryDeviceRequest().dsl(query);
    }

    @Nonnull
    @Override
    public String uri() {
        return "/device/instance/_query";
    }

    @Nonnull
    @Override
    public HttpMethod method() {
        return HttpMethod.POST;
    }

    @Override
    public ResolvableType responseType() {
        return RESPONSE_TYPE;
    }
}
