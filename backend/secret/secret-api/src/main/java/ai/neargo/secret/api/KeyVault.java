package ai.neargo.secret.api;

/**
 * 密钥运算的唯一入口（TDD-支付服务-开放API加解密与密钥分发 §2.8.2 · 类清单 §1）。
 *
 * <p>按「操作」抽象、不按「取明文」抽象：L5 的密钥根本取不出来，只有这样五档才能是同一个接口，
 * 业务代码换档一行不改。生成、录入、吊销在 {@link KeyRegistry}；封存业务敏感字段在 {@link SecretVault}。
 *
 * <p>每次运算都校验版本状态（ACTIVE / ROTATING / RETIRED 可用，与 {@link KeyRegistry#materialOf} 同一口径）
 * 与密钥用途：吊销对下一次运算立即生效，HMAC 密钥不能拿来加密。
 */
public interface KeyVault {

    /** HMAC-SHA256：开放 API 请求 / 响应签名的基础方案（P1 / P2） */
    byte[] mac(KeyVersionRef ref, byte[] data);

    /** 私钥签名（SHA256withRSA / SHA256withECDSA，按密钥算法）：增强方案的响应与推送签名（P2） */
    byte[] sign(KeyVersionRef ref, byte[] data);

    /** AES-256-GCM，IV 由实现随机生成：返回 {@code IV(12) ‖ 密文 ‖ Tag(16)}（P3 / P4 / P5） */
    byte[] encrypt(KeyVersionRef ref, byte[] plaintext, byte[] aad);

    /**
     * 对称密钥：AES-256-GCM，输入 {@code IV ‖ 密文 ‖ Tag}；RSA 私钥：RSA-OAEP，摘要与 MGF1 都是 SHA-256，{@code aad} 须为空。
     * 认证失败（密文被改、AAD 不符、OAEP 参数不符）一律 {@link SecretException.Reason#INTEGRITY}，不区分哪一步。
     */
    byte[] decrypt(KeyVersionRef ref, byte[] ciphertext, byte[] aad);

    /** 该版本材料所在的档 */
    StorageTier tierOf(KeyVersionRef ref);
}
