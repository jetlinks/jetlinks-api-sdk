package org.jetlinks.sdk.example;

/** Shared UI operation request; body is the user's arbitrary platform request payload. @since 2.0 */
public class ExampleOperation {
    public String authMode = "oauth2", client = "", operation = "", entry = "";
    public String id = "", roleId = "", taskId = "";
    public String method = "GET", path = "/authorize/context";
    public Object body;
    public boolean stream;
    public int attempt = 1;
}
