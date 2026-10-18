package ai.neargo.shop.spi.entitlement;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 可信来源的稳定身份；仅供内部协议，不能代替服务鉴权、本人授权或源事实核实。
 *
 * <p>租户由调用上下文隔离，数据库唯一键必须同时包含tenant_no与来源键。
 * 订单单件和件内权益采用从1开始的序号；发行身份不随源订单修订变化。
 */
public record SourceRef(String sourceSystem, String sourceType, String sourceNo, String sourceLineNo,
                        Integer unitIndex, Integer entitlementIndex, long sourceRevision) {
    public SourceRef {
        requireIdentity(sourceSystem);
        requireIdentity(sourceType);
        requireIdentity(sourceNo);
        if (sourceLineNo != null) {
            requireIdentity(sourceLineNo);
        }
        if (sourceRevision < 0 || (unitIndex != null && unitIndex <= 0)
                || (entitlementIndex != null && entitlementIndex <= 0)) {
            throw new IllegalArgumentException("Source revision or unit index is invalid");
        }
        if ((unitIndex != null && sourceLineNo == null)
                || (entitlementIndex != null && unitIndex == null)) {
            throw new IllegalArgumentException("Unit identity requires its parent source identity");
        }
    }

    /** 同一来源修订后不能再次发行，单件/件内权益仍分别唯一。 */
    public String grantKey() {
        return key("GRANT", false);
    }

    /** 源意图改版产生新预占，但须先确认原意图决策。 */
    public String useIntentKey() {
        return key("USE_INTENT", true);
    }

    /** 成功使用唯一键不含修订号，防止改价后在同一来源再次提交。 */
    public String settledKey() {
        return key("SETTLED", false);
    }

    private String key(String purpose, boolean includeRevision) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            append(digest, "ENT_SOURCE_V1");
            append(digest, purpose);
            append(digest, sourceSystem);
            append(digest, sourceType);
            append(digest, sourceNo);
            append(digest, sourceLineNo);
            append(digest, unitIndex == null ? null : unitIndex.toString());
            append(digest, entitlementIndex == null ? null : entitlementIndex.toString());
            if (includeRevision) {
                append(digest, Long.toString(sourceRevision));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", e);
        }
    }

    private static void append(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void requireIdentity(String value) {
        if (value == null || value.isBlank() || value.length() > 64 || !value.equals(value.strip())) {
            throw new IllegalArgumentException("Source identity must contain 1 to 64 unpadded characters");
        }
    }
}
