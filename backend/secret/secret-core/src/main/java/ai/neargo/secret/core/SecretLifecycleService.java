package ai.neargo.secret.core;

import ai.neargo.secret.api.SecretException;
import ai.neargo.secret.store.entity.SecKey;
import ai.neargo.secret.store.entity.SecKeyEvent;
import ai.neargo.secret.store.entity.SecKeyVersion;
import ai.neargo.secret.store.entity.SecMasterKey;
import ai.neargo.secret.store.entity.SecSecretBlob;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 轮换与到期任务的一轮（表结构说明 §3.3 第 4 步、§3.6、§3.7 第 2–3 步）：
 * <ol>
 *   <li>重叠期满的 {@code ROTATING} 版本 → {@code RETIRED}；</li>
 *   <li>当前版本将到期（{@code warn_before_days} 内）→ 每个版本记一次 {@code EXPIRING_SOON}；已到期 → 密钥 {@code EXPIRED}；</li>
 *   <li>挂在 {@code DECRYPT_ONLY} 主密钥上的密文逐条重新包裹到当前主密钥；一条不剩时旧主密钥 → {@code RETIRED}。</li>
 * </ol>
 * 每一步单行一个小事务：一条失败不连累整轮，下一轮接着做。通知运营与商家由告警侧订阅事件表完成，不在这里发。
 */
public class SecretLifecycleService {

    static final String OPERATOR = "SYSTEM";
    private static final int REWRAP_BATCH = 200;

    private final SecretStoreMappers db;
    private final MasterKeyBook book;
    private final MasterKeyProvider provider;
    private final DefaultSecretVault vault;
    private final SecretSettings settings;
    private final TransactionTemplate tx;

    public SecretLifecycleService(SecretStoreMappers db, MasterKeyBook book, MasterKeyProvider provider,
                                  DefaultSecretVault vault, SecretSettings settings,
                                  PlatformTransactionManager txManager) {
        this.db = db;
        this.book = book;
        this.provider = provider;
        this.vault = vault;
        this.settings = settings;
        this.tx = new TransactionTemplate(txManager);
    }

    /** 一轮的结果；{@code failed} 是本轮跳过、留给下一轮的条数 */
    public record Summary(int retired, int expiringSoon, int expired, int rewrapped, int masterKeysRetired,
                          int failed) {

        public String detail() {
            return "退役%d，将到期%d，已到期%d，重新包裹%d，主密钥退役%d，失败%d".formatted(
                    retired, expiringSoon, expired, rewrapped, masterKeysRetired, failed);
        }
    }

    public Summary runOnce() {
        if (!settings.envConfigured()) {
            return new Summary(0, 0, 0, 0, 0, 0);
        }
        LocalDateTime now = settings.now();
        int[] failed = {0};
        int retired = retireOverlapped(now, failed);
        int[] expiry = expire(now, failed);
        int[] rewrap = rewrapAll(failed);
        return new Summary(retired, expiry[0], expiry[1], rewrap[0], rewrap[1], failed[0]);
    }

    private int retireOverlapped(LocalDateTime now, int[] failed) {
        int n = 0;
        for (SecKeyVersion v : db.versions().selectList(Wrappers.<SecKeyVersion>lambdaQuery()
                .eq(SecKeyVersion::getStatus, "ROTATING").le(SecKeyVersion::getOverlapUntil, now))) {
            Boolean done = tx.execute(s -> {
                int rows = db.versions().update(null, Wrappers.<SecKeyVersion>lambdaUpdate()
                        .set(SecKeyVersion::getStatus, "RETIRED")
                        .set(SecKeyVersion::getRetiredAt, now)
                        .eq(SecKeyVersion::getId, v.getId()).eq(SecKeyVersion::getStatus, "ROTATING"));
                if (rows == 1) {
                    event(v.getSecKeyId(), v.getId(), "RETIRED", null, now);
                }
                return rows == 1;
            });
            n += Boolean.TRUE.equals(done) ? 1 : 0;
        }
        return n;
    }

    /** @return {将到期提醒数, 到期数} */
    private int[] expire(LocalDateTime now, int[] failed) {
        int warned = 0;
        int expired = 0;
        for (SecKeyVersion v : db.versions().selectList(Wrappers.<SecKeyVersion>lambdaQuery()
                .eq(SecKeyVersion::getStatus, "ACTIVE").isNotNull(SecKeyVersion::getValidTo))) {
            SecKey key = db.keys().selectById(v.getSecKeyId());
            if (key == null || !settings.env().equals(key.getEnv())) {
                continue;
            }
            if (!v.getValidTo().isAfter(now)) {
                if ("ACTIVE".equals(key.getStatus())) {
                    Boolean done = tx.execute(s -> {
                        int rows = db.keys().update(null, Wrappers.<SecKey>lambdaUpdate()
                                .set(SecKey::getStatus, "EXPIRED")
                                .eq(SecKey::getId, key.getId()).eq(SecKey::getStatus, "ACTIVE"));
                        if (rows == 1) {
                            event(key.getId(), v.getId(), "EXPIRED", "valid_to " + v.getValidTo(), now);
                        }
                        return rows == 1;
                    });
                    expired += Boolean.TRUE.equals(done) ? 1 : 0;
                }
                continue;
            }
            KeyPolicy policy = KeyPolicy.of(db.policies(), key);
            boolean inWindow = !v.getValidTo().minusDays(policy.warnBeforeDays()).isAfter(now);
            boolean alreadyWarned = db.events().selectCount(Wrappers.<SecKeyEvent>lambdaQuery()
                    .eq(SecKeyEvent::getSecKeyVersionId, v.getId())
                    .eq(SecKeyEvent::getEventType, "EXPIRING_SOON")) > 0;
            if (inWindow && !alreadyWarned) {
                tx.executeWithoutResult(s -> event(key.getId(), v.getId(), "EXPIRING_SOON",
                        "valid_to " + v.getValidTo(), now));
                warned++;
            }
        }
        return new int[] {warned, expired};
    }

    /** @return {重新包裹条数, 主密钥退役数} */
    private int[] rewrapAll(int[] failed) {
        int rewrapped = 0;
        int retired = 0;
        for (SecMasterKey old : book.decryptOnly(settings.env())) {
            if (!provider.has(old.getKmsKeyId())) {
                // 旧主密钥已经不在进程里：这批密文没法重新包裹，留着并计失败 —— 运维要把它注入回来（或认定数据丢失）
                failed[0] += Math.toIntExact(db.blobs().selectCount(Wrappers.<SecSecretBlob>lambdaQuery()
                        .eq(SecSecretBlob::getSecMasterKeyId, old.getId())));
                continue;
            }
            List<SecSecretBlob> batch;
            do {
                batch = db.blobs().selectList(Wrappers.<SecSecretBlob>lambdaQuery()
                        .eq(SecSecretBlob::getSecMasterKeyId, old.getId())
                        .ne(SecSecretBlob::getCipherText, "")
                        .orderByAsc(SecSecretBlob::getId)
                        .last("LIMIT " + REWRAP_BATCH));
                int before = rewrapped;
                for (SecSecretBlob blob : batch) {
                    try {
                        tx.executeWithoutResult(s -> {
                            vault.rewrap(blob);
                            keyOfBlob(blob).ifPresent(v -> event(v.getSecKeyId(), v.getId(), "REWRAPPED",
                                    "master key #" + old.getId() + " retired", settings.now()));
                        });
                        rewrapped++;
                    } catch (SecretException e) {
                        failed[0]++;
                    }
                }
                if (rewrapped == before) {
                    break;   // 这一批一条都没成：别在同一批上空转
                }
            } while (batch.size() == REWRAP_BATCH);
            long left = db.blobs().selectCount(Wrappers.<SecSecretBlob>lambdaQuery()
                    .eq(SecSecretBlob::getSecMasterKeyId, old.getId()));
            if (left == 0) {
                book.retire(old);
                retired++;
            }
        }
        return new int[] {rewrapped, retired};
    }

    private Optional<SecKeyVersion> keyOfBlob(SecSecretBlob blob) {
        if (!"KEY_VERSION".equals(blob.getOwnerType())) {
            return Optional.empty();
        }
        return Optional.ofNullable(db.versions().selectById(blob.getOwnerId()));
    }

    private void event(long keyId, Long versionId, String type, String reason, LocalDateTime at) {
        SecKeyEvent e = new SecKeyEvent();
        e.setSecKeyId(keyId);
        e.setSecKeyVersionId(versionId);
        e.setEventType(type);
        e.setOperatorType(OPERATOR);
        e.setOperatorNo("secret-lifecycle");
        e.setReason(reason);
        e.setOccurredAt(at);
        db.events().insert(e);
    }
}
