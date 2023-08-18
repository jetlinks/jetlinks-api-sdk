package org.jetlinks.sdk.model.scene;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;
import java.util.List;

/**
 * @author zyl
 * @date 3/8/2023
 */
@Getter
@Setter
public class DeviceSelectorSpec extends VariableSource{
    @Schema(description = "选择器标识")
    @NotBlank
    private String selector;

    @Schema(description = "选择器的值,如选择的部门ID,标签信息等")
    private List<SelectorValue> selectorValues;

    @Schema(description = "固定设备ID")
    @Override
    public Object getValue() {
        return super.getValue();
    }

    public DeviceSelectorSpec() {
    }

    private DeviceSelectorSpec(String selector, List<SelectorValue> selectorValue) {
        this.selector = selector;
        super.setValue(selectorValue);
    }
}
