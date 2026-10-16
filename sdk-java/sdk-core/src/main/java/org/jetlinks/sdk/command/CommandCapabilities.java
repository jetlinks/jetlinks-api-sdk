package org.jetlinks.sdk.command;

import java.util.ArrayList;
import java.util.List;

/** One supported command protocol version and its actual registered command IDs. @since 2.0 */
public class CommandCapabilities {
    public static final String VERSION_1 = "1";
    public String version = VERSION_1;
    public List<String> capabilities = new ArrayList<>();

    public CommandCapabilities() {}

    public CommandCapabilities(List<String> capabilities) {
        this.capabilities = capabilities;
    }
}
