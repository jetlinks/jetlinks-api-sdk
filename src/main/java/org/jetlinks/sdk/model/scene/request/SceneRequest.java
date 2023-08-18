package org.jetlinks.sdk.model.scene.request;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.SimpleApiRequest;
import org.jetlinks.sdk.model.scene.SceneRule;
import org.jetlinks.sdk.model.scene.info.SceneInfo;
import org.springframework.core.ResolvableType;

import java.util.ArrayList;
import java.util.Map;

/**
 * 场景管理
 *
 * @author zyl
 * @date 31/7/2023
 */
public class SceneRequest {

    /**
     * 分页查询场景联动
     *
     * @param query
     * @return
     */
    public static SimpleApiRequest<PagerResult<SceneInfo>> queryScene(QueryParamEntity query) {
        return SimpleApiRequest
                .<PagerResult<SceneInfo>>builder()
                .post()
                .body(query)
                .uri("/rule-engine/instance/")
                .responseType(ResolvableType
                        .forClassWithGenerics(
                                PagerResult.class,
                                SceneInfo.class
                        ))
                .build();
    }

    /**
     * 新增场景联动
     *
     * @param sceneRule
     * @return
     */
    public static SimpleApiRequest<SceneInfo> createScene(SceneRule sceneRule) {
        return SimpleApiRequest
                .<SceneInfo>builder()
                .post()
                .body(sceneRule)
                .uri("/scene")
                .responseType(SceneInfo.class)
                .build();
    }


    /**
     * 更新场景
     *
     * @param id
     * @param sceneRule
     * @return
     */
    public static SimpleApiRequest<SceneInfo> updateScene(String id, SceneRule sceneRule) {
        return SimpleApiRequest
                .<SceneInfo>builder()
                .put()
                .parameter(id, "id")
                .body(sceneRule)
                .uri("/scene/" + id)
                .responseType(SceneInfo.class)
                .build();
    }

    /**
     * 删除场景
     *
     * @param id
     * @return
     */
    public static SimpleApiRequest<SceneInfo> deleteScene(String id) {
        return SimpleApiRequest
                .<SceneInfo>builder()
                .delete()
                .parameter(id, "id")
                .uri("/scene/" + id)
                .responseType(SceneInfo.class)
                .build();
    }

    /**
     * 根据id查询场景
     *
     * @param id
     * @return
     */
    public static SimpleApiRequest<SceneInfo> getSceneById(String id) {
        return SimpleApiRequest
                .<SceneInfo>builder()
                .get()
                .parameter(id, "id")
                .uri("/scene/" + id)
                .responseType(SceneInfo.class)
                .build();
    }


    /**
     * 启用场景
     *
     * @param id
     * @return
     */
    public static SimpleApiRequest enableScene(String id) {
        return SimpleApiRequest
                .<SceneInfo>builder()
                .put()
                .parameter(id, "id")
                .uri("/scene/" + id + "/_enable")
                .responseType(SceneInfo.class)
                .build();
    }

    /**
     * 禁用场景
     *
     * @param id
     * @return
     */
    public static SimpleApiRequest disableScene(String id) {
        return SimpleApiRequest
                .<SceneInfo>builder()
                .put()
                .parameter(id, "id")
                .uri("/scene/" + id + "/_disable")
                .responseType(SceneInfo.class)
                .build();
    }

    /**
     * 手动触发场景
     *
     * @param id
     * @return
     */
    public static SimpleApiRequest executeScene(String id, Map<String, Object> data) {
        return SimpleApiRequest
                .<SceneInfo>builder()
                .post()
                .parameter(id, "id")
                .body(data)
                .uri("/scene/" + id + "/_execute")
                .responseType(SceneInfo.class)
                .build();
    }


    /**
     * 批量手动执行场景
     * @param request
     * @return
     */
    public static SimpleApiRequest<Object> batchExecute(ArrayList<SceneExecuteRequest> request) {
        return SimpleApiRequest
                .<Object>builder()
                .post()
                .body(request)
                .uri("/scene/batch/_execute")
                .responseType(Object.class)
                .build();
    }

}


