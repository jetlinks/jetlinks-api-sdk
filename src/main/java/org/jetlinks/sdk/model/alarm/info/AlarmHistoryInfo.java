package org.jetlinks.sdk.model.alarm.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * @author zyl
 * @date 7/8/2023
 */
@Getter
@Setter
public class AlarmHistoryInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "告警数据ID")
    private String id;

    @Schema(description = "告警配置ID")
    private String alarmConfigId;

    @Schema(description = "告警配置名称")
    private String alarmConfigName;

    @Schema(description = "告警记录ID")
    private String alarmRecordId;

    @Schema(description = "告警级别")
    private int level;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "告警时间")
    private long alarmTime;

    @Schema(description = "告警目标类型")
    private String targetType;

    @Schema(description = "告警目标名称")
    private String targetName;

    @Schema(description = "告警目标Id")
    private String targetId;

    @Schema(description = "告警源类型")
    private String sourceType;

    @Schema(description = "告警源Id")
    private String sourceId;

    @Schema(description = "告警源名称")
    private String sourceName;

    @Schema(description = "告警信息")
    private String alarmInfo;

    @Schema(description = "绑定信息")
    private List<Map<String, Object>> bindings;
}
