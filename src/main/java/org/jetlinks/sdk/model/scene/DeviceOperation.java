package org.jetlinks.sdk.model.scene;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.core.message.function.FunctionParameter;

import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class DeviceOperation {
    //当前值
    public static final String property_value_type_current = "current";

    @NotNull(message = "error.scene_rule_trigger_device_operation_cannot_be_null")
    private Operator operator;

    @Schema(description = "[operator]为[readProperty,writeProperty,invokeFunction]时不能为空")
    private TimerSpec timer;

    @Schema(description = "[operator]为[reportEvent]时不能为空")
    private String eventId;

    @Schema(description = "[operator]为[readProperty]时不能为空")
    private List<String> readProperties;

    @Schema(description = "[operator]为[writeProperty]时不能为空")
    private Map<String, Object> writeProperties;

    @Schema(description = "[operator]为[invokeFunction]时不能为空")
    private String functionId;

    @Schema(description = "[operator]为[invokeFunction]时不能为空")
    private List<FunctionParameter> functionParameters;

    public enum Operator {
        online,
        offline,
        //事件上报
        reportEvent,
        //属性上报
        reportProperty,
        //读取属性
        readProperty,
        //修改属性
        writeProperty,
        //调用功能
        invokeFunction;

    }
}
