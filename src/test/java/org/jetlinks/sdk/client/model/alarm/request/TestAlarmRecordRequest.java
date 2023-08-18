package org.jetlinks.sdk.client.model.alarm.request;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.client.ApiClient;
import org.jetlinks.sdk.client.ClientConfig;
import org.jetlinks.sdk.client.WebApiClient;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.EnumInfo;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.alarm.AlarmHandleInfo;
import org.jetlinks.sdk.model.alarm.info.AlarmHandleHistoryEntity;
import org.jetlinks.sdk.model.alarm.info.AlarmRecordEntity;
import org.jetlinks.sdk.model.alarm.request.AlarmRecordRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

/**
 * @author zyl
 * @date 7/8/2023
 */
public class TestAlarmRecordRequest {


    ClientConfig clientConfig = new ClientConfig(
            "http://192.168.33.46:8844",
            "w23y8dADCwPkRGnB",
            "dH7eNKTNmca33YstE88HdYRz8x7RZiFt"
    );

    ApiClient client = new WebApiClient(clientConfig);

    @Test
    public void testGetConfigById() {
        String id = "a3f9fe9c15505703ef657c2be4245371";
        ApiResponse<AlarmRecordEntity> response = client
                .request(AlarmRecordRequest.getConfigById(id));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testPostHandleHistory() {
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .doPaging(0, 12);
        ApiResponse<PagerResult<AlarmHandleHistoryEntity>> response = client
                .request(AlarmRecordRequest
                        .PostHandleHistory(queryParamEntity));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testQueryHandleHistoryByGet() {
        ApiResponse<PagerResult<AlarmRecordEntity>> response = client
                .request(AlarmRecordRequest
                        .queryHandleHistoryByGet());
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testQueryHandleHistoryByPost() {
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .doPaging(0, 12);
        ApiResponse<PagerResult<AlarmRecordEntity>> response = client
                .request(AlarmRecordRequest
                        .queryHandleHistoryByPost(queryParamEntity));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testQueryHandleHistoryByGetNoPaging() {
        ApiResponse<ArrayList<AlarmRecordEntity>> response = client
                .request(AlarmRecordRequest
                        .queryHandleHistoryByGetNoPaging());
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }


    @Test
    public void testHandleAlarm() {
        AlarmHandleInfo info = new AlarmHandleInfo();
        info.setAlarmRecordId("a3f9fe9c15505703ef657c2be4245371");
        info.setAlarmConfigId("1688434323304239104");

        long t = System.currentTimeMillis();
        info.setAlarmTime(t);

        EnumInfo<String> enumInfo1 = new EnumInfo<>();
        enumInfo1.setValue("user");
        info.setType(enumInfo1);

        EnumInfo<String> enumInfo2 = new EnumInfo<>();
        enumInfo2.setValue("normal");
        info.setState(enumInfo2);

        info.setDescribe("test-zyl1");

        ApiResponse<Object> response = client
                .request(AlarmRecordRequest.handleAlarm(info));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

}
