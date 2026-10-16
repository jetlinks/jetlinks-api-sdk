package org.jetlinks.sdk.subscription;

import java.util.ArrayList;
import java.util.List;

/** Candidate configuration validation returned by the platform. @since 2.0 */
public class SubscriptionValidation {
    public boolean valid;
    public List<Issue> issues = new ArrayList<>();

    public static class Issue {
        public String field, code, message;
    }
}
