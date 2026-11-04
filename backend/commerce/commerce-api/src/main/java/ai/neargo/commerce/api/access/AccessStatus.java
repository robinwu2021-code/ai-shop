package ai.neargo.commerce.api.access;

import ai.neargo.commerce.api.common.*;

public enum AccessStatus {
    ALLOWED,
    NOT_PURCHASED,
    EXPIRED,
    NOT_STARTED,
    REVOKED,
    CHECK_UNAVAILABLE,
    FORBIDDEN,
    STORE_DISABLED,
    CONTEXT_REFRESH_REQUIRED,
    ACCOUNT_INVALID
}
