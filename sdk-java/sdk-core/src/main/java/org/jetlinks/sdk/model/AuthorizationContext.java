package org.jetlinks.sdk.model;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 当前已认证主体、应用及生效的功能权限。
 *
 * @since 2.0
 */
public class AuthorizationContext {
    public Subject subject;
    public Application application;
    public List<Permission> permissions;
    public Map<String, Object> extensions;

    public static class Subject {
        public String id, name, type;
    }

    public static class Application {
        public String id, name, type, templateId;
    }

    public static class Permission {
        public String id;
        public Set<String> actions;
    }
}
