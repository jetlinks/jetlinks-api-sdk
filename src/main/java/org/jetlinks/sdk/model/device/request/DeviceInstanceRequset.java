package org.jetlinks.sdk.model.device.request;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.SimpleApiRequest;
import org.jetlinks.sdk.model.device.info.DeviceDetail;
import org.springframework.core.ResolvableType;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 设备实例相关请求构造
 * @author gyl
 * @since 2.1
 */

public class DeviceInstanceRequset {

    /**
     * 查询设备详情
     * @param deviceId 设备id
     */
    public static SimpleApiRequest<DeviceDetail> getDeviceDetailInfo(String deviceId) {
        URI uri = UriComponentsBuilder
                .fromUriString("/device-instance/{id}/detail")
                .build(deviceId);
        return SimpleApiRequest
                .<DeviceDetail>builder()
                .get()
                .uri(uri.toString())
                .responseType(ResolvableType.forClass(DeviceDetail.class))
                .build();
    }

    /**
     * 分页查询设备详情
     * @param query 通用查询参数
     */
    public static SimpleApiRequest<PagerResult<DeviceDetail>> queryDeviceDetail(QueryParamEntity query) {
        return SimpleApiRequest
                .<PagerResult<DeviceDetail>>builder()
                .post()
                .uri("/device-instance/detail/_query")
                .body(query)
                .responseType(ResolvableType
                                      .forClassWithGenerics(
                                              PagerResult.class,
                                              DeviceDetail.class
                                      ))
                .build();
    }



}
