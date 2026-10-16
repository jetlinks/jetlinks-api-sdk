package org.jetlinks.sdk.model;

import java.util.List;
import java.util.Map;

/** Asset permission facts; provider-specific access/options remain open maps. @since 2.0 */
public class AssetAccesses extends Extensible {
    public String assetType;
    public List<Map<String, Object>> accesses;
    public Map<String, Object> options;
}
