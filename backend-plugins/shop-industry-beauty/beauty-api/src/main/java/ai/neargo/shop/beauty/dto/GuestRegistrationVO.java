package ai.neargo.shop.beauty.dto;

/** Scoped result; credentials and public arrival codes must not be logged. */
public record GuestRegistrationVO(String operationNo, String guestNo, String claimToken, String state,
        Long revision, String operationState, String recoveryAction) {
    @Override public String toString() { return "GuestRegistrationVO[REDACTED]"; }
}
