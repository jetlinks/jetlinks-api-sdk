package org.jetlinks.sdk.model.device.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.jetlinks.sdk.model.EnumInfo;
import org.jetlinks.sdk.model.device.DeviceInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Getter
@Setter
public class DeviceDetail extends DeviceInfo {

    @Schema(description = "产品图片地址")
    private String productPhotoUrl;

    @Schema(description = "设备图片地址")
    private String devicePhotoUrl;

    @Schema(description = "消息协议ID")
    private String protocol;

    @Schema(description = "消息协议名称")
    private String protocolName;

    @Schema(description = "通信协议")
    private String transport;

    @Schema(description = "设备类型")
    private EnumInfo<String> deviceType;

    @Schema(description = "ip地址")
    private String address;

    @Schema(description = "上线时间")
    private long onlineTime;

    @Schema(description = "离线时间")
    private long offlineTime;

    @Schema(description = "物模型")
    private String metadata;

    @Schema(description = "产品物模型")
    private String productMetadata;

    @Schema(description = "是否为独立物模型")
    private boolean independentMetadata;

    @Schema(description = "设备自己的配置信息")
    private Map<String, Object> deviceConfiguration = new HashMap<>();

    @Schema(description = "已生效的配置信息")
    private Map<String, Object> cachedConfiguration = new HashMap<>();

    @Schema(description = "是否为单独的配置,false表示部分配置信息继承自产品.")
    private boolean aloneConfiguration;

    @Schema(description = "当前连接到的服务ID")
    private String connectServerId;

    @Schema(description = "标签信息")
    private List<Map<String, Object>> tags = new ArrayList<>();

    @Schema(description = "绑定信息,通常是和第三方云平台进行了关联.")
    private List<Map<String, Object>> binds = new ArrayList<>();

    @Schema(description = "固件信息")
    private Map<String, Object> firmwareInfo;

    @Schema(description = "设备描述")
    private String description;

    @Schema(description = "设备特性")
    private List<Map<String, Object>> features = new ArrayList<>();

    @Schema(description = "关系信息")
    private List<Map<String, Object>> relations;

    @Schema(description = "设备接入方式ID")
    private String accessId;

    @Schema(description = "设备接入方式")
    private String accessProvider;

    @Schema(description = "设备接入方式名称")
    private String accessName;

    @Schema(description = "产品所属品类ID")
    private String classifiedId;

    @Schema(description = "产品所属品类名称")
    private String classifiedName;

}
