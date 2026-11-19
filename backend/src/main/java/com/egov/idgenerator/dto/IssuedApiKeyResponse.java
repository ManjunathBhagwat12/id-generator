package com.egov.idgenerator.dto;

/**
 * Returned ONLY by create and rotate. {@code apiKey} is the full plaintext key; it is not stored and can never be
 * retrieved again.
 */
public record IssuedApiKeyResponse(ApiCredentialResponse credential, String apiKey, String warning) {

    public static final String WARNING =
            "Store this API key securely now. It is shown only once and cannot be retrieved again.";

    @Override
    public String toString() { // never log the key by accident
        return "IssuedApiKeyResponse[" + credential.keyPrefix() + "...]";
    }
}
