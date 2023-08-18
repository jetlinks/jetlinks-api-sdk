package org.jetlinks.sdk.model.scene;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.core.param.Term;
import org.jetlinks.sdk.model.scene.internal.actions.AlarmAction;
import org.jetlinks.sdk.model.scene.internal.actions.DelayAction;
import org.jetlinks.sdk.model.scene.internal.actions.DeviceAction;
import org.jetlinks.sdk.model.scene.internal.actions.NotifyAction;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class SceneAction implements Serializable {
    @Schema(description = "执行器类型")
    @NotNull
    private String executor;

    @Schema(description = "执行器类型为[notify]时不能为空")
    private NotifyAction notify;

    @Schema(description = "执行器类型为[delay]时不能为空")
    private DelayAction delay;

    @Schema(description = "执行器类型为[device]时不能为空")
    private DeviceAction device;

    @Schema(description = "执行器类型为[alarm]时不能为空")
    private AlarmAction alarm;

    @Schema(description = "输出过滤条件,串行执行动作时,满足条件才会进入下一个节点")
    private List<Term> terms;

    @Schema(description = "其他执行器配置")
    private Map<String, Object> configuration;

    @Schema(description = "拓展信息")
    private Map<String, Object> options;
}
