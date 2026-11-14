package ai.neargo.secret.api;

/**
 * 进程内的密文保险柜：<b>全平台唯一的解密点</b>（表结构说明 §3.2）。
 *
 * <p>信封加密：每段密文一把随机数据密钥（AES-256-GCM），数据密钥再由本命名空间的主密钥包裹；
 * 附加认证数据绑定 {@code id + namespace + owner_type + owner_id + env} —— 密文被挪到别的行即解密失败。
 *
 * <p>环境（SANDBOX / PRODUCTION）不是参数：它是进程的属性（{@code shop.secret.env}），
 * 生产密钥因此永远进不了沙箱进程的手。没配置环境的进程一律拒绝加解密。
 *
 * <p>它是库，不是服务：不要把它包成一个返回明文的远程接口 —— 那会让明文走网络。
 */
public interface SecretVault {

    /** 封存一段敏感字段（purpose = SENSITIVE_FIELD），返回业务表要存的引用 */
    SecretRef seal(KeyOwner owner, byte[] plaintext);

    /**
     * 解开一段密文。
     *
     * @param namespace 调用方所属命名空间；与密文的不一致直接拒绝（{@link SecretException.Reason#NAMESPACE_MISMATCH}）
     */
    byte[] open(SecretNamespace namespace, SecretRef ref);
}
