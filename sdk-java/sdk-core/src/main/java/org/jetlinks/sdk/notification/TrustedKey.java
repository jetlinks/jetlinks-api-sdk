package org.jetlinks.sdk.notification;

/**
 * 本地信任的密钥及其归属，不得根据收到的事件内容构造信任关系。
 *
 * @since 2.0
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

    /** 本地通道 ID 用于隔离不同平台地址上同名的 API 应用，不属于签名 Header。 */
    public static TrustedKey api(String app, String secret, String channel) {
        return new TrustedKey(API, "", app, null, secret, channel);
    }

    public static TrustedKey business(String platform, String template, String app, String secret) {
        return new TrustedKey(BUSINESS, platform, app, template, secret, "");
    }
}
