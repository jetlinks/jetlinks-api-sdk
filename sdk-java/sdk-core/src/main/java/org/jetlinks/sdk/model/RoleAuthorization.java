package org.jetlinks.sdk.model;

import java.util.List;
import java.util.Set;

/**
 * 角色权限事实；数据访问配置由对应提供方解释。
 *
 * @since 2.0
 */
public class RoleAuthorization extends Extensible {
    public String id,
            permission,
            dimensionType,
            dimensionTypeName,
            dimensionTarget,
            dimensionTargetName;
    public Byte state;
    public Set<String> actions;
    public List<DataAccess> dataAccesses;
    public Integer priority;
    public Boolean merge;
}
