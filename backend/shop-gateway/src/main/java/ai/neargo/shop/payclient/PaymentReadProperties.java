package ai.neargo.shop.payclient;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.AssertTrue;

/** Optional protected credentials for an explicitly selected SANDBOX read view only. */
@Validated
@ConfigurationProperties("shop.payment-collections")
public record PaymentReadProperties(boolean enabled, String environment, boolean sandboxReadEnabled,
        String dedicatedSandboxCaller, String dedicatedSandboxCredential) {
    boolean sandboxConfigured() {
        return sandboxReadEnabled && enabled && "SANDBOX".equals(environment)
                && dedicatedSandboxCaller != null
                && dedicatedSandboxCaller.matches("[A-Z][A-Z0-9_]{1,63}")
                && dedicatedSandboxCredential != null && !dedicatedSandboxCredential.isBlank();
    }

    @AssertTrue(message = "Enabled sandbox reads require SANDBOX collections and dedicated caller credentials")
    public boolean isDedicatedReadConfigurationComplete() {
        return !sandboxReadEnabled || sandboxConfigured();
    }

    @Override
    public String toString() {
        return "PaymentReadProperties[enabled=" + enabled + ",environment=" + environment
                + ",credential=REDACTED]";
    }
}
