package org.jetlinks.sdk.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ApiResponse<T> {
    private int status;
    private String code;
    private String message;
    private String timestamp;
    private T result;

    public boolean isSuccess() {
        return status == 200;
    }
}
