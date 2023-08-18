package org.jetlinks.sdk.model.alarm.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.Comment;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.jetlinks.sdk.model.alarm.AlarmLevelInfo;

import java.sql.JDBCType;
import java.util.List;

/**
 * @author zyl
 * @date 7/8/2023
 */
@Getter
@Setter
@Comment("告警级别")
public class AlarmLevelEntity {

    @Schema(description = "名称")
    private String name;

    @ColumnType(jdbcType = JDBCType.LONGVARCHAR)
    @JsonCodec
    @Schema(description = "配置信息")
    private List<AlarmLevelInfo> levels;

    @Schema(description = "说明")
    private String description;
}
