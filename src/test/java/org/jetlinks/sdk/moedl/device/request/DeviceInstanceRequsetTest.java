package org.jetlinks.sdk.moedl.device.request;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.client.ApiClient;
import org.jetlinks.sdk.client.ClientConfig;
import org.jetlinks.sdk.client.WebApiClient;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.device.info.DeviceInstance;
import org.jetlinks.sdk.model.device.info.DeviceProperty;
import org.jetlinks.sdk.model.device.request.DeviceInstanceRequset;
import org.junit.jupiter.api.Test;

public class DeviceInstanceRequsetTest {
    ClientConfig clientConfig = new ClientConfig(
            "http://120.77.179.54:8844",
            "E2AabA4DWXrrr48J",
            "dyYZersHsr8YKhkMQHjmsjB5RwaWdCjy"
    );
    ApiClient client = new WebApiClient(clientConfig);

    //前端按钮没找到
    @Test
    public void queryDevicePropertyTest() {
        String deviceId = "12";
        String property = "6660";
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .where("property", "property")
                .where("deviceId", "deviceId");
        ApiResponse<PagerResult<DeviceProperty>> request = client
                .request(DeviceInstanceRequset.queryDeviceProperty(deviceId, property, queryParamEntity));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }

    //测试通过
    @Test
    public void addDeviceInstanceTest(){
        DeviceInstance deviceInstance = new DeviceInstance();
        deviceInstance.setId("005");
        deviceInstance.setName("test08035555");
        deviceInstance.setProductId("1656546670239813688");
        deviceInstance.setProductName("测试产品");
        ApiResponse<DeviceInstance> request = client
                .request(DeviceInstanceRequset.addDeviceInstance(deviceInstance));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }

    //测试显示错误,但实际上可以删除
    @Test
    public void deleteDeviceInstanceTest(){
        String deviceId = "0005";
        ApiResponse<DeviceInstance> request = client.
                request(DeviceInstanceRequset.deleteDeviceInstance(deviceId));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }
    //测试通过
    @Test
    public void queryDeviceInstanceTest(){
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .where("id", "0005")
                .doPaging(0,12);
        ApiResponse<DeviceInstance> request = client
                .request(DeviceInstanceRequset.queryDeviceInstance(queryParamEntity));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }

    //测试通过
    @Test
    public void updateDeviceInstanceTest(){
        DeviceInstance deviceInstance = new DeviceInstance();
        String deviceId = "0005";
        deviceInstance.setName("test0803a1");
        deviceInstance.setParentId("1656546670239813688");
        deviceInstance.setProductName("测试产品");
        ApiResponse<DeviceInstance> request = client
                .request(DeviceInstanceRequset.updateDeviceInstance(deviceId, deviceInstance));
        System.out.println(JSON.toJSONString(request, SerializerFeature.PrettyFormat));
    }


}
