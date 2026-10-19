package ai.neargo.shop.pay.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.entity.EntCodeToken;
import ai.neargo.shop.pay.entitlement.service.EntitlementCodeSigner;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EntitlementCodeSignerTest {
    private EntCodeToken row() {
        var row = new EntCodeToken(); row.setTenantNo("MAIN"); row.setUserNo("U1"); row.setInstanceNo("I1");
        row.setRequestKeyHash("a".repeat(64)); row.setNonce("b".repeat(32)); return row;
    }
    @Test void missingOrChangedKeyCannotSilentlyMintAnotherCredentialForTheSameRequest() {
        var json = new ObjectMapper(); var row = row();
        assertThatThrownBy(() -> new EntitlementCodeSigner("", json).token(row)).isInstanceOf(BizException.class);
        var signer = new EntitlementCodeSigner("test-only-stable-key-a-over-thirty-two-characters", json);
        String original = signer.token(row); row.setTokenHash(EntitlementRecords.digest(original));
        assertThat(signer.token(row)).isEqualTo(original);
        assertThatThrownBy(() -> new EntitlementCodeSigner("test-only-stable-key-b-over-thirty-two-characters", json)
                .token(row)).isInstanceOf(BizException.class);
    }
    @Test void identicalNonceAndRequestKeyCannotCrossTheTenantOrHolderBoundary() {
        var signer = new EntitlementCodeSigner("test-only-stable-key-over-thirty-two-characters", new ObjectMapper());
        var row = row(); String original = signer.token(row);
        row.setTenantNo("OTHER"); assertThat(signer.token(row)).isNotEqualTo(original);
        row.setTenantNo("MAIN"); row.setUserNo("U2"); assertThat(signer.token(row)).isNotEqualTo(original);
    }
}
