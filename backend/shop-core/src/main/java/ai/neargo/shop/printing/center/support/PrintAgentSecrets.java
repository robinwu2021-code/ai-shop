package ai.neargo.shop.printing.center.support;

import ai.neargo.shop.print.api.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.*;
import java.security.spec.*;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

@Component
public final class PrintAgentSecrets {
    private final String leaseKey;

    public PrintAgentSecrets(@Value("${shop.print.agent.lease-key:}") String key) {
        leaseKey = key;
    }

    public String random() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String seal(String text, String key) {
        try {
            byte[] der =
                    Base64.getDecoder()
                            .decode(
                                    key.replace("-----BEGIN PUBLIC KEY-----", "")
                                            .replace("-----END PUBLIC KEY-----", "")
                                            .replaceAll("\\s", ""));
            var pub = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(der));
            if (!(pub instanceof java.security.interfaces.RSAPublicKey rsa)
                    || rsa.getModulus().bitLength() < 2048) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    pub,
                    new OAEPParameterSpec(
                            "SHA-256",
                            "MGF1",
                            MGF1ParameterSpec.SHA256,
                            PSource.PSpecified.DEFAULT));
            return Base64.getEncoder()
                    .encodeToString(
                            cipher.doFinal(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new PrintFailure("print.agentPublicKeyInvalid");
        }
    }

    public String lease(String dispatch, long session, long fence, java.time.Instant until) {
        try {
            byte[] key = Base64.getDecoder().decode(leaseKey);
            if (key.length < 32) throw new IllegalArgumentException();
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            mac.doFinal(
                                    (dispatch
                                                    + ":"
                                                    + session
                                                    + ":"
                                                    + fence
                                                    + ":"
                                                    + until.toEpochMilli())
                                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new PrintFailure("print.agentLeaseKeyNotConfigured");
        }
    }

    public boolean matches(String raw, String hash) {
        return raw != null
                && hash != null
                && MessageDigest.isEqual(
                        PrintHashes.sha256(raw)
                                .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                        hash.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
}
