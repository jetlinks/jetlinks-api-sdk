package org.jetlinks.sdk.client.model.alarm.request;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.client.ApiClient;
import org.jetlinks.sdk.client.ClientConfig;
import org.jetlinks.sdk.client.WebApiClient;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.alarm.info.AlarmHistoryInfo;
import org.jetlinks.sdk.model.alarm.request.AlarmHistoryRequest;
import org.junit.jupiter.api.Test;

/**
 * @author zyl
 * @date 8/8/2023
 */
public class TestAlarmHistory {


    ClientConfig clientConfig = new ClientConfig(
            "http://192.168.33.46:8844",
            "w23y8dADCwPkRGnB",
            "dH7eNKTNmca33YstE88HdYRz8x7RZiFt"
    );

    ApiClient client = new WebApiClient(clientConfig);

    @Test
    public void testGetHistory() {
        QueryParamEntity queryParam = new QueryParamEntity();
        queryParam
                .toQuery()
                .doPaging(0, 12);
        ApiResponse<PagerResult<AlarmHistoryInfo>> response = client
                .request(AlarmHistoryRequest
                        .queryHistory(queryParam));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }
}
