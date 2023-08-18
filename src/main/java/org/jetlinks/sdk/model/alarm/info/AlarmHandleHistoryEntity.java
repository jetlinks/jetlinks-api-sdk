package org.jetlinks.sdk.model.alarm.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.Comment;
import org.hswebframework.ezorm.rdb.mapping.annotation.DefaultValue;
import org.hswebframework.ezorm.rdb.mapping.annotation.EnumCodec;
import org.jetlinks.sdk.model.EnumInfo;
import org.jetlinks.sdk.model.scene.Generators;

/**
 * @author zyl
 * @date 7/8/2023
 */
@Getter
@Setter
@Comment("告警处理记录")
public class AlarmHandleHistoryEntity {

    @Schema(description = "告警配置ID")
    private String alarmId;

    @Schema(description = "告警记录Id")
    private String alarmRecordId;

    @Schema(description = "告警处理类型")
    @EnumCodec
    @ColumnType(javaType = String.class)
    private EnumInfo<String> handleType;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "处理时间")
    private Long handleTime;

    @Schema(description = "告警时间")
    private Long alarmTime;

    @Schema(
            description = "创建者ID(只读)"
            , accessMode = Schema.AccessMode.READ_ONLY
    )
    private String creatorId;

    @DefaultValue(generator = Generators.CURRENT_TIME)
    @Schema(
            description = "创建时间(只读)"
            , accessMode = Schema.AccessMode.READ_ONLY
    )
    private Long createTime;
}
