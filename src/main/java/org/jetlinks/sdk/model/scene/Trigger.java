package org.jetlinks.sdk.model.scene;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.sdk.model.scene.internal.triggers.DeviceTrigger;
import org.jetlinks.sdk.model.scene.internal.triggers.TimerTrigger;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Map;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class Trigger implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "触发方式")
    @NotNull(message = "error.scene_rule_trigger_cannot_be_null")
    private String type;

    @Schema(description = "防抖配置")
    private GroupShakeLimit shakeLimit;

    @Schema(description = "[type]为[device]时不能为空")
    private DeviceTrigger device;

    @Schema(description = "[type]为[timer]时不能为空")
    private TimerTrigger timer;

    @Schema(description = "[type]不为[device,timer,collector]时不能为控")
    private Map<String, Object> configuration;

    @Getter
    @Setter
    public static class GroupShakeLimit extends ShakeLimit {

        //暂时没有实现其他方式
        @Schema(description = "分组类型:device,product,org...")
        @Hidden
        private String groupType;

    }
}
