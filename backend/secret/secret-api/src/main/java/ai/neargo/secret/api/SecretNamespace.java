package ai.neargo.secret.api;

/**
 * 命名空间：每个域一套主密钥，进程只能解本域（表结构说明 §3.0）。
 *
 * <p>隔离靠的是<b>主密钥按命名空间下发</b>：支付进程只注入 PAY 的主密钥，库里 LOGISTICS 的密文它解不开 ——
 * 不是因为代码里有一句 if，而是它手里根本没有那把钥匙。{@link SecretVault#open} 上的命名空间比对只是第二道。
 */
public enum SecretNamespace {
    PAY,
    LOGISTICS,
    INVENTORY,
    NOTIFY,
    PLATFORM
}
