package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/** Stable hashing, UTC precision and explicit audit for pay-owned writes without a user session. */
public final class EntitlementRecords {
    private EntitlementRecords() { }
    public static void audit(BaseEntity row, EntitlementContext context) {
        row.setTenantNo(context.tenantNo());
        row.setCreatedAt(now());
        row.setUpdatedAt(row.getCreatedAt());
        row.setCreatedBy(context.actorNo());
        row.setUpdatedBy(context.actorNo());
        row.setDeleted(0);
        row.setVersion(0L);
    }
    public static LocalDateTime now() {
        return utc(Instant.now().truncatedTo(ChronoUnit.MILLIS));
    }
    public static LocalDateTime utc(Instant at) {
        return at == null ? null : LocalDateTime.ofInstant(at.truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
    public static Instant instant(LocalDateTime at) {
        return at == null ? null : at.toInstant(ZoneOffset.UTC);
    }
    public static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException missing) { throw new IllegalStateException("SHA-256 required", missing); }
    }
    public static void changed(int rows) { if (rows != 1) conflict(); }
    public static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
    public static void unavailable() { throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE); }
}
