package ai.neargo.shop.pay.entitlement.dto;

import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.time.Instant;

/** Optional legacy exchange locator, never a member identity or stored-value account credential. */
public final class CodeViews {
    private CodeViews() { }
    public record CodeRequest(String useContext) {
        public CodeRequest { ContractChecks.require("STORE_EXCHANGE".equals(useContext)); }
    }
    public record CodeView(String token, Instant expiresAt, String instanceNo) {
        public CodeView {
            ContractChecks.require(token != null && token.matches("ec1_[A-Za-z0-9_-]{43}"));
            ContractChecks.present(expiresAt); ContractChecks.id(instanceNo);
        }
        @Override public String toString() {
            return "CodeView[token=REDACTED, expiresAt=" + expiresAt + ", instanceNo=" + instanceNo + "]";
        }
    }
    /** Internal response only; public preview must continue through source and consumer authorization. */
    public record ResolvedCode(String userNo, String instanceNo, Instant expiresAt) { }
}
