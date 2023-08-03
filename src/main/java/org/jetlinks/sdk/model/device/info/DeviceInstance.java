package org.jetlinks.sdk.model.device.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.core.device.DeviceState;
import org.jetlinks.sdk.model.device.enums.DeviceFeature;
import org.jetlinks.sdk.model.device.enums.DeviceType;

import java.util.Map;

@Getter
@Setter
public class DeviceInstance {

    @Schema(description = "设备ID(只能由数字,字母,下划线和中划线组成)")
    private String id;

    @Schema(description = "图片地址")
    private String photoUrl;

    @Schema(description = "设备名称")
    private String name;

    @Schema(description = "设备类型")
    private Map<String, Object> deviceType;

    @Schema(description = "说明")
    private String describe;

    @Schema(description = "产品ID")
    private String productId;

    @Schema(description = "产品名称")
    private String productName;

    @Schema(description = "配置信息")
    private Map<String, Object> configuration;

    @Schema(description = "派生(独立)物模型")
    private String deriveMetadata;

    @Schema(
            description = "状态(只读)"
            , accessMode = Schema.AccessMode.READ_ONLY
            , defaultValue = "notActive"
    )
    private DeviceType state;

    @Schema(
            description = "创建者ID(只读)"
            , accessMode = Schema.AccessMode.READ_ONLY
    )
    private String creatorId;

    @Schema(
            description = "创建者名称(只读)"
            , accessMode = Schema.AccessMode.READ_ONLY
    )
    private String creatorName;

    @Schema(
            description = "创建时间(只读)"
            , accessMode = Schema.AccessMode.READ_ONLY
    )
    private Long createTime;
    @Schema(description = "激活时间"
            , accessMode = Schema.AccessMode.READ_ONLY
    )
    private Long registryTime;

    @Schema(description = "父级设备ID")
    private String parentId;

    @Schema(description = "设备特性")
    private DeviceFeature[] features;

    @Schema(
            description = "修改时间"
            , accessMode = Schema.AccessMode.READ_ONLY
    )
    private Long modifyTime;

    @Schema(
            description = "修改人ID"
            , accessMode = Schema.AccessMode.READ_ONLY
    )
    private String modifierId;

    @Schema(
            description = "修改人名称"
            , accessMode = Schema.AccessMode.READ_ONLY
    )
    private String modifierName;
}
