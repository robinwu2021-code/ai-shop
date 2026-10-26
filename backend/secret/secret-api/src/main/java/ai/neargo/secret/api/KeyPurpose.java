package ai.neargo.secret.api;

/**
 * 一把密钥拿来干什么：方向 × 用途。与 {@link KeyOwner} 合起来就是 {@code sec_key} 的唯一键
 * （命名空间 × 归属 × 方向 × 用途 × 环境；环境由进程决定，见 {@link SecretVault}）。
 *
 * <p>「轮换是在同一把密钥下加版本，不是新建密钥」（表结构说明 §3.1）—— 所以调用方找密钥只报这两样，
 * 不报版本；版本由 {@link KeyRegistry} 按生命周期挑。
 */
public record KeyPurpose(Direction direction, Usage usage) {

    /** 第三方 / 内部系统调我方：我方用它验 HMAC 签名 */
    public static final KeyPurpose INBOUND_HMAC = new KeyPurpose(Direction.INBOUND, Usage.HMAC);
    /** 我方调渠道：签名 */
    public static final KeyPurpose OUTBOUND_SIGN = new KeyPurpose(Direction.OUTBOUND, Usage.SIGN);
    /** 我方调渠道：报文加解密（如微信 APIv3 密钥、蓝新 HashKey + HashIV） */
    public static final KeyPurpose OUTBOUND_ENCRYPT = new KeyPurpose(Direction.OUTBOUND, Usage.ENCRYPT);
    /** 验渠道的响应与回调签名（平台公钥 / 证书） */
    public static final KeyPurpose COUNTERPARTY_VERIFY = new KeyPurpose(Direction.COUNTERPARTY_VERIFY, Usage.VERIFY);

    public KeyPurpose {
        if (direction == null || usage == null) {
            throw new IllegalArgumentException("direction and usage are required");
        }
    }

    /** {@code sec_key.direction} */
    public enum Direction {
        /** 我方调外部时使用 */
        OUTBOUND,
        /** 外部调我方时我方验证 */
        INBOUND,
        /** 验对端响应与回调 */
        COUNTERPARTY_VERIFY
    }

    /** {@code sec_key.key_usage}（列不叫 usage：MariaDB 保留字） */
    public enum Usage {
        SIGN,
        VERIFY,
        HMAC,
        ENCRYPT,
        MTLS
    }
}
