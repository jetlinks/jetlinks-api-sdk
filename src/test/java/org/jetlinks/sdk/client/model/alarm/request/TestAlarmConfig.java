package org.jetlinks.sdk.client.model.alarm.request;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.client.ApiClient;
import org.jetlinks.sdk.client.ClientConfig;
import org.jetlinks.sdk.client.WebApiClient;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.alarm.AlarmLevelInfo;
import org.jetlinks.sdk.model.alarm.info.AlarmConfigDetail;
import org.jetlinks.sdk.model.alarm.info.AlarmConfigEntity;
import org.jetlinks.sdk.model.alarm.info.AlarmLevelEntity;
import org.jetlinks.sdk.model.alarm.info.AlarmTargetTypeInfo;
import org.jetlinks.sdk.model.alarm.request.AlarmConfigRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

/**
 * @author zyl
 * @date 7/8/2023
 */
public class TestAlarmConfig {

    ClientConfig clientConfig = new ClientConfig(
            "http://192.168.33.46:8844",
            "w23y8dADCwPkRGnB",
            "dH7eNKTNmca33YstE88HdYRz8x7RZiFt"
    );

    ApiClient client = new WebApiClient(clientConfig);

    @Test
    public void testGetConfigById() {
        String id = "1688434323304239104";
        ApiResponse<AlarmConfigEntity> response = client
                .request(AlarmConfigRequest.getConfigById(id));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testEnableAlarmConfig() {
        String id = "1688434323304239104";
        ApiResponse<AlarmConfigEntity> response = client
                .request(AlarmConfigRequest.enable(id));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testDisableAlarmConfig() {
        String id = "1688434323304239104";
        ApiResponse<AlarmConfigEntity> response = client
                .request(AlarmConfigRequest.disable(id));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void queryDetail() {
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .doPaging(0, 12);
        ApiResponse<PagerResult<AlarmConfigDetail>> response = client
                .request(AlarmConfigRequest
                        .queryDetail(queryParamEntity));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void queryByGet() {
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .doPaging(0, 12);
        ApiResponse<PagerResult<AlarmConfigEntity>> response = client
                .request(AlarmConfigRequest
                        .queryByGet(queryParamEntity));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void queryByPost() {
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .doPaging(0, 12);
        ApiResponse<PagerResult<AlarmConfigEntity>> response = client
                .request(AlarmConfigRequest
                        .queryByPost(queryParamEntity));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testGetNoPaging() {
        ApiResponse<ArrayList<AlarmConfigEntity>> response = client
                .request(AlarmConfigRequest
                        .getConfigNoPaging());
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testPostNoPaging() {
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .doPaging(0, 12);
        ApiResponse<ArrayList<AlarmConfigEntity>> response = client
                .request(AlarmConfigRequest
                        .postConfigNoPaging(queryParamEntity));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testGetLevel() {
        ApiResponse<AlarmLevelEntity> response = client
                .request(AlarmConfigRequest
                        .getLevel());
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testSaveDefaultLevel() {
        ArrayList<AlarmLevelInfo> infos = new ArrayList<>();

        AlarmLevelInfo info1 = new AlarmLevelInfo();
        info1.setLevel(1);
        info1.setTitle("紧急报警");

        AlarmLevelInfo info2 = new AlarmLevelInfo();
        info2.setLevel(2);
        info2.setTitle("紧急");

        AlarmLevelInfo info3 = new AlarmLevelInfo();
        info3.setLevel(3);
        info3.setTitle("严重");

        AlarmLevelInfo info4 = new AlarmLevelInfo();
        info4.setLevel(4);
        info4.setTitle("正常");

        AlarmLevelInfo info5 = new AlarmLevelInfo();
        info5.setLevel(5);
        info5.setTitle("正常");

        infos.add(info1);
        infos.add(info2);
        infos.add(info3);
        infos.add(info4);
        infos.add(info5);

        ApiResponse<AlarmLevelEntity> response = client
                .request(AlarmConfigRequest
                        .saveDefaultLevel(infos));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testQueryCountByPost() {
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .doPaging(0, 12);
        ApiResponse<Integer> response = client
                .request(AlarmConfigRequest
                        .queryCountByPost(queryParamEntity));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testGetTypeSupports() {
        ApiResponse<ArrayList<AlarmTargetTypeInfo>> response = client
                .request(AlarmConfigRequest
                        .getTypeSupports());
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }
}
