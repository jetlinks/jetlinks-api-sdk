package org.jetlinks.sdk.model.scene;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

import javax.validation.constraints.NotBlank;
import java.util.Map;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PropertyMetric {
    @Schema(description = "指标ID")
    @NotBlank
    private String id;

    @Schema(description = "名称")
    @NotBlank
    private String name;

    @Schema(description = "值,范围值使用逗号分隔")
    private Object value;

    @Schema(description = "是否为范围值")
    private boolean range;

    @Schema(description = "其他拓展配置")
    private Map<String, Object> expands;
}
