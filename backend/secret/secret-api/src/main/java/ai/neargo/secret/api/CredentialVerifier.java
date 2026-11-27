package ai.neargo.secret.api;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * API Key 类凭据的统一入口（TDD-基础能力库对齐 §2.2「1h 开放应用」，Q15 / Q16）：发放、自助登记、验证、吊销、列出。
 *
 * <p>凭据进密钥库 {@code sec_key}，但<b>只存摘要</b>（{@link StorageTier#DIGEST}）：每张凭据是所有者那把密钥下的一个版本，
 * 版本上只有 SHA-256 摘要，没有密文；{@link KeyRegistry#materialOf} 对它拒绝。库被拖走也拿不到一把能用的凭据。
 *
 * <p>与 HMAC 密钥不同，同一所有者可以同时有多张有效凭据（运营人员给几个脚本各发一把），
 * 所以 DIGEST 档的版本各自 ACTIVE、不占「一把密钥一个 ACTIVE 版本」那个名额 —— 每个版本就是一张独立的凭据。
 *
 * <p>摘要口径：出示的整串（含显示前缀，如 {@code oak_…}）按 UTF-8 取 SHA-256，小写十六进制 —— 与令牌存储
 * {@code TokenHash} 同一算法，于是调用方只交摘要的自助登记也能验得过。
 */
public interface CredentialVerifier {

    /**
     * 平台生成一张凭据：32 字节随机数的 Base64URL，前面接 {@code prefix}。<b>明文只在返回值里出现这一次</b>，
     * 同时记一条 {@code sec_access_log(REVEAL_ON_ISSUE)}。
     *
     * @throws SecretException {@link SecretException.Reason#DUPLICATE}：同一命名空间同一归属类型下凭据号已用过
     */
    Issued issue(NewCredential credential, String prefix);

    /**
     * 自助登记：调用方本机生成凭据，只交摘要。同一所有者、同一凭据号、内容完全一致的重复登记返回已有那张（含已吊销的），
     * {@link Registration#created()} 为 false。
     *
     * @param digest 出示串的 SHA-256 小写十六进制（64 位）
     * @throws SecretException {@link SecretException.Reason#DUPLICATE}：凭据号已用于不同内容或别的所有者，或摘要已被占用
     */
    Registration register(NewCredential credential, String digest);

    /** 按出示的凭据找它属于谁：未知、已吊销、已过期、不属于本命名空间或本环境一律为空，不区分是哪一种 */
    Optional<Verified> verify(SecretNamespace namespace, String presented);

    /** 一个所有者的全部凭据（含已吊销、已过期），新的在前；没有任何材料 */
    List<Credential> list(KeyOwner owner);

    /** 吊销一张：返回这次是否真的从有效变成吊销（已吊销或不存在为 false） */
    boolean revoke(KeyOwner owner, String credentialNo, String operator, String reason);

    /** 吊销一个所有者的全部有效凭据（停用账号、改密时用），返回吊销的张数 */
    int revokeAll(KeyOwner owner, String operator, String reason);

    /**
     * 一张凭据的描述。
     *
     * @param credentialNo 调用方给的凭据号（同一命名空间同一归属类型下唯一，存 {@code sec_key_version.external_key_id}）
     * @param label        给人看的名字
     * @param scope        调用方模块定义的权限范围（如 READ_ONLY / MANAGE），密钥模块不解释
     * @param validity     有效期，从发放那一刻算
     * @param issuedBy     发放或登记人
     */
    record NewCredential(KeyOwner owner, String credentialNo, String label, String scope, Duration validity,
                         String issuedBy) {

        public NewCredential {
            if (owner == null || credentialNo == null || credentialNo.isBlank() || validity == null
                    || validity.isNegative() || validity.isZero() || issuedBy == null || issuedBy.isBlank()) {
                throw new IllegalArgumentException("owner, credentialNo, positive validity and issuedBy are required");
            }
        }
    }

    /** 一张凭据的元数据：没有任何材料，也不给摘要 */
    record Credential(KeyOwner owner, String credentialNo, String label, String scope, String issuedBy,
                      Instant issuedAt, Instant expiresAt, Instant revokedAt) {
    }

    /** 发放结果：明文只此一次，调用方展示后即丢弃，不得落库、不得进日志 */
    record Issued(Credential credential, String secret) {

        @Override
        public String toString() {
            return "Issued[credential=" + credential + "]";
        }
    }

    /** 登记结果：{@code created} = 这次新登记（重复登记已有的那张为 false） */
    record Registration(Credential credential, boolean created) {
    }

    /** 验证通过：凭据属于谁、是哪一张、权限范围 */
    record Verified(KeyOwner owner, String credentialNo, String scope) {
    }
}
