package ai.neargo.secret.api;

/**
 * 平台生成对称密钥时的形态。材料就是这串可打印字符本身，按 ASCII 字节当密钥 —— 对方拿到什么就用什么，
 * 两边不会差一层「要不要先 Base64 解码」。
 */
public enum SecretFormat {
    /** 32 位 {@code [A-Za-z0-9]}（约 190 bit）：开放 API 的 AppSecret 与 EncryptKey（TDD §2.1，与微信支付 APIv3 密钥同一约定） */
    ALNUM_32,
    /** 32 字节随机数的 Base64URL（43 位，256 bit）：W5 起的默认形态，终端设备密钥继续用 */
    BASE64URL_32B
}
