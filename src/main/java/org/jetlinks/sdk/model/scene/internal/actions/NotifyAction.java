package org.jetlinks.sdk.model.scene.internal.actions;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.sdk.model.scene.VariableSource;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;
import java.util.Map;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class NotifyAction implements Serializable {
    @Schema(description = "通知类型")
    @NotBlank(message = "error.scene_rule_actions_notify_type_cannot_be_empty")
    private String notifyType;

    @Schema(description = "通知配置ID")
    @NotBlank(message = "error.scene_rule_actions_notify_id_cannot_be_empty")
    private String notifierId;

    @Schema(description = "通知模版ID")
    @NotBlank(message = "error.scene_rule_actions_notify_template_cannot_be_blank")
    private String templateId;

    /**
     * 变量值的格式可以为{@link  VariableSource}
     */
    @Schema(description = "通知变量")
    @NotBlank(message = "error.scene_rule_actions_notify_variables_cannot_be_blank")
    private Map<String, Object> variables;
}
