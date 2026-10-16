package org.jetlinks.sdk.model;

import java.util.List;

/**
 * 平台返回的角色事实；权限仍由平台判断。
 *
 * @since 2.0
 */
public class Role extends Extensible {
    public String id, name, description, applicationId, groupId, creatorId, modifierId;
    public EnumValue state;
    public Long createTime, modifyTime;
    public List<AssetAccesses> dataAccess;
}
