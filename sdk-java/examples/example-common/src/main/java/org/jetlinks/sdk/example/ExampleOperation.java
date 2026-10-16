package org.jetlinks.sdk.example;

/**
 * 共用前端的操作请求；body 是用户自定义的平台请求载荷。
 *
 * @since 2.0
 */
public class ExampleOperation {
    public String authMode = "oauth2", client = "", operation = "", entry = "";
    public String id = "", roleId = "", taskId = "";
    public String method = "GET", path = "/authorize/context";
    public Object body;
    public boolean stream;
    public int attempt = 1;
}
