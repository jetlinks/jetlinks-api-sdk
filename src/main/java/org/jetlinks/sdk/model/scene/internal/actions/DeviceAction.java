package org.jetlinks.sdk.model.scene.internal.actions;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.sdk.model.scene.DeviceSelectorSpec;

import java.util.Map;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class DeviceAction extends DeviceSelectorSpec {
    @Schema(description = "产品ID")
    private String productId;

    @Schema(description = "设备指令")
    private Map<String, Object> message;
}
