package ai.neargo.shop.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.spi.resource.ResourceCommandFactsPort;
import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.json.JsonMapper;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import org.junit.jupiter.api.Test;

/** Evidence shape and byte binding are contracts, not authentication or live resource permissions. */
class ResourceRecoveryContractTest {
    private ResourceSourceAuthorityPort.Binding binding() {
        return new ResourceSourceAuthorityPort.Binding("MAIN", "E", "S", "C", "BEAUTY_APPOINTMENT", "A", 1,
                "RELEASE", "a".repeat(64), "b".repeat(64));
    }
    @Test
    void identityNamespaceAndExactPermissionAreMandatory() {
        assertThatThrownBy(() -> new MerchantOperatorAccessPort.Query("MAIN", "E", "S", "A", "CONSUMER", "USR",
                "biz:order:view")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MerchantOperatorAccessPort.Query("MAIN", "E", "S", "A", "MERCHANT", "MCH",
                "biz:order:*")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MerchantOperatorAccessPort.Query("MAIN", "E", "S ", "A", "MERCHANT", "MCH",
                "biz:order:view")).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void explicitAbsenceSurvivesGlobalNullSuppressionButMissingPropertyIsInvalid() {
        var mapper = JsonMapper.builder().changeDefaultPropertyInclusion(
                value -> value.withValueInclusion(JsonInclude.Include.NON_NULL)).build();
        assertThat(mapper.writeValueAsString(new ResourceCommandFactsPort.Lookup(null)))
                .isEqualTo("{\"command\":null}");
        assertThat(mapper.readValue("{\"command\":null}", ResourceCommandFactsPort.Lookup.class).command()).isNull();
        assertThatThrownBy(() -> mapper.readValue("{}", ResourceCommandFactsPort.Lookup.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void pendingCannotCarryTerminalEvidence() {
        assertThat(new ResourceCommandFactsPort.Fact(binding(), ResourceCommandFactsPort.Status.ACCEPTED, 0,
                null, null, null).terminalHash()).isNull();
        assertThatThrownBy(() -> new ResourceCommandFactsPort.Fact(binding(), ResourceCommandFactsPort.Status.ACCEPTED,
                0, "{}", null, null)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test
    void terminalFingerprintBindsOriginalUtf8BytesAndVersion() {
        var status = ResourceCommandFactsPort.Status.REJECTED;
        String payload = " {\"message\":\"已拒绝😀\"} ";
        String hash = ResourceCommandFactsPort.fingerprint(binding(), status, 2, payload, "NO_EFFECTS");
        assertThat(new ResourceCommandFactsPort.Fact(binding(), status, 2, payload, "NO_EFFECTS", hash).resultPayload())
                .isEqualTo(payload);
        assertThatThrownBy(() -> new ResourceCommandFactsPort.Fact(binding(), status, 3, payload, "NO_EFFECTS", hash))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResourceCommandFactsPort.Fact(binding(), status, 2, payload.strip(),
                "NO_EFFECTS", hash)).isInstanceOf(IllegalArgumentException.class);
    }
}
