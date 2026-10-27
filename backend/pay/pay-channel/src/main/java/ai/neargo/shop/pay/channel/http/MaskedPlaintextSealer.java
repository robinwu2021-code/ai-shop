package ai.neargo.shop.pay.channel.http;

/**
 * 密钥模块就绪前的过渡（复核 §3.2：「W5 前存脱敏全文」）：<b>不加密，存脱敏全文</b>。
 *
 * <p>不存明文是因为这张表比日志更容易被导出与备份 —— 没有加密的明文原文就是一份凭据与个人信息的拷贝。
 * 脱敏全文已经够排障（结构、金额、单号都在），完整原文要等能加密时再存。{@code sha256} 仍按<b>明文</b>算，事后能举证。
 */
public final class MaskedPlaintextSealer implements RawPayloadSealer {

    /** {@code data_key_cipher} 的标记值：这一行没有加密、也不是完整原文 */
    public static final String NOT_ENCRYPTED = "NONE:MASKED_PLAINTEXT";

    @Override
    public Sealed seal(String plain, String masked) {
        return new Sealed(masked, NOT_ENCRYPTED, 0L);
    }
}
