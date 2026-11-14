package ai.neargo.shop.pay.channel.http;

/**
 * 原文怎么存：给密钥模块（W5）留的缝。W5 就绪后换成信封加密实现（数据密钥经 {@code secret-api} 包裹），
 * 记录型客户端与表结构都不动。
 */
public interface RawPayloadSealer {

    /**
     * @param plain  完整原文（含头与体）
     * @param masked 同一份的脱敏全文
     */
    Sealed seal(String plain, String masked);

    /**
     * @param cipherText     落 {@code cipher_text} 的内容
     * @param dataKeyCipher  被主密钥包裹的数据密钥；未加密时为标记值
     * @param masterKeyId    {@code sec_master_key.id}；未加密为 0
     */
    record Sealed(String cipherText, String dataKeyCipher, long masterKeyId) {
    }
}
