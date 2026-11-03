package ai.neargo.commerce.api.access;

import ai.neargo.commerce.api.common.*;

import java.time.Instant;
import java.util.Optional;

public record AccessDecision(
        AccessStatus status,
        StoreRef store,
        String entitlementCode,
        long entitlementVersion,
        Instant serverNow,
        Optional<Instant> nextBoundaryAt,
        Optional<String> denialCode,
        String nextAction) {
    public AccessDecision(
            AccessStatus status,
            StoreRef store,
            String entitlementCode,
            long entitlementVersion,
            Instant serverNow,
            Optional<Instant> nextBoundaryAt,
            Optional<String> denialCode) {
        this(
                status,
                store,
                entitlementCode,
                entitlementVersion,
                serverNow,
                nextBoundaryAt,
                denialCode,
                nextActionFor(status));
    }

    public static String nextActionFor(AccessStatus status) {
        return switch (status) {
            case ALLOWED -> "ENTER_END";
            case CONTEXT_REFRESH_REQUIRED -> "REFRESH_CONTEXT";
            case EXPIRED, NOT_PURCHASED -> "VIEW_SUBSCRIPTION";
            case NOT_STARTED -> "WAIT_FOR_START";
            case CHECK_UNAVAILABLE -> "RETRY_CHECK";
            case ACCOUNT_INVALID -> "LOGIN";
            case FORBIDDEN, STORE_DISABLED, REVOKED -> "CONTACT_ADMIN";
        };
    }
}
