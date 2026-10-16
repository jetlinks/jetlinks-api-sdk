package org.jetlinks.sdk.model;

import java.util.List;
import java.util.Set;

/** Role permission facts. Data-access configuration is interpreted by its provider. @since 2.0 */
public class RoleAuthorization extends Extensible {
    public String id, permission, dimensionType, dimensionTypeName, dimensionTarget, dimensionTargetName;
    public Byte state;
    public Set<String> actions;
    public List<DataAccess> dataAccesses;
    public Integer priority;
    public Boolean merge;
}
