package org.jetlinks.sdk.model;

import java.util.Set;

/** API group grant facts, including operation and asset scope. @since 2.0 */
public class ApiGrant extends Extensible {
    public String id, targetType, targetId, targetKey, groupId, creatorId;
    public AssetAccesses assetAccesses;
    public Set<String> operationIds;
    public Boolean merge;
    public Integer priority;
    public Long createTime;
}
