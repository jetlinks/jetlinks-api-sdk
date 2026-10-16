package org.jetlinks.sdk.model;

import java.util.Map;

/**
 * 权限数据访问规则；config 由对应 type 的提供方解释。
 *
 * @since 2.0
 */
public class DataAccess extends Extensible {
    public String action, type, describe;
    public Map<String, Object> config;
}
