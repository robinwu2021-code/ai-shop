package ai.neargo.shop.food.policy;

import ai.neargo.shop.common.pricing.PricingSnapshotHash;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Exact canonical signature preimage; a plain payload digest never proves device possession. */
public final class OfflineDraftSignatures {
    private OfflineDraftSignatures() {}

    public static byte[] message(String domain, Object value) {
        if (!java.util.Set.of(
                        "FOOD_OFFLINE_CHALLENGE_V1",
                        "FOOD_OFFLINE_LEASE_V1",
                        "FOOD_OFFLINE_DRAFT_V1")
                .contains(domain))
            throw new IllegalArgumentException("Unsupported offline signature domain");
        return (domain + "\n" + PricingSnapshotHash.of(value)).getBytes(StandardCharsets.UTF_8);
    }

    public static void key(String encoded) {
        try {
            var bytes = Base64.getDecoder().decode(encoded);
            if (bytes.length != 44)
                throw new IllegalArgumentException("Bounded Ed25519 SPKI key required");
            var key =
                    KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(bytes));
            if (!java.util.Arrays.equals(bytes, key.getEncoded()))
                throw new IllegalArgumentException("Canonical key required");
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Invalid Ed25519 public key", invalid);
        }
    }

    public static void verify(String key, String signature, String domain, Object value) {
        key(key);
        try {
            var bytes = Base64.getDecoder().decode(signature);
            if (bytes.length != 64)
                throw new IllegalArgumentException("Ed25519 signature required");
            var verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(
                    KeyFactory.getInstance("Ed25519")
                            .generatePublic(
                                    new X509EncodedKeySpec(Base64.getDecoder().decode(key))));
            verifier.update(message(domain, value));
            if (!verifier.verify(bytes))
                throw new IllegalArgumentException("Invalid device signature");
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Device possession not proven", invalid);
        }
    }
}
