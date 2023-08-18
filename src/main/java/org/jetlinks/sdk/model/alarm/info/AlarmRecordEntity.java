package org.jetlinks.sdk.model.alarm.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.Comment;
import org.hswebframework.ezorm.rdb.mapping.annotation.DefaultValue;
import org.hswebframework.ezorm.rdb.mapping.annotation.EnumCodec;
import org.jetlinks.sdk.model.EnumInfo;

import javax.persistence.Column;

/**
 * @author zyl
 * @date 7/8/2023
 */
@Getter
@Setter
@Comment("告警记录")
public class AlarmRecordEntity {

    @Schema(description = "告警配置ID")
    private String alarmConfigId;

    @Schema(description = "告警配置名称")
    private String alarmName;

    @Schema(description = "告警目标类型")
    private String targetType;

    @Schema(description = "告警目标Id")
    private String targetId;

    @Schema(description = "告警目标Key")
    private String targetKey;

    @Schema(description = "告警目标名称")
    private String targetName;

    @Schema(description = "告警源类型")
    private String sourceType;

    @Schema(description = "告警源Id")
    private String sourceId;

    @Schema(description = "告警源名称")
    private String sourceName;

    @Schema(description = "最近一次告警时间")
    private Long alarmTime;

    @Schema(description = "处理时间")
    private Long handleTime;

    @Schema(description = "告警级别")
    private Integer level;

    @Schema(description = "告警记录状态")
    @EnumCodec
    @ColumnType(javaType = String.class)
    @DefaultValue("normal")
    private EnumInfo<String> state;

    @Column
    @Schema(description = "说明")
    private String description;
}
