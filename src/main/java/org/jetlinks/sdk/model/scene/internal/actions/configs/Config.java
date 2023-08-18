package org.jetlinks.sdk.model.scene.internal.actions.configs;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.sdk.model.scene.enums.AlarmMode;

import javax.validation.constraints.NotNull;
import java.io.Serializable;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class Config implements Serializable {
    @NotNull
    @Schema(description = "告警方式")
    private AlarmMode mode;
}
