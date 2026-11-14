package ai.neargo.commerce.api.common;


public record CommerceError(String code, String message, boolean retryable) {}
