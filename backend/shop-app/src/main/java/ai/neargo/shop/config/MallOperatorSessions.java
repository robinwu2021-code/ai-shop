package ai.neargo.shop.config;

import ai.neargo.shop.auth.AccountSessionVerifier;

/** Separate holder prevents replacement or ambiguity of the legacy merchant verifier bean. */
public record MallOperatorSessions(AccountSessionVerifier verifier) {}
