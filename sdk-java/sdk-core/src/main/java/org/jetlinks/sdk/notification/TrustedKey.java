package org.jetlinks.sdk.notification;

/**
 * Locally trusted ownership and secret; never construct this object from the incoming event. @since
 * 2.0
 */
public final class TrustedKey {
    public static final String API = "api";
    public static final String BUSINESS = "business";
    public final String type, platformId, applicationId, templateId, secret, channelId;

    private TrustedKey(
            String type,
            String platform,
            String app,
            String template,
            String secret,
            String channel) {
        this.type = type;
        platformId = platform;
        applicationId = app;
        templateId = template;
        this.secret = secret;
        channelId = channel;
    }

    public static TrustedKey api(String app, String secret) {
        return api(app, secret, app);
    }

    /**
     * Local channel ID separates identical API app IDs from different configured platform
     * addresses. Not a signature header.
     */
    public static TrustedKey api(String app, String secret, String channel) {
        return new TrustedKey(API, "", app, null, secret, channel);
    }

    public static TrustedKey business(String platform, String template, String app, String secret) {
        return new TrustedKey(BUSINESS, platform, app, template, secret, "");
    }
}
