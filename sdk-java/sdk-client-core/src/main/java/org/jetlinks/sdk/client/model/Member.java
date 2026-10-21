package org.jetlinks.sdk.client.model;

import java.util.List;
import java.util.Map;

/**
 * 应用成员信息，保留服务端扩展字段。
 *
 * @since 2.0
 */
public class Member extends Extensible {
    public String id, username, name, email, telephone, avatar, description, typeId;
    public Byte status;
    public Long createTime, modifyTime, relationTime;
    public List<Role> roleList;
    public List<Map<String, Object>> orgList, positions, businessApplications;
}
