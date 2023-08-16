package org.jetlinks.sdk.model.device.request;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.SimpleApiRequest;
import org.jetlinks.sdk.model.device.info.DeviceProduct;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 设备产品相关请求构造
 * @author wyj
 * @since 2.1
 */
public class DeviceProductRequset {
    /**
     * 新增设备产品
     * @param deviceProduct 设备产品
     * @return
     */
    public static SimpleApiRequest<DeviceProduct> addDeviceProduct(DeviceProduct deviceProduct){
        return SimpleApiRequest
                .<DeviceProduct>builder()
                .post()
                .uri("/device/product")
                .body(deviceProduct)
                .responseType(ResolvableType
                        .forClass(
                                DeviceProduct.class
                        ))
                .build();
    }

    /**
     * 根据id删除设备
     * @param productId 产品id
     * @return
     */

    public static SimpleApiRequest<DeviceProduct> deleteDeviceProduct(String productId){
        URI uri = UriComponentsBuilder
                .fromUriString("/device-product/{id}")
                .build(productId);
        return SimpleApiRequest
                .<DeviceProduct>builder()
                .method(HttpMethod.DELETE)
                .uri(uri.toString())
                .responseType(ResolvableType
                        .forClass(
                                DeviceProduct.class
                        ))
                .build();
    }

    /**
     *
     * @param productId 产品id
     * @param deviceProduct 修改后的设备产品
     * @return
     */

    public static SimpleApiRequest<DeviceProduct> updateDeviceProduct(String productId, DeviceProduct deviceProduct){
        URI uri = UriComponentsBuilder
                .fromUriString("/device-product/{id}")
                .build(productId);

        return SimpleApiRequest
                .<DeviceProduct>builder()
                .method(HttpMethod.PUT)
                .uri(uri.toString())
                .body(deviceProduct)
                .responseType(ResolvableType
                        .forClass(
                                Boolean.class
                        ))
                .build();
    }

    /**
     *
     * @param query 查询条件
     * @return
     */
    public static SimpleApiRequest<DeviceProduct> queryDeviceProduct(QueryParamEntity query){
        return SimpleApiRequest
                .<DeviceProduct>builder()
                .post()
                .uri("/device-product/_query")
                .body(query)
                .responseType(ResolvableType
                        .forClassWithGenerics(
                                PagerResult.class,
                                DeviceProduct.class
                        ))
                .build();
    }

}
