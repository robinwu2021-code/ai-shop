package ai.neargo.shop.commercebridge;

import ai.neargo.shop.auth.LoginUser;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/** The raw identity remains in the authoritative session; commercial buyer keys are bounded. */
final class ShopMallIdentity {
    static String buyerKey(LoginUser user) {
        String kind =
                switch (user.subjectKind()) {
                    case USR -> "USR";
                    case MCH -> "MCH";
                    default -> throw new IllegalArgumentException("buyer kind required");
                };
        return buyerKey(kind, user.userNo());
    }

    static String buyerKey(String kind, String rawNo) {
        if (!"USR".equals(kind) && !"MCH".equals(kind))
            throw new IllegalArgumentException("buyer kind required");
        if (rawNo == null || rawNo.isBlank() || rawNo.length() > 64)
            throw new IllegalArgumentException("invalid buyer identity");
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(
                                    ("commerce-mall-buyer:v1:" + kind + ":" + rawNo)
                                            .getBytes(StandardCharsets.UTF_8));
            return kind + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
