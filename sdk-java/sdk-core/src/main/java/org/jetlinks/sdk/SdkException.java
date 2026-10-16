package org.jetlinks.sdk;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * HTTP or platform failure. Response data is available explicitly; never included in the default
 * message. @since 2.0
 */
public final class SdkException extends RuntimeException {
    public final int status;
    public final JsonNode response;

    public SdkException(int status, JsonNode response) {
        super("JetLinks request failed (status " + status + ")");
        this.status = status;
        this.response = response;
    }
}
