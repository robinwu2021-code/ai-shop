package ai.neargo.shop.pay.points.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Deterministic compact source keys let replay find a head before calculating a new result. */
public final class PointsKeys {
    private PointsKeys() { }
    public static String position(String kind,String type,String source) {
        try {
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((type.length()+":"+type+source.length()+":"+source).getBytes(StandardCharsets.UTF_8)));
            return kind+hash.substring(0,32);
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
