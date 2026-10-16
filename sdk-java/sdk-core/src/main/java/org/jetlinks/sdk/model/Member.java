package org.jetlinks.sdk.model;

import java.util.List;
import java.util.Map;

/** Application member profile projection. Extension fields are retained. @since 2.0 */
public class Member extends Extensible {
    public String id, username, name, email, telephone, avatar, description, typeId;
    public Byte status;
    public Long createTime, modifyTime, relationTime;
    public List<Role> roleList;
    public List<Map<String, Object>> orgList, positions, businessApplications;
}
