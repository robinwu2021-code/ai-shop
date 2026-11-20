package ai.neargo.shop.paybridge;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import lombok.Getter;
import lombok.Setter;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Getter
@Setter
@Validated
@ConfigurationProperties("shop.payment-collections")
public class PaymentCollectionProperties {
    private boolean enabled;

    /** Legacy single environment; {@link #secret} is its key. Superseded by {@link #environments}. */
    @Pattern(regexp = "SANDBOX|PRODUCTION")
    private String environment = "SANDBOX";

    /** Legacy key of {@link #environment}. */
    @Size(min = 32)
    private String secret;

    /** Payment environments this deployment accepts; empty means just {@link #environment}. */
    private Set<String> environments = new LinkedHashSet<>();

    /** Per-environment signing keys, so a SANDBOX key can never sign a PRODUCTION result. */
    private Map<String, String> secrets = new LinkedHashMap<>();

    @NotBlank
    @Size(min = 32)
    private String cursorSecret;

    /** Allowlisted environment:tenantNo:payAppId combinations for canonical checkout IDs. */
    private Set<@Pattern(regexp = "(SANDBOX|PRODUCTION):[A-Za-z0-9_-]{1,64}:[1-9][0-9]{0,18}") String>
            canonicalCheckoutSources = new LinkedHashSet<>();

    @Min(100)
    private long pollMs = 1000;

    @Min(1000)
    private long heartbeatMs = 15000;

    @Min(1000)
    private long retentionMs = 604800000;

    @Min(2)
    @Max(1024)
    private int sendQueueCapacity = 64;

    @Min(100)
    private long writeTimeoutMs = 5000;

    public Set<String> acceptedEnvironments() {
        return environments == null || environments.isEmpty() ? Set.of(environment) : Set.copyOf(environments);
    }

    /** The signing key of one accepted environment, or null when the environment is not accepted. */
    public String secretFor(String env) {
        if (env == null || !acceptedEnvironments().contains(env)) return null;
        String key = secrets == null ? null : secrets.get(env);
        return key != null ? key : env.equals(environment) ? secret : null;
    }

    @AssertTrue(message = "every accepted payment environment needs a signing key of at least 32 characters")
    public boolean isKeyedPerEnvironment() {
        if (!enabled) return true;
        for (String env : acceptedEnvironments()) {
            String key = secretFor(env);
            if (!"SANDBOX".equals(env) && !"PRODUCTION".equals(env) || key == null || key.length() < 32) return false;
        }
        return true;
    }
}
