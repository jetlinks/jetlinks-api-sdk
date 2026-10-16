package org.jetlinks.sdk.model;

import java.util.List;

/** Role menu/button grant projection. @since 2.0 */
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
