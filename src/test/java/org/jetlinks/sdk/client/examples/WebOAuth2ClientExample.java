package org.jetlinks.sdk.client.examples;


import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.jetlinks.sdk.client.ApiClient;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.device.DeviceInfo;
import org.jetlinks.sdk.model.device.QueryDeviceRequest;
import org.jetlinks.sdk.oauth2.client.OAuth2Config;
import org.jetlinks.sdk.oauth2.client.WebOAuth2ApiClient;

public class WebOAuth2ClientExample {

    public static void main(String[] args) {
        OAuth2Config clientConfig = new OAuth2Config()
                .setServerBaseUrl("http://127.0.0.1:9000/api")
                .setAppId("1673272555247095808")
                .setSecureKey("c837ADCbSt8xszH5demTCfPCm388TiHJ")
                .setRedirectUri("http://127.0.0.1:8000")
                .validate();

        ApiClient client = new WebOAuth2ApiClient(clientConfig);

        ApiResponse<PagerResult<DeviceInfo>> response = client
                .request(QueryDeviceRequest
                                 .of(query -> query
                                         .where("productId", "demo-device")
                                         .doPaging(0, 100)));

        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));

    }

}