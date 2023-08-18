package org.jetlinks.sdk.model.scene;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.ezorm.core.param.Term;
import org.hswebframework.ezorm.core.param.TermType;

import java.util.List;
import java.util.Map;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class Variable {
    public static final String OPTION_PRODUCT_ID = "productId";
    @Schema(description = "变量ID")
    private String id;

    @Schema(description = "变量名")
    private String name;

    @Schema(description = "变量全名")
    private String fullName;

    @Schema(description = "列")
    private String column;

    @Schema(description = "说明")
    private String description;

    @Schema(description = "类型")
    private String type;

    /**
     * @see Term#getTermType()
     */
    @Schema(description = "支持的条件类型")
    private List<TermType> termTypes;

    @Schema(description = "子级变量")
    private List<Variable> children;

    @Schema(description = "是否为物模型变量")
    private boolean metadata;

    @Schema(description = "其他配置")
    private Map<String, Object> options;
}
