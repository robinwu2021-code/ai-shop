package ai.neargo.secret.core;

/**
 * ⏸ 占位（W5 只交付注入式）：云 KMS 的主密钥提供者契约。
 *
 * <p>实现要点（落地时照做）：
 * <ul>
 *   <li>{@link #wrap} / {@link #unwrap} 直接调 KMS 的 Encrypt / Decrypt（或 GenerateDataKey），
 *       {@code aad} 作为 KMS 的加密上下文传过去 —— 主密钥从不离开 KMS；</li>
 *   <li>KMS 授权<b>按命名空间下发</b>：支付进程的身份只被授予 PAY 的密钥，这才是「进程只能解本域」的真正边界；</li>
 *   <li>{@link #declared} 返回本进程被授权的 key id 列表，名字原样写进 {@code sec_master_key.kms_key_id}。</li>
 * </ul>
 * 选型（阿里云 KMS / AWS KMS / 自建 HSM）随生产部署定，见 TDD-支付服务-W5密钥模块 §4。
 */
public interface KmsMasterKeyProvider extends MasterKeyProvider {

    @Override
    default String providerType() {
        return "KMS";
    }
}
