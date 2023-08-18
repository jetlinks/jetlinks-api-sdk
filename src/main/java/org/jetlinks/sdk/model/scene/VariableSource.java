package org.jetlinks.sdk.model.scene;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.Map;
import java.util.Optional;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class VariableSource implements Serializable {
    private static final long serialVersionUID = 1L;

    @Schema(description = "来源")
    private Source source;

    @Schema(description = "固定值,[source]为[fixed]时不能为空")
    private Object value;

    @Schema(description = "上游key,[source]为[upper]时不能为空")
    private String upperKey;

    @Schema(description = "关系,[source]为[relation]时不能为空")
    private VariableObjectSpec relation;

    @Schema(description = "拓展信息")
    private Map<String, Object> options;

    public enum Source {
        //固定值
        fixed,
        //来自上游
        upper,
        //通过关系选择
        relation;

        public static Optional<Source> of(String source) {
            for (Source value : values()) {
                if (value.name().equals(source)) {
                    return Optional.of(value);
                }
            }
            return Optional.empty();
        }
    }
}
