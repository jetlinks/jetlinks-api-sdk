package org.jetlinks.sdk.model.device.request;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.client.ApiClient;
import org.jetlinks.sdk.client.ClientConfig;
import org.jetlinks.sdk.client.WebApiClient;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.EnumInfo;
import org.jetlinks.sdk.model.device.info.DeviceProduct;
import org.junit.jupiter.api.Test;

public class DeviceProductRequsetTest {
    ClientConfig clientConfig = new ClientConfig(
            "http://192.168.33.46:8844",
            "w23y8dADCwPkRGnB",
            "dH7eNKTNmca33YstE88HdYRz8x7RZiFt"
    );
    ApiClient client = new WebApiClient(clientConfig);


    @Test
    public void addDeviceProductTest(){
        DeviceProduct deviceProduct = new DeviceProduct();
        deviceProduct.setId("demo_id");
        deviceProduct.setName("demo_name");
        EnumInfo<String> deviceType = new EnumInfo<>();
        deviceType.setValue("device");
        deviceProduct.setDeviceType(deviceType);
        ApiResponse<DeviceProduct> request = client
                .request(DeviceProductRequset.addDeviceProduct(deviceProduct));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }


    @Test
    public void deleteDeviceInstanceTest(){
        String productId = "demo_id";
        ApiResponse<DeviceProduct> request = client.
                request(DeviceProductRequset.deleteDeviceProduct(productId));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }


    @Test
    public void updateDeviceProductTest(){
        DeviceProduct deviceProduct = new DeviceProduct();
        String productId = "demo_id";
        deviceProduct.setName("demo_name");
        EnumInfo<String> deviceType = new EnumInfo<>();
        deviceType.setValue("device");
        deviceProduct.setDeviceType(deviceType);
        ApiResponse<DeviceProduct> request = client
                .request(DeviceProductRequset.updateDeviceProduct(productId, deviceProduct));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }


    @Test
    public void queryDeviceProductTest(){
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .where("id", "demo_id")
                .doPaging(0,12);
        ApiResponse<DeviceProduct> request = client
                .request(DeviceProductRequset.queryDeviceProduct(queryParamEntity));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }
}
