package org.jetlinks.sdk.model.scene;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.core.param.TermType;

import java.util.List;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class TermColumn {
    @Schema(description = "条件列")
    private String column;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "全名")
    private String fullName;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "数据类型")
    private String dataType;

    @Schema(description = "是否为物模型列")
    private boolean metadata;

    /**
     * @see Term#getTermType()
     */
    @Schema(description = "支持的条件类型")
    private List<TermType> termTypes;

    @Schema(description = "支持的指标")
    private List<PropertyMetric> metrics;

    @Schema(description = "可选内容")
    private List<PropertyMetric> options;

    @Schema(description = "子列,在类型为object时有值")
    private List<TermColumn> children;
}
