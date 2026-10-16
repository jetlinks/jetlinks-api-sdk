package org.jetlinks.sdk.event;

import org.jetlinks.sdk.model.Extensible;

/** Public application snapshot supplied by lifecycle events; never selects routing identity. @since 2.0 */
public class ApplicationInfo extends Extensible {
    public static final String ENABLED = "enabled";
    public static final String DISABLED = "disabled";

    public String id, templateId, name, icon, description, state, apiBaseUrl, creatorId, modifierId;
    public Long createTime, modifyTime;
}
