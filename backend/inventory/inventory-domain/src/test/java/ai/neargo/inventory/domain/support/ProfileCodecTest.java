package ai.neargo.inventory.domain.support;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.profile.BusinessProfile;
import ai.neargo.inventory.api.profile.Capability;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T0.1：四个业态的示例档案都能被同一套规则接受；常见的写错方式都被拒绝并说清是哪一条。
 */
class ProfileCodecTest {

    @ParameterizedTest
    @ValueSource(strings = {"shop", "food", "sharehub", "charging"})
    void fourBusinessProfilesAreValidAndRoundTrip(String name) throws IOException {
        BusinessProfile p = ProfileCodec.parse(read(name));
        assertThat(ProfileCodec.parse(ProfileCodec.write(p))).isEqualTo(p);
    }

    @Test
    void deviceProfilesDifferOnlyInConfiguration() throws IOException {
        BusinessProfile sharehub = ProfileCodec.parse(read("sharehub"));
        BusinessProfile charging = ProfileCodec.parse(read("charging"));
        assertThat(sharehub.capabilities()).containsExactlyInAnyOrder(Capability.SERIAL, Capability.DISPATCH_POOL);
        assertThat(charging.capabilities()).isEqualTo(sharehub.capabilities());
        assertThat(sharehub.gateRefTypes()).containsExactly("QC");
        assertThat(charging.gateRefTypes()).containsExactly("ACCEPTANCE");
    }

    @Test
    void poolHandoverRequiresTheDispatchPoolCapability() throws IOException {
        String broken = read("sharehub").replace("\"SERIAL\", \"DISPATCH_POOL\"", "\"SERIAL\"");
        assertRule(broken, "outboundPurposes.handover.requiresDispatchPool:ISSUE");
    }

    @Test
    void optionalTargetMustHangUnderARequiredOne() throws IOException {
        // 站点挂在商家下；把商家从必填里拿掉，「这单发给谁」就说不清了
        String broken = read("sharehub").replace("[\"MERCHANT\", \"PERSON\"]", "[\"PERSON\"]");
        assertRule(broken, "outboundPurposes.optionalTargets:ISSUE");
    }

    @Test
    void unknownParentTypeAndUnknownFieldsAreRejected() throws IOException {
        assertRule(read("sharehub").replace("\"parentType\": \"MERCHANT\"", "\"parentType\": \"NOPE\""),
                "targetTypes.parentType:SITE");
        assertRule(read("shop").replace("\"schemaVersion\": 1,", "\"schemaVersion\": 1, \"extra\": true,"), "json");
    }

    private static void assertRule(String json, String rule) {
        assertThatThrownBy(() -> ProfileCodec.parse(json))
                .isInstanceOf(InventoryException.class)
                .satisfies(e -> {
                    assertThat(((InventoryException) e).error()).isEqualTo(InventoryError.PROFILE_INVALID);
                    assertThat(((InventoryException) e).args()).containsExactly(rule);
                });
    }

    private static String read(String name) throws IOException {
        try (InputStream in = ProfileCodecTest.class.getResourceAsStream("/profiles/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
