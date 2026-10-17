package org.jetlinks.sdk.client.model;

import java.util.List;

/**
 * 角色的菜单及按钮授权信息。
 *
 * @since 2.0
 */
public class RoleMenu extends Extensible {
    public String menuId;
    public List<Button> buttons;
    public Boolean merge;
    public Integer priority;

    public static class Button {
        public String id;
        public boolean granted;
    }
}
