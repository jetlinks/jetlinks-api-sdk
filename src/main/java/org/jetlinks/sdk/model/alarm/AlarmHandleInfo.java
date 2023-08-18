package org.jetlinks.sdk.model.alarm;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.sdk.model.EnumInfo;

import javax.validation.constraints.NotBlank;

/**
 * @author zyl
 * @date 7/8/2023
 */
@Getter
@Setter
public class AlarmHandleInfo {
    @Schema(description = "告警记录ID")
    @NotBlank
    private String alarmRecordId;

    @Schema(description = "告警ID")
    @NotBlank
    private String alarmConfigId;

    @Schema(description = "告警时间")
    @NotBlank
    private Long alarmTime;

    @Schema(description = "处理说明")
    private String describe;

    @Schema(description = "处理时间")
    private Long handleTime;

    @NotBlank
    @Schema(description = "处理类型")
    private EnumInfo<String> type;

    @Schema(description = "处理后的状态")
    @NotBlank
    private EnumInfo<String> state;
}
