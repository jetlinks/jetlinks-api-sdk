package org.jetlinks.sdk.model.device.request;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.client.ApiClient;
import org.jetlinks.sdk.client.ClientConfig;
import org.jetlinks.sdk.client.WebApiClient;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.device.info.DeviceCategory;
import org.junit.jupiter.api.Test;

public class DeviceCategoryRequestTest {
    ClientConfig clientConfig = new ClientConfig(
            "http://192.168.33.46:8844",
            "w23y8dADCwPkRGnB",
            "dH7eNKTNmca33YstE88HdYRz8x7RZiFt"
    );
    ApiClient client = new WebApiClient(clientConfig);

    @Test
    public void addDeviceCategoryTest(){
        DeviceCategory deviceCategory = new DeviceCategory();
        deviceCategory.setId("demo_id");
        deviceCategory.setName("demo_name");
        deviceCategory.setKey("demo_key");
        deviceCategory.setSortIndex(1L);
        ApiResponse<DeviceCategory> response = client
                .request(DeviceCategoryRequest.addDeviceCategory(deviceCategory));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void deleteDeviceInstanceTest(){
        String categoryId = "demo_id";
        ApiResponse<DeviceCategory> request = client.
                request(DeviceCategoryRequest.deleteDeviceCategory(categoryId));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }


    @Test
    public void updateDeviceCategoryTest(){
        DeviceCategory deviceCategory = new DeviceCategory();
        String categoryId = "demo_id";
        deviceCategory.setSortIndex(2L);
        deviceCategory.setName("demo_name");
        deviceCategory.setKey("demo_key");

        ApiResponse<DeviceCategory> request = client
                .request(DeviceCategoryRequest.updateDeviceCategory(categoryId, deviceCategory));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }

    @Test
    public void queryDeviceCategoryTest(){
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .where("sortIndex", "1")
                .doPaging(0,100);
        ApiResponse<DeviceCategory> request = client
                .request(DeviceCategoryRequest.queryDeviceCategory(queryParamEntity));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }

    @Test
    public void getAllCategoryTest(){
        ApiResponse<DeviceCategory> request = client
                .request(DeviceCategoryRequest.getAllCategory());
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }

    @Test
    public void getAllCategoryOfTreeTest(){
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .where("sortIndex", 1)
                .doPaging(0,100);
        ApiResponse<DeviceCategory> request = client
                .request(DeviceCategoryRequest.getAllCategoryOfTree(queryParamEntity));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }


}
