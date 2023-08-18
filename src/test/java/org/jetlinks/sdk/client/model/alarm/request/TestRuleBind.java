package org.jetlinks.sdk.client.model.alarm.request;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.jetlinks.sdk.client.ApiClient;
import org.jetlinks.sdk.client.ClientConfig;
import org.jetlinks.sdk.client.WebApiClient;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.alarm.request.AlarmBindRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * @author zyl
 * @date 7/8/2023
 */
public class TestRuleBind {

    ClientConfig clientConfig = new ClientConfig(
            "http://192.168.33.46:8844",
            "w23y8dADCwPkRGnB",
            "dH7eNKTNmca33YstE88HdYRz8x7RZiFt"
    );

    ApiClient client = new WebApiClient(clientConfig);

    @Test
    public void testDeleteRuleBind() {
        String alarmId = "1688434323304239104";
        List<String> ruleId = new ArrayList<>();
        ruleId.add("1666367344030859264");
        ruleId.add("1665909772219297792");
        ruleId.add("1665904914690977792");
        ApiResponse<Integer> response = client
                .request(AlarmBindRequest.deleteRuleBind(alarmId, ruleId));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

}
