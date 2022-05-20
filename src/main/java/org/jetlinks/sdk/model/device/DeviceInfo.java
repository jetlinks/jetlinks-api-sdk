package org.jetlinks.sdk.model.device;

import lombok.Getter;
import lombok.Setter;
import org.jetlinks.sdk.model.EnumInfo;

import java.util.Map;

@Getter
@Setter
public class DeviceInfo {

    //ID
    private String id;

    //设备名称
    private String name;

    //产品ID
    private String productId;

    //产品名称
    private String productName;

    //配置信息
    private Map<String, Object> configuration;

    //状态信息
    private EnumInfo<String> state;

    //创建时间
    private long createTime;

    //注册时间
    private long registryTime;

    //修改时间
    private long modifyTime;

    //上级网关设备ID
    private String parentId;


}
