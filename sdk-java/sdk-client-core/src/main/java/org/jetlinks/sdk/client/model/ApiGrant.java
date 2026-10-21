package org.jetlinks.sdk.client.model;

import java.util.Set;

/**
 * API 分组授权事实，包含操作及资产范围。
 *
 * @since 2.0
 */
public class ApiGrant extends Extensible {
    public String id, targetType, targetId, targetKey, groupId, creatorId;
    public AssetAccesses assetAccesses;
    public Set<String> operationIds;
    public Boolean merge;
    public Integer priority;
    public Long createTime;
}
