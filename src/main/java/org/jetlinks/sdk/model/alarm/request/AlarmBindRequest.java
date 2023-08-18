package org.jetlinks.sdk.model.alarm.request;

import org.jetlinks.sdk.model.SimpleApiRequest;

import java.util.List;

/**
 * @author zyl
 * @date 31/7/2023
 */
public class AlarmBindRequest {

    /**
     * 批量删除告警规则绑定
     * @param alarmId
     * @param ruleId
     * @return
     */
    public static SimpleApiRequest<Integer> deleteRuleBind(String alarmId, List<String> ruleId) {
        return SimpleApiRequest
                .<Integer>builder()
                .post()
                .parameter(alarmId, "alarmId")
                .body(ruleId)
                .uri("/alarm/rule/bind/" + alarmId + "/_delete")
                .responseType(Integer.class)
                .build();
    }
}
