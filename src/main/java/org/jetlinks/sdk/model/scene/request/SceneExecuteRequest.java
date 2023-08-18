package org.jetlinks.sdk.model.scene.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * @author zyl
 * @date 8/8/2023
 */
@Getter
@Setter
public class SceneExecuteRequest {
    @Schema(description = "场景ID")
    private String id;

    @Schema(description = "数据")
    private Map<String, Object> context;

}
