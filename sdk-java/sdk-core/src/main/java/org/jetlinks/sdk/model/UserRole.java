package org.jetlinks.sdk.model;

/**
 * 当前应用范围内的成员与角色关系。
 *
 * @since 2.0
 */
public class UserRole extends Extensible {
    public String userId, roleId;
    public Long relationTime;
}
