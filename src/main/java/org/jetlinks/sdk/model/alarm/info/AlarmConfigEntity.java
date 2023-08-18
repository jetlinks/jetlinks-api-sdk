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

import javax.persistence.Column;

/**
 * @author zyl
 * @date 7/8/2023
 */
@Getter
@Setter
@Comment("告警配置表")
public class AlarmConfigEntity {
    @Schema(description = "名称")
    private String name;

    @Schema(description = "告警目标类型")
    private String targetType;

    @Schema(description = "告警级别")
    private Integer level;

    @Schema(description = "关联场景名称")
    @Deprecated
    private String sceneName;

    @Schema(description = "关联场景Id")
    @Deprecated
    private String sceneId;

    @EnumCodec
    @ColumnType(javaType = String.class)
    @DefaultValue("enabled")
    @Schema(description = "状态")
    private EnumInfo<String> state;

    @EnumCodec
    @ColumnType(javaType = String.class)
    @Schema(description = "场景触发类型")
    @Deprecated
    private EnumInfo<String> sceneTriggerType;

    @Schema(description = "说明")
    private String description;

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

    @Schema(description = "更新者ID", accessMode = Schema.AccessMode.READ_ONLY)
    private String modifierId;

    @Column
    @DefaultValue(generator = Generators.CURRENT_TIME)
    @Schema(description = "更新时间")
    private Long modifyTime;
}
