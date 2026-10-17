package org.jetlinks.sdk.client.subscription;

import java.util.ArrayList;
import java.util.List;

/**
 * 平台返回的候选订阅配置校验结果。
 *
 * @since 2.0
 */
public class SubscriptionValidation {
    public boolean valid;
    public List<Issue> issues = new ArrayList<>();

    public static class Issue {
        public String field, code, message;
    }
}
