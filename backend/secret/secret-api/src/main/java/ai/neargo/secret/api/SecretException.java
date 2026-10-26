package ai.neargo.secret.api;

/**
 * 密钥模块唯一的异常。契约包零依赖，用不了平台的 {@code BizException}；调用方按 {@link #reason()} 翻译成自己的错误码。
 *
 * <p><b>消息里永远不放密钥材料</b>，只放 id、状态、命名空间这类元数据 —— 异常消息会进日志。
 */
public class SecretException extends RuntimeException {

    private final Reason reason;

    public SecretException(Reason reason, String message) {
        super(reason + ": " + message);
        this.reason = reason;
    }

    public SecretException(Reason reason, String message, Throwable cause) {
        super(reason + ": " + message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        /** 引用的密钥 / 版本 / 密文不存在 */
        NOT_FOUND,
        /** 密文属于别的命名空间：进程只能解本域 */
        NAMESPACE_MISMATCH,
        /** 进程没配置环境（{@code shop.secret.env}），拒绝加解密 —— 不猜沙箱还是生产 */
        ENV_NOT_CONFIGURED,
        /** 本命名空间本环境没有可用主密钥，或密文用的主密钥不在本进程 */
        NO_MASTER_KEY,
        /** 解密失败：密文被改、被挪到别的行、或主密钥不对 */
        INTEGRITY,
        /** 生命周期不允许这一步（如启用一个已吊销的版本） */
        ILLEGAL_STATE,
        /** 两人控制：启用人与录入人相同 */
        DUAL_CONTROL,
        /** 版本不可用于这次操作（已吊销、未启用、已过重叠期） */
        VERSION_UNUSABLE,
        /** 密钥不可用于新调用（已到期 / 暂停 / 吊销） */
        KEY_UNUSABLE
    }
}
