package org.jetlinks.sdk.model.alarm.request;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.SimpleApiRequest;
import org.jetlinks.sdk.model.alarm.AlarmLevelInfo;
import org.jetlinks.sdk.model.alarm.info.AlarmConfigDetail;
import org.jetlinks.sdk.model.alarm.info.AlarmConfigEntity;
import org.jetlinks.sdk.model.alarm.info.AlarmLevelEntity;
import org.jetlinks.sdk.model.alarm.info.AlarmTargetTypeInfo;
import org.springframework.core.ResolvableType;

import java.util.ArrayList;

/**
 * @author zyl
 * @date 7/8/2023
 */
public class AlarmConfigRequest {

    /**
     * 根据ID查询
     *
     * @param id
     * @return
     */
    public static SimpleApiRequest<AlarmConfigEntity> getConfigById(String id) {
        return SimpleApiRequest
                .<AlarmConfigEntity>builder()
                .get()
                .parameter(id, "id")
                .uri("/alarm/config/" + id)
                .responseType(AlarmConfigEntity.class)
                .build();
    }

    /**
     * 启用告警配置
     *
     * @param id
     * @return
     */
    public static SimpleApiRequest<AlarmConfigEntity> enable(String id) {
        return SimpleApiRequest
                .<AlarmConfigEntity>builder()
                .post()
                .parameter(id, "id")
                .uri("/alarm/config/" + id + "/_enable")
                .responseType(AlarmConfigEntity.class)
                .build();
    }

    /**
     * 禁用告警配置
     *
     * @param id
     * @return
     */
    public static SimpleApiRequest<AlarmConfigEntity> disable(String id) {
        return SimpleApiRequest
                .<AlarmConfigEntity>builder()
                .post()
                .parameter(id, "id")
                .uri("/alarm/config/" + id + "/_disable")
                .responseType(AlarmConfigEntity.class)
                .build();
    }

    /**
     * 查询告警配置详情
     *
     * @param query
     * @return
     */
    public static SimpleApiRequest<PagerResult<AlarmConfigDetail>> queryDetail(QueryParamEntity query) {
        return SimpleApiRequest
                .<PagerResult<AlarmConfigDetail>>builder()
                .post()
                .body(query)
                .uri("/alarm/config/detail/_query")
                .responseType(ResolvableType.forClassWithGenerics(
                        PagerResult.class,
                        AlarmConfigDetail.class
                ))
                .build();
    }

    /**
     * 使用GET方式分页动态查询
     *
     * @param query
     * @return
     */
    public static SimpleApiRequest<PagerResult<AlarmConfigEntity>> queryByGet(QueryParamEntity query) {
        return SimpleApiRequest
                .<PagerResult<AlarmConfigEntity>>builder()
                .get()
                .body(query)
                .uri("/alarm/config/_query")
                .responseType(ResolvableType.forClassWithGenerics(
                        PagerResult.class,
                        AlarmConfigEntity.class
                ))
                .build();
    }

    /**
     * 使用POST方式分页动态查询
     *
     * @param query
     * @return
     */
    public static SimpleApiRequest<PagerResult<AlarmConfigEntity>> queryByPost(QueryParamEntity query) {
        return SimpleApiRequest
                .<PagerResult<AlarmConfigEntity>>builder()
                .post()
                .body(query)
                .uri("/alarm/config/_query")
                .responseType(ResolvableType.forClassWithGenerics(
                        PagerResult.class,
                        AlarmConfigEntity.class
                ))
                .build();
    }

    /**
     * 使用GET方式分页动态查询(不返回总数)
     *
     * @return
     */
    public static SimpleApiRequest<ArrayList<AlarmConfigEntity>> getConfigNoPaging() {
        return SimpleApiRequest
                .<ArrayList<AlarmConfigEntity>>builder()
                .get()
                .uri("/alarm/config/_query/no-paging")
                .responseType(ResolvableType.forClassWithGenerics(
                        ArrayList.class,
                        AlarmConfigEntity.class
                ))
                .build();
    }

    /**
     * 使用POST方式分页动态查询(不返回总数)
     *
     * @return
     */
    public static SimpleApiRequest<ArrayList<AlarmConfigEntity>> postConfigNoPaging(QueryParamEntity queryParamEntity) {
        return SimpleApiRequest
                .<ArrayList<AlarmConfigEntity>>builder()
                .post()
                .body(queryParamEntity)
                .uri("/alarm/config/_query/no-paging")
                .responseType(ResolvableType.forClassWithGenerics(
                        ArrayList.class,
                        AlarmConfigEntity.class
                ))
                .build();
    }

    /**
     * 获取默认告警级别
     * @return
     */
    public static SimpleApiRequest<AlarmLevelEntity> getLevel() {
        return SimpleApiRequest
                .<AlarmLevelEntity>builder()
                .get()
                .uri("/alarm/config/default/level")
                .responseType(AlarmLevelEntity.class)
                .build();
    }

    /**
     * 保存默认告警级别
     *
     * @param infos
     * @return
     */
    public static SimpleApiRequest<AlarmLevelEntity> saveDefaultLevel(ArrayList<AlarmLevelInfo> infos) {
        return SimpleApiRequest
                .<AlarmLevelEntity>builder()
                .patch()
                .body(infos)
                .uri("/alarm/config/default/level")
                .responseType(AlarmLevelEntity.class)
                .build();
    }

    /**
     * 使用POST方式查询总数
     *
     * @param query
     * @return
     */
    public static SimpleApiRequest<Integer> queryCountByPost(QueryParamEntity query) {
        return SimpleApiRequest
                .<Integer>builder()
                .post()
                .body(query)
                .uri("/alarm/config/_count")
                .responseType(Integer.class)
                .build();
    }

    /**
     * 获取支持的告警目标类型
     * @return
     */
    public static SimpleApiRequest<ArrayList<AlarmTargetTypeInfo>> getTypeSupports() {
        return SimpleApiRequest
                .<ArrayList<AlarmTargetTypeInfo>>builder()
                .get()
                .uri("/alarm/config/target-type/supports")
                .responseType(ResolvableType.forClassWithGenerics(
                        ArrayList.class,
                        AlarmTargetTypeInfo.class
                ))
                .build();
    }

}
