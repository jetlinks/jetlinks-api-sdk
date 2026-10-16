package org.jetlinks.sdk.model;

/** Member-to-role association scoped to the requested application. @since 2.0 */
public class UserRole extends Extensible {
    public String userId, roleId;
    public Long relationTime;
}
