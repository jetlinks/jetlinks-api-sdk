package org.jetlinks.sdk.model.scene.internal.triggers;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.sdk.model.scene.DeviceOperation;
import org.jetlinks.sdk.model.scene.DeviceSelectorSpec;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class DeviceTrigger extends DeviceSelectorSpec implements SceneTriggerProvider.TriggerConfig, Serializable {
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "error.scene_rule_trigger_device_product_cannot_be_null")
    @Schema(description = "产品ID")
    private String productId;

    @Schema(description = "操作方式")
    @NotNull(message = "error.scene_rule_trigger_device_operation_cannot_be_null")
    private DeviceOperation operation;

    @Override
    public void validate() {

    }
}
