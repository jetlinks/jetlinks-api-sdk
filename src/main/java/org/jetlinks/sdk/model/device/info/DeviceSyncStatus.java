package org.jetlinks.sdk.model.device.info;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.sdk.model.device.DeviceInfo;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class DeviceSyncStatus extends DeviceInfo {

    @Schema(description = "数据内容")
    private List<Map<String, Object>> result;
}


