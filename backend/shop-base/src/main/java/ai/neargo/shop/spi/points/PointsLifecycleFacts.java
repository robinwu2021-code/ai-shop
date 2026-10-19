package ai.neargo.shop.spi.points;

import ai.neargo.shop.spi.points.PointsFactPort.LifecycleFact;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.function.Function;

/** Canonical v1 field order; an absent new payment proof preserves the existing kernel digest. */
public final class PointsLifecycleFacts {
    private PointsLifecycleFacts() {}

    public static String digest(LifecycleFact f, Function<Object, String> serialize) {
        Map<String, Object> unsigned = new LinkedHashMap<>();
        unsigned.put("eventNo", f.eventNo());
        unsigned.put("payloadHash", null);
        unsigned.put("subOrderNo", f.subOrderNo());
        unsigned.put("userNo", f.userNo());
        unsigned.put("typeCode", f.typeCode());
        unsigned.put("sourceVersion", f.sourceVersion());
        unsigned.put("kind", f.kind());
        unsigned.put("finalGoodsMinor", f.finalGoodsMinor());
        unsigned.put("refundedGoodsMinor", f.refundedGoodsMinor());
        unsigned.put("lines", f.lines());
        unsigned.put("occurredAt", f.occurredAt());
        if (f.payment() != null) unsigned.put("payment", f.payment());
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            serialize
                                                    .apply(unsigned)
                                                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static LifecycleFact sign(LifecycleFact f, Function<Object, String> serialize) {
        return new LifecycleFact(
                f.eventNo(),
                digest(f, serialize),
                f.subOrderNo(),
                f.userNo(),
                f.typeCode(),
                f.sourceVersion(),
                f.kind(),
                f.finalGoodsMinor(),
                f.refundedGoodsMinor(),
                f.lines(),
                f.occurredAt(),
                f.payment());
    }
}
