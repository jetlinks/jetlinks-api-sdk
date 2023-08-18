package org.jetlinks.sdk.model.scene.request;

import org.jetlinks.sdk.model.SimpleApiRequest;
import org.jetlinks.sdk.model.scene.info.RuleInstanceEntity;

/**
 * 规则实例
 *
 * @author zyl
 * @date 7/8/2023
 */
public class RuleInstanceRequest {
    /**
     * 根据id查询规则实例
     * @param id
     * @return
     */
    public static SimpleApiRequest<RuleInstanceEntity> queryInstance(String id) {
        return SimpleApiRequest
                .<RuleInstanceEntity>builder()
                .get()
                .parameter(id, "id")
                .uri("/rule-engine/instance/" + id)
                .responseType(RuleInstanceEntity.class)
                .build();
    }
}
