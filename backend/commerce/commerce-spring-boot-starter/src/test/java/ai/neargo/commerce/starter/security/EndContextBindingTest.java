package ai.neargo.commerce.starter.security;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class EndContextBindingTest {
    final Instant start = Instant.ofEpochMilli(1000123), expiry = start.plusMillis(111);
    final EndContext context =
            new EndContext(
                    "jti",
                    new ActorContext("T", SubjectKind.ACCOUNT, "U", "fingerprint"),
                    new StoreRef("T", "S"),
                    "APP_POS",
                    "END_APP_POS",
                    "pos-app",
                    "aud",
                    42,
                    start,
                    expiry);
    final byte[] key = new java.security.SecureRandom().generateSeed(32);
    final CommerceEndContextService codec = new CommerceEndContextService("a", Map.of("a", key));

    @Test
    void preservesMillisecondsAndAllBindings() {
        var signed = codec.encode(context);
        assertEquals(
                context,
                codec.verify(signed, "aud", expiry.minusMillis(1)).context().orElseThrow());
        assertTrue(codec.verify(signed, "aud", expiry).context().isEmpty());
        assertTrue(codec.verify(signed, "other", start).context().isEmpty());
        assertTrue(codec.verify(signed, "aud", start.minusMillis(1)).context().isEmpty());
    }

    @Test
    void rejectsAValidSignatureWithAnUnregisteredAlgorithm() throws Exception {
        var longKey = new java.security.SecureRandom().generateSeed(64);
        var ring = new CommerceEndContextService("key", Map.of("key", longKey));
        var claims = com.nimbusds.jwt.SignedJWT.parse(ring.encode(context)).getJWTClaimsSet();
        var header =
                new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.HS512)
                        .type(com.nimbusds.jose.JOSEObjectType.JWT)
                        .keyID("key")
                        .build();
        var otherAlgorithm = new com.nimbusds.jwt.SignedJWT(header, claims);
        otherAlgorithm.sign(new com.nimbusds.jose.crypto.MACSigner(longKey));
        assertTrue(ring.verify(otherAlgorithm.serialize(), "aud", start).context().isEmpty());
    }

    @Test
    void refusesWrongKeyUnknownKidTamperingAndUnsecuredAlgorithm() {
        var signed = codec.encode(context);
        assertTrue(
                new CommerceEndContextService("b", Map.of("b", key))
                        .verify(signed, "aud", start)
                        .context()
                        .isEmpty());
        assertTrue(
                new CommerceEndContextService("a", Map.of("a", new byte[32]))
                        .verify(signed, "aud", start)
                        .context()
                        .isEmpty());
        var pieces = signed.split("\\.");
        assertTrue(
                codec.verify(pieces[0] + "." + pieces[1] + ".bogus", "aud", start)
                        .context()
                        .isEmpty());
        assertTrue(
                codec.verify("eyJhbGciOiJub25lIn0." + pieces[1] + ".", "aud", start)
                        .context()
                        .isEmpty());
        assertTrue(
                new CommerceEndContextService("b", Map.of("a", key, "b", new byte[32]))
                        .verify(signed, "aud", start)
                        .context()
                        .isPresent());
    }
}
