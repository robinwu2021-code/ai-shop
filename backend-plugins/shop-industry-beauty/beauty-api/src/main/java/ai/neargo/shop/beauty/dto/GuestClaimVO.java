package ai.neargo.shop.beauty.dto;

/** Scoped result; credentials and public arrival codes must not be logged. */
public record GuestClaimVO(String operationNo, String guestNo, String userNo, Long claimRevision,
        String operationState, String recoveryAction) {
    @Override public String toString() { return "GuestClaimVO[REDACTED]"; }
}
