package org.jetlinks.sdk.model.scene.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.rdb.mapping.annotation.ColumnType;
import org.hswebframework.ezorm.rdb.mapping.annotation.DefaultValue;
import org.hswebframework.ezorm.rdb.mapping.annotation.EnumCodec;
import org.hswebframework.ezorm.rdb.mapping.annotation.JsonCodec;
import org.jetlinks.sdk.model.EnumInfo;
import org.jetlinks.sdk.model.scene.Generators;
import org.jetlinks.sdk.model.scene.SceneAction;
import org.jetlinks.sdk.model.scene.SceneConditionAction;
import org.jetlinks.sdk.model.scene.Trigger;

import javax.persistence.Column;
import java.sql.JDBCType;
import java.util.List;
import java.util.Map;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class SceneInfo {

    @Schema(description = "告警名称")
    private String name;

    @Schema(description = "触发器类型")
    private String triggerType;

    @JsonCodec
    @ColumnType(javaType = String.class, jdbcType = JDBCType.LONGVARCHAR)
    @Schema(description = "触发器")
    private Trigger trigger;

    @JsonCodec
    @ColumnType(javaType = String.class, jdbcType = JDBCType.LONGVARCHAR)
    @Schema(description = "触发条件")
    private List<Term> terms;

    @Schema(description = "是否并行执行动作")
    @DefaultValue("false")
    private Boolean parallel;

    @JsonCodec
    @ColumnType(javaType = String.class, jdbcType = JDBCType.LONGVARCHAR)
    @Schema(description = "执行动作")
    private List<SceneAction> actions;

    @JsonCodec
    @ColumnType(javaType = String.class, jdbcType = JDBCType.LONGVARCHAR)
    @Schema(description = "动作分支")
    private List<SceneConditionAction> branches;

    @Schema(description = "创建人")
    private String creatorId;

    @Schema(description = "创建时间")
    @DefaultValue(generator = Generators.CURRENT_TIME)
    private Long createTime;

    @Schema(description = "修改人")
    private String modifierId;

    @Schema(description = "修改时间")
    @DefaultValue(generator = Generators.CURRENT_TIME)
    private Long modifyTime;

    @Schema(description = "启动时间")
    private Long startTime;

    @Schema(description = "状态")
    @EnumCodec
    @ColumnType(javaType = String.class)
    @DefaultValue("disable")
    private EnumInfo<String> state;

    @Schema(description = "扩展配置")
    @JsonCodec
    @ColumnType(jdbcType = JDBCType.LONGVARCHAR)
    private Map<String, Object> options;

    @Column
    @Schema(description = "说明")
    private String description;

}
