package org.jetlinks.sdk.model.alarm.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.sdk.model.EnumInfo;

import javax.persistence.Column;
import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * @author zyl
 * @date 7/8/2023
 */
@Getter
@Setter
public class AlarmConfigDetail {
    @Schema(description = "告警配置ID")
    private String id;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "告警目标类型")
    private String targetType;

    @Schema(description = "告警级别")
    private Integer level;

    @Schema(description = "关联场景")
    private List<SceneInfo> scene;

    @Schema(description = "状态")
    private EnumInfo<String> state;

    @Schema(description = "场景触发类型")
    private String sceneTriggerType;

    @Schema(description = "说明")
    private String description;

    @Schema(
            description = "创建者ID(只读)"
            , accessMode = Schema.AccessMode.READ_ONLY
    )
    private String creatorId;

    @Schema(
            description = "创建时间(只读)"
            , accessMode = Schema.AccessMode.READ_ONLY
    )
    private Long createTime;

    @Schema(description = "更新者ID", accessMode = Schema.AccessMode.READ_ONLY)
    private String modifierId;

    @Schema(description = "更新时间")
    private Long modifyTime;

    /**
     * 场景联动信息
     */
    @Getter
    @Setter
    public static class SceneInfo {
        @Schema(description = "场景联动ID")
        private String id;

        @Column(nullable = false)
        @Schema(description = "场景联动名称")
        @NotBlank
        private String name;

        @Schema(description = "触发器类型")
        private String triggerType;

        @Schema(description = "状态")
        private EnumInfo<String> state;
    }
}

