package ai.neargo.commerce.api.common;

/** Trusted identity provenance only. Never contains tokens, credentials, or client assertions. */
public record RawPrincipalIdentity(String realm, String kind, String rawNo) {}
