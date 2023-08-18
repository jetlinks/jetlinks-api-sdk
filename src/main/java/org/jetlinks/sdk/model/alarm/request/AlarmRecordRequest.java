package org.jetlinks.sdk.model.alarm.request;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.SimpleApiRequest;
import org.jetlinks.sdk.model.alarm.AlarmHandleInfo;
import org.jetlinks.sdk.model.alarm.info.AlarmHandleHistoryEntity;
import org.jetlinks.sdk.model.alarm.info.AlarmRecordEntity;
import org.springframework.core.ResolvableType;

import java.util.ArrayList;

/**
 * @author zyl
 * @date 7/8/2023
 */
public class AlarmRecordRequest {

    /**
     * 根据ID查询
     *
     * @param id
     * @return
     */
    public static SimpleApiRequest<AlarmRecordEntity> getConfigById(String id) {
        return SimpleApiRequest
                .<AlarmRecordEntity>builder()
                .get()
                .parameter(id, "id")
                .uri("/alarm/record/" + id)
                .responseType(AlarmRecordEntity.class)
                .build();
    }

    /**
     * 告警处理历史查询
     *
     * @param queryParamEntity
     * @return
     */
    public static SimpleApiRequest<PagerResult<AlarmHandleHistoryEntity>> PostHandleHistory(QueryParamEntity queryParamEntity) {
        return SimpleApiRequest
                .<PagerResult<AlarmHandleHistoryEntity>>builder()
                .post()
                .body(queryParamEntity)
                .uri("/alarm/record/handle-history/_query")
                .responseType(ResolvableType.forClassWithGenerics(
                        PagerResult.class,
                        AlarmHandleHistoryEntity.class
                ))
                .build();
    }

    /**
     * 使用GET方式分页动态查询告警记录
     *
     * @return
     */
    public static SimpleApiRequest<PagerResult<AlarmRecordEntity>> queryHandleHistoryByGet() {
        return SimpleApiRequest
                .<PagerResult<AlarmRecordEntity>>builder()
                .get()
                .uri("/alarm/record/_query")
                .responseType(ResolvableType.forClassWithGenerics(
                        PagerResult.class,
                        AlarmRecordEntity.class
                ))
                .build();
    }

    /**
     * 使用POST方式分页动态查询告警记录
     *
     * @param queryParamEntity
     * @return
     */
    public static SimpleApiRequest<PagerResult<AlarmRecordEntity>> queryHandleHistoryByPost(QueryParamEntity queryParamEntity) {
        return SimpleApiRequest
                .<PagerResult<AlarmRecordEntity>>builder()
                .post()
                .body(queryParamEntity)
                .uri("/alarm/record/_query")
                .responseType(ResolvableType.forClassWithGenerics(
                        PagerResult.class,
                        AlarmRecordEntity.class
                ))
                .build();
    }

    /**
     * 使用GET方式分页动态查询(不返回总数)
     *
     * @return
     */
    public static SimpleApiRequest<ArrayList<AlarmRecordEntity>> queryHandleHistoryByGetNoPaging() {
        return SimpleApiRequest
                .<ArrayList<AlarmRecordEntity>>builder()
                .get()
                .uri("/alarm/record/_query/no-paging")
                .responseType(ResolvableType.forClassWithGenerics(
                        ArrayList.class,
                        AlarmRecordEntity.class
                ))
                .build();
    }


    /**
     * 处理告警
     *
     * @param alarmHandleInfo
     * @return
     */
    public static SimpleApiRequest<Object> handleAlarm(AlarmHandleInfo alarmHandleInfo) {
        return SimpleApiRequest
                .<Object>builder()
                .post()
                .body(alarmHandleInfo)
                .uri("/alarm/record/_handle")
                .responseType(Object.class)
                .build();
    }
}
