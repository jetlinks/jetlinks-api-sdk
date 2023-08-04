package org.jetlinks.sdk.model;

import lombok.Getter;

/**
 * @author gyl
 * @since 2.1
 */
@Getter
public class ApiResponseErrorException extends RuntimeException {

    private final int status;
    private final String code;
    private final String message;
    private final String timestamp;
    private final Object result;

    public ApiResponseErrorException(ApiResponse<Object> response) {
        super(response.getMessage());
        this.status = response.getStatus();
        this.code = response.getCode();
        this.message = response.getMessage();
        this.timestamp = response.getTimestamp();
        this.result = response.getResult();
    }

    public ApiResponseErrorException(ApiResponse<Object> response, String message) {
        super(message);
        this.status = response.getStatus();
        this.code = response.getCode();
        this.message = response.getMessage();
        this.timestamp = response.getTimestamp();
        this.result = response.getResult();
    }

    public ApiResponseErrorException(ApiResponse<Object> response, String message, Throwable cause) {
        super(message, cause);
        this.status = response.getStatus();
        this.code = response.getCode();
        this.message = response.getMessage();
        this.timestamp = response.getTimestamp();
        this.result = response.getResult();
    }


}
