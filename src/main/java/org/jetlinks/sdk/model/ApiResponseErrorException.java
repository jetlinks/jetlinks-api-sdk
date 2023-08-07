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
    private final String resMessage;
    private final String timestamp;
    private final Object result;

    public ApiResponseErrorException(ApiResponse<Object> response) {
        super(fullMessage(response));
        this.status = response.getStatus();
        this.code = response.getCode();
        this.resMessage = response.getMessage();
        this.timestamp = response.getTimestamp();
        this.result = response.getResult();
    }

    public ApiResponseErrorException(ApiResponse<Object> response, String message) {
        super(fullMessage(response) + "," + message);
        this.status = response.getStatus();
        this.code = response.getCode();
        this.resMessage = response.getMessage();
        this.timestamp = response.getTimestamp();
        this.result = response.getResult();
    }


    public static String fullMessage(ApiResponse<Object> response) {
        return "API返回错误：code='"
                + response.getStatus() + '\'' +
                ", message='"
                + response.getMessage() + '\'';

    }


}
