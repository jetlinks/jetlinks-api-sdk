package org.jetlinks.sdk.model.scene;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.core.param.Term;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/**
 * @author zyl
 * @date 4/8/2023
 */
@Getter
@Setter
public class SceneRule implements Serializable {
    public static final String ACTION_KEY_BRANCH_INDEX = "_branchIndex";
    public static final String ACTION_KEY_GROUP_INDEX = "_groupIndex";
    public static final String ACTION_KEY_ACTION_INDEX = "_actionIndex";

    public static final String CONTEXT_KEY_SCENE_OUTPUT = "scene";

    public static final String SOURCE_TYPE_KEY = "sourceType";
    public static final String SOURCE_ID_KEY = "sourceId";
    public static final String SOURCE_NAME_KEY = "sourceName";


    @Schema(description = "告警ID")
    @NotBlank(message = "error.scene_rule_id_cannot_be_blank")
    private String id;

    @Schema(description = "告警名称")
    @NotBlank(message = "error.scene_rule_name_cannot_be_blank")
    private String name;

    @Schema(description = "触发器")
    @NotNull(message = "error.scene_rule_trigger_cannot_be_null")
    private Trigger trigger;


    @Schema(description = "触发条件")
    private List<Term> terms;

    @Schema(description = "是否并行执行动作")
    private boolean parallel;

    @Schema(description = "执行动作")
    private List<SceneAction> actions;

    @Schema(description = "动作分支")
    private List<SceneConditionAction> branches;

    @Schema(description = "扩展配置")
    private Map<String, Object> options;

    @Schema(description = "说明")
    private String description;
}
