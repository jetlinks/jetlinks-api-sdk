package org.jetlinks.sdk.model.alarm.request;

import org.hswebframework.web.api.crud.entity.QueryParamEntity;
import org.jetlinks.sdk.model.PagerResult;
import org.jetlinks.sdk.model.SimpleApiRequest;
import org.jetlinks.sdk.model.alarm.info.AlarmHistoryInfo;
import org.springframework.core.ResolvableType;

/**
 * @author zyl
 * @date 8/8/2023
 */
public class AlarmHistoryRequest {

    /**
     * 告警历史查询
     * @param queryParam
     * @return
     */
    public static SimpleApiRequest<PagerResult<AlarmHistoryInfo>> queryHistory(QueryParamEntity queryParam) {
        return SimpleApiRequest
                .<PagerResult<AlarmHistoryInfo>>builder()
                .post()
                .body(queryParam)
                .uri("/alarm/history/_query")
                .responseType(ResolvableType.forClassWithGenerics(
                        PagerResult.class,
                        AlarmHistoryInfo.class
                ))
                .build();
    }
}
