package org.jetlinks.sdk.model;

import java.util.List;

/** Role facts as returned by the platform; authorization stays on the platform. @since 2.0 */
public class Role extends Extensible {
    public String id, name, description, applicationId, groupId, creatorId, modifierId;
    public EnumValue state;
    public Long createTime, modifyTime;
    public List<AssetAccesses> dataAccess;
}
