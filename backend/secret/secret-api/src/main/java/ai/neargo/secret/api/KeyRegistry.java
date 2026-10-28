package ai.neargo.secret.api;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 密钥登记与版本生命周期（表结构说明 §3.3）：
 * {@code DRAFT → VERIFIED → ACTIVE → ROTATING → RETIRED}，任意状态可 {@code REVOKED}。
 *
 * <p>三条不变量由实现保证、不靠调用方自律：
 * <ol>
 *   <li>一把密钥同一时刻至多一个 ACTIVE 版本（{@code sec_key_version.active_key} 唯一）；</li>
 *   <li>启用人 ≠ 录入人（策略 {@code dual_control}）；</li>
 *   <li>已入库的私密材料没有回显入口 —— 只有发放那一刻（{@link #generate}）给一次，并记 {@code sec_access_log}。</li>
 * </ol>
 */
public interface KeyRegistry {

    /** 登记一把密钥（DRAFT，尚无版本）。同一归属 × 用途 × 环境已存在时返回已有的 id */
    long register(NewKey key);

    Optional<KeyInfo> find(KeyOwner owner, KeyPurpose purpose);

    /** 录入一个版本（UPLOADED / FETCHED）：私密材料走信封加密，公开材料明文存放。新版本是 DRAFT */
    KeyVersionRef addVersion(long keyId, NewVersion version);

    /**
     * 平台生成一个对称密钥版本（GENERATED）。<b>明文只在返回值里出现这一次</b>，
     * 同时写一条 {@code sec_access_log(REVEAL_ON_ISSUE)}。
     */
    IssuedSecret generate(long keyId, int lengthBytes, String issuedBy, String reason);

    /**
     * 按指定形态生成（同上：明文只此一次、记 {@code REVEAL_ON_ISSUE}）。
     * 版本另记显示前缀与 KeySerial（{@code external_key_id} = 序列号前缀 + 生成日 yyyyMMdd + 两位版本号）。
     */
    IssuedSecret generate(long keyId, Generation generation);

    /** 测试调用通过：DRAFT → VERIFIED，证据是调用方模块的记录引用（如 {@code PAY:pay_channel_call_log:123}） */
    void markVerified(KeyVersionRef ref, String evidence, String operator);

    /**
     * 启用：VERIFIED（或策略不要求验证时 DRAFT）→ ACTIVE；原 ACTIVE → ROTATING，{@code overlap_until = now + overlap_days}。
     *
     * @param operator    启用人，必须 ≠ 录入人
     * @param approvalRef 调用方模块的复核单引用（如 {@code PAY:pay_approval:88}），可空
     */
    void enable(KeyVersionRef ref, String operator, String approvalRef);

    /** 吊销：立即失效，冻结了它的在途操作随之失败（不自动换新版本） */
    void revoke(KeyVersionRef ref, String operator, String reason);

    /** 新调用用的版本：密钥 ACTIVE 且有 ACTIVE 版本 */
    KeyVersionRef current(long keyId);

    /**
     * 冻结版本的私密材料：ACTIVE / ROTATING / RETIRED 可用（退款、查单、验旧回调），
     * DRAFT / VERIFIED / REVOKED 不可用。
     */
    byte[] materialOf(KeyVersionRef ref);

    /**
     * 验入站签名可接受的版本：ACTIVE 加上仍在重叠期内的 ROTATING（表结构说明 §3.3 轮换表），ACTIVE 在前。
     * 只给版本引用，交 {@link KeyVault} 运算，材料不离开密钥模块 —— L5 也能验签（原返回材料的
     * {@code verificationMaterials} 随 {@code SecretAppKeyResolver} 一起删除，开发计划 M3.1 / 类清单 Q-C2）
     */
    List<KeyVersionRef> verificationVersions(long keyId);

    /** 按 KeySerial（{@code external_key_id}）找版本：请求头 {@code X-Key-Serial} / {@code X-Encrypt-Serial} 用 */
    Optional<KeyVersionRef> findVersion(long keyId, String externalKeyId);

    /** 反查：版本的 KeySerial（响应头 {@code X-Key-Serial} 用，开发计划 M3.4）；没有登记序列号为空 */
    Optional<String> serialOf(KeyVersionRef ref);

    /** 指纹：唯一可回显的密钥信息 */
    String fingerprintOf(KeyVersionRef ref);

    /** 一把密钥的全部版本（不含材料），按版本号升序：运维看状态、导入前判重用（开发计划 M3.10） */
    List<VersionInfo> versions(long keyId);

    /** 材料的指纹（与登记版本时同一算法），不落库、不记日志：导入前判重用（开发计划 M3.10） */
    String fingerprintOfMaterial(byte[] material);

    /** 一个版本的元数据：没有任何材料 */
    record VersionInfo(KeyVersionRef ref, String status, String enteredBy, String fingerprint, String externalKeyId) {
    }

    /**
     * 登记一把密钥所需。
     *
     * @param counterparty 对端系统码（NEWEBPAY / WECHAT / KUAIDI100 …），可空
     * @param operator     登记人，写进 {@code sec_key_event(CREATED)}
     */
    record NewKey(KeyOwner owner, KeyPurpose purpose, String name, String counterparty,
                  String algorithm, String materialType, String operator) {
    }

    /**
     * 一个版本的材料：{@code secretMaterial}（对称密钥、私钥）与 {@code publicMaterial}（PEM）至少一个。
     *
     * @param source GENERATED / UPLOADED / FETCHED
     */
    record NewVersion(byte[] secretMaterial, String publicMaterial, String source, String externalKeyId,
                      LocalDateTime validFrom, LocalDateTime validTo, String enteredBy) {
    }

    /** 密钥元数据：不含任何材料 */
    record KeyInfo(long keyId, KeyOwner owner, KeyPurpose purpose, String status, int currentVersion,
                   LocalDateTime expiresAt) {
    }

    /**
     * 一次按形态的生成。
     *
     * @param displayPrefix 只用于显示的前缀（{@code sk_live_} / {@code sk_test_}），可空；<b>不进材料</b>
     * @param serialPrefix  KeySerial 前缀（AppSecret {@code S}、EncryptKey {@code E}），可空 = 不生成 KeySerial
     */
    record Generation(SecretFormat format, String displayPrefix, String serialPrefix, String issuedBy, String reason) {

        public Generation {
            if (format == null || issuedBy == null || issuedBy.isBlank()) {
                throw new IllegalArgumentException("format and issuedBy are required");
            }
        }
    }

    /**
     * 发放结果：明文只此一次，调用方展示后即丢弃，不得落库、不得进日志。
     *
     * @param plaintext     材料本身（不含显示前缀）
     * @param externalKeyId KeySerial；生成时没要求则为空
     */
    record IssuedSecret(KeyVersionRef ref, byte[] plaintext, String fingerprint, String externalKeyId) {

        public IssuedSecret(KeyVersionRef ref, byte[] plaintext, String fingerprint) {
            this(ref, plaintext, fingerprint, null);
        }

        @Override
        public String toString() {
            return "IssuedSecret[ref=" + ref + ", fingerprint=" + fingerprint + ", serial=" + externalKeyId + "]";
        }
    }
}
