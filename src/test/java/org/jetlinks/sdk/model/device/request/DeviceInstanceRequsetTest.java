package org.jetlinks.sdk.model.device.request;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.client.ApiClient;
import org.jetlinks.sdk.client.ClientConfig;
import org.jetlinks.sdk.client.WebApiClient;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.EnumInfo;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.device.info.DeviceDetail;
import org.jetlinks.sdk.model.device.info.DeviceInstance;
import org.jetlinks.sdk.model.device.info.DeviceProperty;
import org.junit.jupiter.api.Test;

public class DeviceInstanceRequsetTest {
    ClientConfig clientConfig = new ClientConfig(
            "http://192.168.33.46:8844",
            "w23y8dADCwPkRGnB",
            "dH7eNKTNmca33YstE88HdYRz8x7RZiFt"
    );
    ApiClient client = new WebApiClient(clientConfig);

    @Test
    public void getDeviceDetailTest(){
        String deviceId = "demo_id";
        ApiResponse<DeviceDetail> response = client
                .request(DeviceInstanceRequset.getDeviceDetailInfo(deviceId));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void queryDeviceDetailTest(){
        //统一构造Request使用示例
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .where("productId", "demo_device")
                .doPaging(0, 100);
        ApiResponse<PagerResult<DeviceDetail>> request = client.request(DeviceInstanceRequset.queryDeviceDetail(queryParamEntity));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }

    @Test
    public void queryDevicePropertyTest() {
        String deviceId = "demo_id";
        String property = "demo_property";
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .where("property", "demo_property")
                .where("deviceId", "demo_id");
        ApiResponse<PagerResult<DeviceProperty>> response = client
                .request(DeviceInstanceRequset.queryDeviceProperty(deviceId, property, queryParamEntity));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }


    @Test
    public void addDeviceInstanceTest(){
        DeviceInstance deviceInstance = new DeviceInstance();
        deviceInstance.setId("demo_id");
        deviceInstance.setName("demo_name");
        deviceInstance.setProductId("demo_productId");
        EnumInfo<String> deviceType = new EnumInfo<>();
        deviceType.setValue("demo_deviceType");
        deviceInstance.setProductName("demo_productName");
        ApiResponse<DeviceInstance> response = client
                .request(DeviceInstanceRequset.addDeviceInstance(deviceInstance));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }


    @Test
    public void deleteDeviceInstanceTest(){
        String deviceId = "demo_deviceId";
        ApiResponse<DeviceInstance> response = client.
                request(DeviceInstanceRequset.deleteDeviceInstance(deviceId));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void queryDeviceInstanceTest(){
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .where("id", "demo_id")
                .doPaging(0,12);
        ApiResponse<DeviceInstance> response = client
                .request(DeviceInstanceRequset.queryDeviceInstance(queryParamEntity));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }


    @Test
    public void updateDeviceInstanceTest(){
        DeviceInstance deviceInstance = new DeviceInstance();
        String deviceId = "demo_id";
        deviceInstance.setName("demo_name");
        deviceInstance.setProductName("demo_name");
        ApiResponse<DeviceInstance> response = client
                .request(DeviceInstanceRequset.updateDeviceInstance(deviceId, deviceInstance));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void queryDeviceStateTest(){
        String deviceId = "demo_id";
        ApiResponse<DeviceInstance> response = client
                .request(DeviceInstanceRequset.queryDeviceState(deviceId));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }


    @Test
    public void queryDeviceLogTest(){
        String deviceId = "demo_id";
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .where("type", "offline")
                .doPaging(0,12);
        ApiResponse<DeviceInstance> response = client
                .request(DeviceInstanceRequset.queryDeviceOperationLog(deviceId, queryParamEntity));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    // todo
    @Test
    public void updateDeviceMetadataTest(){
        String deviceId = "demo _id";
        String meta = "demo_meta";
        ApiResponse<DeviceInstance> response = client
                .request(DeviceInstanceRequset.updateDeviceMetadata(deviceId, meta));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void getDeviceMetadataCodecsTest(){
        ApiResponse<DeviceInstance> response = client
                .request(DeviceInstanceRequset.getDeviceMetadataCodecs());
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void deleteDeviceMetadataTest(){
        String deviceId = "1688426683861848064";
        ApiResponse<DeviceInstance> response = client
                .request(DeviceInstanceRequset.deleteMetadata(deviceId));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }



    @Test
    public void getDeviceShadowTest(){
        String deviceId = "1688426683861848064";
        ApiResponse<DeviceInstance> response = client
                .request(DeviceInstanceRequset.getDeviceShadow(deviceId));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }


}
