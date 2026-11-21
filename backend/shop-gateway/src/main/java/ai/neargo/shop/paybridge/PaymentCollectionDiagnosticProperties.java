package ai.neargo.shop.paybridge;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Deployment-owned, temporary SANDBOX transport diagnostics; never payment facts. */
@Validated
@ConfigurationProperties("shop.payment-collections.diagnostic")
public record PaymentCollectionDiagnosticProperties(
        String userNo, String entityNo, String storeNo, Long intervalMs, Long durationMs) {
    public long interval() { return intervalMs == null ? 10000 : intervalMs; }
    public long duration() { return durationMs == null ? 1800000 : durationMs; }

    @AssertTrue(message = "SSE diagnostics require an exact user/entity/store and bounded timing")
    public boolean isValidConfiguration() {
        return identifier(userNo) && identifier(entityNo) && identifier(storeNo)
                && interval() >= 5000 && interval() <= 60000
                && duration() >= 30000 && duration() <= 3600000 && duration() >= interval();
    }
    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}");
    }
}
