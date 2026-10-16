package org.jetlinks.sdk.event;

import org.jetlinks.sdk.model.Extensible;

/**
 * 生命周期携带的应用快照，供更新基本信息使用；不能据此选择接收应用。
 *
 * @since 2.0
 */
public class ApplicationInfo extends Extensible {
    public static final String ENABLED = "enabled";
    public static final String DISABLED = "disabled";

    public String id, templateId, name, icon, description, state, apiBaseUrl, creatorId, modifierId;
    public Long createTime, modifyTime;
}
