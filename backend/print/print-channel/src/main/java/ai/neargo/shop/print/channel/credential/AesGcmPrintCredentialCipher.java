package ai.neargo.shop.print.channel.credential;

import ai.neargo.shop.print.api.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

import javax.crypto.*;
import javax.crypto.spec.*;

@Component
public final class AesGcmPrintCredentialCipher implements PrintCredentialCipherPort {
    private final String configuredKey;
    private final String keyVersion;
    private final Map<String, String> previousKeys;

    public AesGcmPrintCredentialCipher(
            @Value("${shop.print.credentials.key:}") String key,
            @Value("${shop.print.credentials.key-version:1}") String version,
            @Value("${shop.print.credentials.previous-keys-json:{}}") String previous) {
        configuredKey = key;
        keyVersion = version;
        var map = new TreeMap<String, String>();
        tools.jackson.databind.json.JsonMapper.builder()
                .build()
                .readTree(previous)
                .properties()
                .forEach(e -> map.put(e.getKey(), e.getValue().asText()));
        previousKeys = Map.copyOf(map);
    }

    private SecretKeySpec key() {
        return key(keyVersion);
    }

    private SecretKeySpec key(String version) {
        try {
            byte[] bytes =
                    Base64.getDecoder()
                            .decode(
                                    keyVersion.equals(version)
                                            ? configuredKey
                                            : previousKeys.getOrDefault(version, ""));
            if (bytes.length != 32) throw new IllegalArgumentException();
            return new SecretKeySpec(bytes, "AES");
        } catch (Exception e) {
            throw new PrintFailure("print.credentialKeyNotConfigured");
        }
    }

    private byte[] aad(PrintScope s, String ref) {
        return (s.tenantNo() + ":" + s.entityNo() + ":" + s.storeNo() + ":" + ref)
                .getBytes(StandardCharsets.UTF_8);
    }

    public EncryptedPrintCredential encrypt(PrintScope s, String ref, byte[] secret) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad(s, ref));
            byte[] payload = cipher.doFinal(secret);
            return new EncryptedPrintCredential(
                    ref,
                    keyVersion,
                    ByteBuffer.allocate(iv.length + payload.length).put(iv).put(payload).array());
        } catch (PrintFailure e) {
            throw e;
        } catch (Exception e) {
            throw new PrintFailure("print.credentialEncryptFailed");
        }
    }

    public byte[] decrypt(PrintScope s, EncryptedPrintCredential c) {
        if (!keyVersion.equals(c.encryptionKeyVersion())
                && !previousKeys.containsKey(c.encryptionKeyVersion()))
            throw new PrintFailure("print.credentialKeyVersionUnavailable");
        try {
            byte[] input = c.ciphertext();
            if (input.length < 29) throw new IllegalArgumentException();
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(c.encryptionKeyVersion()),
                    new GCMParameterSpec(128, Arrays.copyOf(input, 12)));
            cipher.updateAAD(aad(s, c.credentialRef()));
            return cipher.doFinal(Arrays.copyOfRange(input, 12, input.length));
        } catch (PrintFailure e) {
            throw e;
        } catch (Exception e) {
            throw new PrintFailure("print.credentialDecryptFailed");
        }
    }
}
