package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/** Production payment-result signing rules (TDD-沙箱收款消息与SSE, 2026-11-16 increment, AC1/AC2). */
class PaymentCollectionEnvironmentTest {
    private static final String SANDBOX_KEY = "sandbox-signing-key-at-least-32-characters";
    private static final String PRODUCTION_KEY = "production-signing-key-at-least-32-chars";

    private static PaymentCollectionProperties props(Set<String> environments, Map<String, String> secrets) {
        var p = new PaymentCollectionProperties();
        p.setEnabled(true);
        p.setSecret(SANDBOX_KEY);
        p.setCursorSecret("cursor-secret-at-least-32-characters-long");
        p.setEnvironments(new java.util.LinkedHashSet<>(environments));
        p.setSecrets(new java.util.LinkedHashMap<>(secrets));
        return p;
    }

    private static void verify(PaymentCollectionSecurity security, String env, String signWith) {
        var signer = new PaymentCollectionSecurity(props(Set.of(env), Map.of(env, signWith)), Clock.systemUTC());
        String ts = Long.toString(Clock.systemUTC().instant().getEpochSecond());
        String nonce = UUID.randomUUID().toString();
        String raw = "{\"x\":1}";
        security.verify(ts, nonce, env, signer.signature(ts, nonce, env, raw), raw);
    }

    @Test
    @DisplayName("AC1: legacy configuration accepts only SANDBOX; PRODUCTION is accepted once configured")
    void productionNeedsExplicitConfiguration() {
        var legacy = new PaymentCollectionSecurity(props(Set.of(), Map.of()), Clock.systemUTC());
        verify(legacy, "SANDBOX", SANDBOX_KEY);
        assertThatThrownBy(() -> verify(legacy, "PRODUCTION", PRODUCTION_KEY))
                .isInstanceOf(ResponseStatusException.class);

        var both = new PaymentCollectionSecurity(
                props(Set.of("SANDBOX", "PRODUCTION"), Map.of("PRODUCTION", PRODUCTION_KEY)), Clock.systemUTC());
        verify(both, "SANDBOX", SANDBOX_KEY);
        verify(both, "PRODUCTION", PRODUCTION_KEY);
    }

    @Test
    @DisplayName("AC2: a SANDBOX key cannot sign a PRODUCTION result")
    void sandboxKeyCannotSignProduction() {
        var both = new PaymentCollectionSecurity(
                props(Set.of("SANDBOX", "PRODUCTION"), Map.of("PRODUCTION", PRODUCTION_KEY)), Clock.systemUTC());
        assertThatThrownBy(() -> verify(both, "PRODUCTION", SANDBOX_KEY)).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("Every accepted environment needs its own key; unknown environments are invalid")
    void keysAreRequiredPerEnvironment() {
        assertThat(props(Set.of("SANDBOX", "PRODUCTION"), Map.of()).isKeyedPerEnvironment()).isFalse();
        assertThat(props(Set.of("SANDBOX", "PRODUCTION"), Map.of("PRODUCTION", PRODUCTION_KEY)).isKeyedPerEnvironment())
                .isTrue();
        assertThat(props(Set.of("STAGING"), Map.of("STAGING", PRODUCTION_KEY)).isKeyedPerEnvironment()).isFalse();
        assertThat(props(Set.of("PRODUCTION"), Map.of("PRODUCTION", "short")).isKeyedPerEnvironment()).isFalse();
    }
}
