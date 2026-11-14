package ai.neargo.shop.food.policy;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.food.dto.OfflineDraftContracts.Envelope;
import ai.neargo.shop.food.dto.OfflineDraftContracts.LeaseProof;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

/**
 * Real Ed25519 key possession, purpose separation and schema admission, never a digest-auth
 * substitute.
 */
class OfflineDraftSignaturesTest {
    @Test
    void genuineSignatureCannotAuthorizeAnotherFenceOrAnotherPurpose() throws Exception {
        var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var original = new LeaseProof("E", "S", "D", 1, "K", 7, 300, "COMMAND");
        var signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(OfflineDraftSignatures.message("FOOD_OFFLINE_LEASE_V1", original));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        String publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        OfflineDraftSignatures.verify(publicKey, signature, "FOOD_OFFLINE_LEASE_V1", original);
        assertThatThrownBy(
                        () ->
                                OfflineDraftSignatures.verify(
                                        publicKey, signature, "FOOD_OFFLINE_DRAFT_V1", original))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                OfflineDraftSignatures.verify(
                                        publicKey,
                                        signature,
                                        "FOOD_OFFLINE_LEASE_V1",
                                        new LeaseProof("E", "S", "D", 1, "K", 8, 300, "COMMAND")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void callerPaymentAndStockClaimsCannotEnterTheSignedEnvelopeSchema() {
        var json = JsonMapper.builder().findAndAddModules().build();
        assertThatThrownBy(
                        () -> json.readValue("{\"entityNo\":\"E\",\"paid\":true}", Envelope.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(
                        () -> json.readValue("{\"entityNo\":\"E\",\"stock\":100}", Envelope.class))
                .isInstanceOf(RuntimeException.class);
    }
}
