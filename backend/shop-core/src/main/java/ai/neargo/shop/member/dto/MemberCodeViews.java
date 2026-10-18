package ai.neargo.shop.member.dto;

import java.time.Instant;

/** Public responses deliberately exclude user numbers, phones and any financial facts. */
public final class MemberCodeViews {
    private MemberCodeViews() { }

    public record MemberCodeView(String memberCode, Instant expiresAt) {
        @Override
        public String toString() {
            return "MemberCodeView[memberCode=REDACTED, expiresAt=" + expiresAt + "]";
        }
    }

    public record CustomerReference(String customerRef, String maskedName, Instant expiresAt) {
        @Override
        public String toString() {
            return "CustomerReference[customerRef=REDACTED, expiresAt=" + expiresAt + "]";
        }
    }
}
