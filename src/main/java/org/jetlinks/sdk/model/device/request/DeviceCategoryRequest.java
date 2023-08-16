package org.jetlinks.sdk.model.device.request;


import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.SimpleApiRequest;
import org.jetlinks.sdk.model.device.info.DeviceCategory;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpMethod;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * 设备产品分类相关请求构造
 * @author wyj
 * @since 2.1
 */

public class DeviceCategoryRequest {
    /**
     * 新增产品分类
     * @param deviceCategory 产品分类
     * @return
     */
    public static SimpleApiRequest<DeviceCategory> addDeviceCategory(DeviceCategory deviceCategory){
        return SimpleApiRequest
                .<DeviceCategory>builder()
                .post()
                .uri("/device/category")
                .body(deviceCategory)
                .responseType(ResolvableType
                        .forClass(
                                DeviceCategory.class
                        ))
                .build();
    }
    /**
     * 根据id删除产品分类
     * @param categoryId 产品分类id
     * @return
     */

    public static SimpleApiRequest<DeviceCategory> deleteDeviceCategory(String categoryId){
        URI uri = UriComponentsBuilder
                .fromUriString("/device/category/{id}")
                .build(categoryId);
        return SimpleApiRequest
                .<DeviceCategory>builder()
                .method(HttpMethod.DELETE)
                .uri(uri.toString())
                .responseType(ResolvableType
                        .forClass(
                                DeviceCategory.class
                        ))
                .build();
    }

    /**
     *
     * @param categoryId 产品分类id
     * @param deviceCategory 修改后的产品分类
     * @return
     */

    public static SimpleApiRequest<DeviceCategory> updateDeviceCategory(String categoryId, DeviceCategory deviceCategory){
        URI uri = UriComponentsBuilder
                .fromUriString("/device/category/{id}")
                .build(categoryId);

        return SimpleApiRequest
                .<DeviceCategory>builder()
                .method(HttpMethod.PUT)
                .uri(uri.toString())
                .body(deviceCategory)
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
    public static SimpleApiRequest<DeviceCategory> queryDeviceCategory(QueryParamEntity query){
        return SimpleApiRequest
                .<DeviceCategory>builder()
                .post()
                .uri("/device/category/_query")
                .body(query)
                .responseType(ResolvableType
                        .forClassWithGenerics(
                                PagerResult.class,
                                DeviceCategory.class
                        ))
                .build();
    }

    /**
     * 获取全部分类
     * @return
     */

    public static SimpleApiRequest<DeviceCategory> getAllCategory(){
        return SimpleApiRequest
                .<DeviceCategory>builder()
                .get()
                .uri("/device/category")
                .responseType(ResolvableType
                        .forClass(
                                List.class
                        ))
                .build();
    }

    /**
     * 获取全部分类树结构
     * @return
     */

    public static SimpleApiRequest<DeviceCategory> getAllCategoryOfTree(QueryParamEntity queryParamEntity){
        return SimpleApiRequest
                .<DeviceCategory>builder()
                .post()
                .uri("/device/category/_tree")
                .body(queryParamEntity)
                .responseType(ResolvableType
                        .forClass(
                                List.class
                        ))
                .build();
    }


}
