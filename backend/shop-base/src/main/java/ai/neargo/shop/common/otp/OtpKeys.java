package ai.neargo.shop.common.otp;

import ai.neargo.shop.common.otp.service.OtpSendService.Channel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public final class OtpKeys {
    private OtpKeys() {}
    public static String normalize(Channel channel, String recipient) {
        return channel == Channel.EMAIL ? recipient.trim().toLowerCase(Locale.ROOT) : recipient;
    }
    /** LOGIN storage and all account hard limits retain their existing keys. */
    public static String recipientKey(Channel channel, String normalized) {
        return channel == Channel.EMAIL ? "email:" + hash(normalized) : normalized;
    }
    public static String storageKey(String scene, Channel channel, String normalized, String businessKey) {
        if (OtpScenes.LOGIN.equals(scene)) return recipientKey(channel, normalized);
        return "otp:" + hash(scene + "\n" + channel + "\n" + hash(normalized) + "\n" + hash(component(businessKey)));
    }
    public static String action(Channel channel) { return channel == Channel.SMS ? "OTP_SEND" : "EMAIL_OTP_SEND"; }
    public static String captchaScope(String kind, String action, String principal, String scene, String businessKey) {
        if (scene == null || OtpScenes.LOGIN.equals(scene)) return hash(kind + ":" + action + ":" + principal);
        return hash("v2\n" + kind + "\n" + action + "\n" + scene + "\n" + hash(principal) + "\n" + hash(component(businessKey)));
    }
    private static String component(String value) { return value == null ? "" : value; }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("Required SHA-256 unavailable", e); }
    }
}
