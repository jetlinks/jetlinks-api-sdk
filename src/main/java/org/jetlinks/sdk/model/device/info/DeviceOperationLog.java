package org.jetlinks.sdk.model.device.info;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.sdk.model.EnumInfo;

@Setter
@Getter
public class DeviceOperationLog {
    @Schema(description = "日志ID")
    private String id;

    @Schema(description = "设备ID")
    private String deviceId;

    @Schema(description = "产品ID")
    private String productId;

    @Schema(description = "日志类型")
    private EnumInfo<String> type;

    @Schema(description = "创建时间")
    private long createTime;

    @Schema(description = "日志内容")
    private Object content;

    @Schema(description = "消息ID")
    private String messageId;

    @Hidden
    private String orgId;

    @Schema(description = "数据时间")
    private long timestamp;
}
