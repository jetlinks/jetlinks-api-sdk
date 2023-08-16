package org.jetlinks.sdk.model.device.request;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.model.EnumInfo;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.SimpleApiRequest;
import org.jetlinks.sdk.model.device.info.*;
;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * 设备实例相关请求构造
 * @author gyl,wyj
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



    /**
     * 查询设备指定属性列表
     * @param property 设备属性
     * @return
     */
    public static SimpleApiRequest<PagerResult<DeviceProperty>> queryDeviceProperty(String deviceId, String property, QueryParamEntity queryParam) {
        URI uri = UriComponentsBuilder
                .fromUriString("/device/instance/{deviceId}/property/{property}/_query")
                .build(deviceId,property);
        return SimpleApiRequest
                .<PagerResult<DeviceProperty>>builder()
                .post()
                .uri(uri.toString())
                .body(queryParam)
                .responseType(ResolvableType
                        .forClassWithGenerics(
                                PagerResult.class,
                                DeviceProperty.class
                        ))
                .build();
    }

    /**
     * 新增设备
     * @param deviceInstance 设备实例
     * @return
     */
    public static SimpleApiRequest<DeviceInstance> addDeviceInstance(DeviceInstance deviceInstance){
        return SimpleApiRequest
                .<DeviceInstance>builder()
                .post()
                .uri("/device-instance")
                .body(deviceInstance)
                .responseType(ResolvableType
                        .forClass(
                                DeviceInstance.class
                        ))
                .build();
    }

    /**
     * 根据id删除设备
     * @param deviceId 设备id
     * @return
     */

    public static SimpleApiRequest<DeviceInstance> deleteDeviceInstance(String deviceId){
        URI uri = UriComponentsBuilder
                .fromUriString("/device-instance/{id}")
                .build(deviceId);
        return SimpleApiRequest
                .<DeviceInstance>builder()
                .method(HttpMethod.DELETE)
                .uri(uri.toString())
                .responseType(ResolvableType
                        .forClass(
                                DeviceInstance.class
                        ))
                .build();
    }

    /**
     * 根据查询体查询设备
     * @param query 查询参数
     * @return
     */
    public static SimpleApiRequest<DeviceInstance> queryDeviceInstance(QueryParamEntity query){
        return SimpleApiRequest
                .<DeviceInstance>builder()
                .post()
                .uri("/device-instance/_query")
                .body(query)
                .responseType(ResolvableType
                        .forClassWithGenerics(
                                PagerResult.class,
                                DeviceInstance.class
                        ))
                .build();
    }

    /**
     * 根据id修改
     * @param deviceId 设备id
     * @return
     */
    public static SimpleApiRequest<DeviceInstance> updateDeviceInstance(String deviceId, DeviceInstance deviceInstance){
        URI uri = UriComponentsBuilder
                .fromUriString("/device/instance/{id}")
                .build(deviceId);

        return SimpleApiRequest
                .<DeviceInstance>builder()
                .method(HttpMethod.PUT)
                .uri(uri.toString())
                .body(deviceInstance)
                .responseType(ResolvableType
                        .forClass(
                                Boolean.class
                        ))
                .build();
    }

    /**
     * 获取指定id设备的在线状态
     * @param deviceId 设备id
     * @return
     */
    public static SimpleApiRequest<DeviceInstance> queryDeviceState(String deviceId){
        URI uri = UriComponentsBuilder
                .fromUriString("/device/instance/{id}/state")
                .build(deviceId);

        return SimpleApiRequest
                .<DeviceInstance>builder()
                .get()
                .uri(uri.toString())
                .responseType(ResolvableType
                        .forClass(
                                EnumInfo.class
                        ))
                .build();

    }

    /**
     * 查询设备日志数据
     * @param deviceId 设备id
     * @param queryParamEntity 查询条件
     * @return
     */

    public static SimpleApiRequest<DeviceInstance> queryDeviceOperationLog(String deviceId, QueryParamEntity queryParamEntity){
        URI uri = UriComponentsBuilder
                .fromUriString("/device/instance/{deviceId}/logs")
                .build(deviceId);

        return SimpleApiRequest
                .<DeviceInstance>builder()
                .post()
                .uri(uri.toString())
                .body(queryParamEntity)
                .responseType(ResolvableType
                        .forClassWithGenerics(
                                PagerResult.class,
                                DeviceOperationLog.class
                        ))
                .build();
    }

    /**
     * 更新
     * @param deviceId
     * @param deviceMetadata
     * @return
     */

    public static SimpleApiRequest<DeviceInstance> updateDeviceMetadata(String deviceId, String deviceMetadata){
        URI uri = UriComponentsBuilder
                .fromUriString("/device/instance/{deviceId}/metadata")
                .build(deviceId);

        return SimpleApiRequest
                .<DeviceInstance>builder()
                .method(HttpMethod.PUT)
                .uri(uri.toString())
                .body(deviceMetadata)
                .responseType(ResolvableType
                        .forClass(
                                Object.class
                        ))
                .build();
    }

    /**
     * 重置物模型
     * @param deviceId 设备id
     * @return
     */
    public static SimpleApiRequest<DeviceInstance> deleteMetadata(String deviceId){
        URI uri = UriComponentsBuilder
                .fromUriString("/device/instance/{deviceId}/metadata")
                .build(deviceId);
        return SimpleApiRequest
                .<DeviceInstance>builder()
                .method(HttpMethod.DELETE)
                .uri(uri.toString())
                .responseType(ResolvableType
                        .forClass(
                                Object.class
                        ))
                .build();
    }

    /**
     * 获取支持的物模型格式
     * @return
     */

    public static SimpleApiRequest<DeviceInstance> getDeviceMetadataCodecs(){
        return SimpleApiRequest
                .<DeviceInstance>builder()
                .get()
                .uri("/device/product/metadata/codecs")
                .responseType(ResolvableType
                        .forClass(
                                Object.class
                        ))
                .build();
    }

    /**
     * 获取设备的影子
     * @param deviceId 设备id
     * @return
     */

    public static SimpleApiRequest<DeviceInstance> getDeviceShadow(String deviceId){
        URI uri = UriComponentsBuilder
                .fromUriString("/device/instance/{deviceId}/shadow")
                .build(deviceId);
        return SimpleApiRequest
                .<DeviceInstance>builder()
                .get()
                .uri(uri.toString())
                .responseType(ResolvableType
                        .forClass(
                                String.class
                        ))
                .build();
    }



}
