package org.jetlinks.sdk.client.examples;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.checkerframework.checker.units.qual.C;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.client.ApiClient;
import org.jetlinks.sdk.client.ClientConfig;
import org.jetlinks.sdk.client.WebApiClient;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.SimpleApiRequest;
import org.jetlinks.sdk.model.device.DeviceInfo;
import org.jetlinks.sdk.model.device.QueryDeviceRequest;
import org.springframework.http.HttpMethod;

public class WebApiClientExample {

    public static void main(String[] args) {
        ClientConfig clientConfig = new ClientConfig(
                "http://localhost:9000/jetlinks",
                "DaYsxpiWSfdTAPJyKW8rP2WAGyWErnsR",
                "test"
        );
//        clientConfig.setTokenMod(true);

        ApiClient client = new WebApiClient(clientConfig);

        ApiResponse<PagerResult<DeviceInfo>> response = client
                .request(QueryDeviceRequest
                                 .of(query -> query
                                         .where("productId", "demo-device")
                                         .doPaging(0,100)));

        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));

    }

}