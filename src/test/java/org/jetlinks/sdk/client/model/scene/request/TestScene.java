package org.jetlinks.sdk.client.model.scene.request;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.client.ApiClient;
import org.jetlinks.sdk.client.ClientConfig;
import org.jetlinks.sdk.client.WebApiClient;
import org.jetlinks.sdk.model.ApiResponse;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.scene.SceneRule;
import org.jetlinks.sdk.model.scene.Trigger;
import org.jetlinks.sdk.model.scene.info.SceneInfo;
import org.jetlinks.sdk.model.scene.request.SceneExecuteRequest;
import org.jetlinks.sdk.model.scene.request.SceneRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * @author zyl
 * @date 3/8/2023
 */
public class TestScene {

    ClientConfig clientConfig = new ClientConfig(
            "http://192.168.33.46:8844",
            "w23y8dADCwPkRGnB",
            "dH7eNKTNmca33YstE88HdYRz8x7RZiFt"
    );

    ApiClient client = new WebApiClient(clientConfig);

    @Test
    public void testQueryScene() {
        QueryParamEntity queryParamEntity = new QueryParamEntity();
        queryParamEntity
                .toQuery()
                .doPaging(0, 12);
        ApiResponse<PagerResult<SceneInfo>> response = client
                .request(SceneRequest
                        .queryScene(queryParamEntity));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testCreateScene() {
        SceneRule sceneRule = new SceneRule();
        sceneRule.setName("zyl-sdk测试");
        Trigger trigger = new Trigger();
        trigger.setType("device");
        sceneRule.setTrigger(trigger);
        ApiResponse<SceneInfo> response = client
                .request(SceneRequest.createScene(sceneRule));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testUpdateScene() {
        SceneRule sceneRule = new SceneRule();
        sceneRule.setId("1687358937564729344");
        String id = "1687358937564729344";
        sceneRule.setName("zyl-99-sdk");
        Trigger trigger = new Trigger();
        trigger.setType("device");
        sceneRule.setTrigger(trigger);
        ApiResponse<SceneInfo> response = client
                .request(SceneRequest.updateScene(id, sceneRule));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testDeleteScene() {
        String id = "1687358937564729344";
        ApiResponse<SceneInfo> response = client
                .request(SceneRequest.deleteScene(id));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }


    @Test
    public void testGetSceneById() {
        String id = "1665666998354030592";
        ApiResponse<SceneInfo> response = client
                .request(SceneRequest.getSceneById(id));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testEnableScene() {
        String id = "1687398810707316736";
        ApiResponse<SceneInfo> response = client
                .request(SceneRequest.enableScene(id));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testDisableScene() {
        String id = "1687398810707316736";
        ApiResponse<SceneInfo> response = client
                .request(SceneRequest.disableScene(id));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testExecuteScene() {
        long t = System.currentTimeMillis();
        Map<String, Object> data = new HashMap<>();
        data.put("_now", t);
        data.put("_timestamp", t);
        String id = "1688372205322260480";
        ApiResponse<SceneInfo> response = client
                .request(SceneRequest.executeScene(id, data));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }

    @Test
    public void testBatchExecute() {
        long t = System.currentTimeMillis();
        ArrayList<SceneExecuteRequest> requests = new ArrayList<>();

        SceneExecuteRequest request1 = new SceneExecuteRequest();
        request1.setId("1688747848681672704");
        Map<String, Object> data1 = new HashMap<>();
        data1.put("_now", t);
        data1.put("_timestamp", t);
        request1.setContext(data1);


        SceneExecuteRequest request2 = new SceneExecuteRequest();
        request2.setId("1688747457109839872");
        Map<String, Object> data2 = new HashMap<>();
        data2.put("_now", t);
        data2.put("_timestamp", t);
        request2.setContext(data2);

        requests.add(request1);
        requests.add(request2);

        ApiResponse<Object> response = client
                .request(SceneRequest.batchExecute(requests));
        System.out.println(JSON.toJSONString(response, SerializerFeature.PrettyFormat));
    }


}
