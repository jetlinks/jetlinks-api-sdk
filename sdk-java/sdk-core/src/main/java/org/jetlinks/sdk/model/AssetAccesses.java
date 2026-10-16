package org.jetlinks.sdk.model;

import java.util.List;
import java.util.Map;

/**
 * 资产权限事实；提供方特有的 access/options 使用开放 Map 表达。
 *
 * @since 2.0
 */
public class AssetAccesses extends Extensible {
    public String assetType;
    public List<Map<String, Object>> accesses;
    public Map<String, Object> options;
}
