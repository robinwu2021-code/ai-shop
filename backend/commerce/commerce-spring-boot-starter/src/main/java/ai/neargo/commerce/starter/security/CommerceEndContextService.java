package ai.neargo.commerce.starter.security;

import ai.neargo.commerce.api.access.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;

import java.time.Instant;
import java.util.*;

/** A dedicated HS256 key ring. Only local configured key ids participate in verification. */
public final class CommerceEndContextService implements EndContextCodecPort {
    private final Map<String, byte[]> keys;
    private final String active;

    public CommerceEndContextService(String active, Map<String, byte[]> keys) {
        var copy = new HashMap<String, byte[]>();
        keys.forEach(
                (id, key) -> {
                    if (id.isBlank() || key.length < 32)
                        throw new IllegalArgumentException("Invalid end context key configuration");
                    copy.put(id, key.clone());
                });
        if (!copy.containsKey(active))
            throw new IllegalArgumentException("Missing active end context key");
        this.keys = Map.copyOf(copy);
        this.active = active;
    }

    public String encode(EndContext c) {
        var a = c.actor();
        var claims =
                new JWTClaimsSet.Builder()
                        .jwtID(c.contextNo())
                        .subject(a.principalNo())
                        .audience(c.audience())
                        .claim("tenant", a.tenantNo())
                        .claim("kind", a.subjectKind().name())
                        .claim("sid", a.accountSessionFingerprint())
                        .claim("store", c.store().storeNo())
                        .claim("end", c.endCode())
                        .claim("ent", c.entitlementCode())
                        .claim("client", c.clientId())
                        .claim("ver", c.entitlementVersion())
                        .issueTime(Date.from(c.issuedAt()))
                        .expirationTime(Date.from(c.expiresAt().plusMillis(999)))
                        .claim("iat_ms", c.issuedAt().toEpochMilli())
                        .claim("exp_ms", c.expiresAt().toEpochMilli())
                        .build();
        var jwt =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.HS256)
                                .keyID(active)
                                .type(JOSEObjectType.JWT)
                                .build(),
                        claims);
        try {
            jwt.sign(new MACSigner(keys.get(active)));
            return jwt.serialize();
        } catch (JOSEException error) {
            throw new IllegalStateException("Context signing unavailable", error);
        }
    }

    public EndContextVerification verify(String encoded, String expectedAudience, Instant now) {
        var rejected = new EndContextVerification(Optional.empty(), Optional.of("INVALID_CONTEXT"));
        if (encoded == null || encoded.length() > 8192) return rejected;
        try {
            var jwt = SignedJWT.parse(encoded);
            var header = jwt.getHeader();
            if (!JWSAlgorithm.HS256.equals(header.getAlgorithm())
                    || !JOSEObjectType.JWT.equals(header.getType())
                    || header.getCriticalParams() != null
                    || !keys.containsKey(header.getKeyID())
                    || !jwt.verify(new MACVerifier(keys.get(header.getKeyID())))) return rejected;
            var c = jwt.getJWTClaimsSet();
            var start = Instant.ofEpochMilli(c.getLongClaim("iat_ms"));
            var expiry = Instant.ofEpochMilli(c.getLongClaim("exp_ms"));
            if (!List.of(expectedAudience).equals(c.getAudience())
                    || now.isBefore(start)
                    || !now.isBefore(expiry)
                    || expiry.isAfter(start.plusSeconds(300))
                    || !"ACCOUNT".equals(c.getStringClaim("kind"))) return rejected;
            var actor =
                    new ActorContext(
                            c.getStringClaim("tenant"),
                            SubjectKind.ACCOUNT,
                            c.getSubject(),
                            c.getStringClaim("sid"));
            var proof =
                    new EndContext(
                            c.getJWTID(),
                            actor,
                            new StoreRef(actor.tenantNo(), c.getStringClaim("store")),
                            c.getStringClaim("end"),
                            c.getStringClaim("ent"),
                            c.getStringClaim("client"),
                            expectedAudience,
                            c.getLongClaim("ver"),
                            start,
                            expiry);
            return new EndContextVerification(Optional.of(proof), Optional.empty());
        } catch (Exception invalid) {
            return rejected;
        }
    }
}
