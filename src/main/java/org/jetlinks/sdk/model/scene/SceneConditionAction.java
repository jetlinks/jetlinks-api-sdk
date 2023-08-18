package org.jetlinks.sdk.model.scene;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.core.param.Term;

import java.io.Serializable;
import java.util.List;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class SceneConditionAction implements Serializable {
    @Schema(description = "条件")
    private List<Term> when;

    @Schema(description = "防抖配置")
    private ShakeLimit shakeLimit;

    @Schema(description = "满足条件时执行的动作")
    private List<SceneActions> then;
}
