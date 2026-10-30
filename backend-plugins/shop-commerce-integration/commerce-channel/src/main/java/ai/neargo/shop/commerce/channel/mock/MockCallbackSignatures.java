package ai.neargo.shop.commerce.channel.mock;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Mock protocol signs the original bytes and credential generation, never a reserialized event. */
public final class MockCallbackSignatures {
    private MockCallbackSignatures() {}

    public static String sign(String secret, String timestamp, String credentialRef, byte[] bytes) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestamp + "\n" + credentialRef + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(bytes));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
