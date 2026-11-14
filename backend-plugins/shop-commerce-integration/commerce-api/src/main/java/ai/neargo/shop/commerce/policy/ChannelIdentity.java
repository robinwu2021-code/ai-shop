package ai.neargo.shop.commerce.policy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 长度编码避免分隔符碰撞；不归一大小写、不纳入Token或本地账号号。 */
public final class ChannelIdentity {
    private ChannelIdentity() {}

    public static String storeKey(
            String namespace, boolean accountScoped, String externalAccount, String externalStore) {
        required(namespace);
        required(externalStore);
        if (accountScoped) required(externalAccount);
        return hash(
                part(namespace) + part(accountScoped ? externalAccount : "") + part(externalStore));
    }

    public static String hash(String input) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    public static String part(String value) {
        required(value);
        return value.length() + ":" + value;
    }

    private static void required(String value) {
        if (value == null) throw new IllegalArgumentException("Missing identity component");
    }
}
