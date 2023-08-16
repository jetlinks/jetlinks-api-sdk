package org.jetlinks.sdk.model.device.info;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;
import org.hswebframework.web.api.crud.entity.GenericEntity;
import org.hswebframework.web.api.crud.entity.RecordCreationEntity;
import org.hswebframework.web.api.crud.entity.RecordModifierEntity;
import org.jetlinks.sdk.model.EnumInfo;

import java.util.Map;

@Getter
@Setter


public class DeviceProduct extends GenericEntity<String> implements RecordCreationEntity, RecordModifierEntity {


    @Schema(description = "ID")
    private String id;

    @Schema(description = "产品名称")
    private String name;

    @Schema(description="所属项目")
    private String projectId;


    @Schema(description = "图片地址")
    private String photoUrl;

    @Schema(description="项目名称")
    private String projectName;


    @Schema(description = "说明")
    private String describe;

    @Schema(description = "所属品类ID")
    private String classifiedId;


    @Schema(description = "所属品类名称")
    private String classifiedName;


    @Schema(description = "消息协议ID")
    private String messageProtocol;


    @Schema(description = "消息协议名称")
    private String protocolName;


    @Schema(description = "物模型定义")
    private String metadata;


    @Schema(description = "传输协议")
    private String transportProtocol;


    @Schema(description = "入网方式")
    private String networkWay;


    @Schema(description = "设备类型")
    private EnumInfo<String> deviceType;


    @Schema(description = "协议相关配置")
    private Map<String, Object> configuration;


    @Schema(description = "产品状态 1正常,0禁用")
    private Byte state;


    @Schema(description = "创建者ID(只读)")
    private String creatorId;


    @Schema(description = "创建者时间(只读)")
    private Long createTime;

    @Schema(description = "机构ID")
    private String orgId;


    @Schema(description = "设备接入方式ID")
    private String accessId;


    @Schema(description = "设备接入方式")
    private String accessProvider;


    @Schema(description = "设备接入方式名称")
    private String accessName;


    @Schema(description = "数据存储策略")
    private String storePolicy;


    @Schema(description = "数据存储策略相关配置")
    private Map<String, Object> storePolicyConfiguration;


    @Schema(description = "修改人")
    private String modifierId;


    @Schema(description = "修改时间")
    private Long modifyTime;


}
