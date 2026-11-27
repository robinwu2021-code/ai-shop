package ai.neargo.secret.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.secret.store.entity.SecKeyPolicy;
import ai.neargo.secret.store.entity.SecKeyVersion;
import ai.neargo.secret.store.mapper.SecretMappers;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/** db/secret V1–V3 原样执行（默认 H2 MySQL 模式；-Dsecret.mysql.url 时真 MySQL），七张表齐、历史记在 sec_flyway_history，实体与列对得上 */
class SecretStoreMigrationTest {

    @Test
    @DisplayName("★★ db/secret 原样跑通：七张 sec_* 表 + sec_flyway_history，DDL 默认值生效")
    void migrationCreatesSevenTables() throws Exception {
        var store = SecretStoreTestSupport.freshStore();

        assertThat(store.migrationsApplied()).as("V1 七表 + V2 存储分档 + V3 摘要档").isEqualTo(3);
        List<String> tables = new ArrayList<>();
        try (Connection c = store.dataSource().getConnection();
             ResultSet rs = c.getMetaData().getTables(c.getCatalog(), null, "sec_%", new String[] {"TABLE"})) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        }
        assertThat(tables).containsExactlyInAnyOrder("sec_flyway_history", "sec_master_key", "sec_secret_blob",
                "sec_key_policy", "sec_key", "sec_key_version", "sec_key_event", "sec_access_log");

        // 每个实体都能查自己的表（列名与实体字段对得上），并取回 DDL 默认值
        for (Class<?> m : SecretMappers.class.getDeclaredClasses()) {
            assertThat(((com.baomidou.mybatisplus.core.mapper.BaseMapper<?>) store.session().getMapper(m))
                    .selectList(null)).as(m.getSimpleName()).isEmpty();
        }
        SecKeyPolicy p = new SecKeyPolicy();
        p.setNamespace("PAY");
        p.setOwnerType("PARTNER_APP");
        p.setEnv("SANDBOX");
        store.mapper(SecretMappers.KeyPolicyMapper.class).insert(p);
        SecKeyPolicy back = store.mapper(SecretMappers.KeyPolicyMapper.class).selectById(p.getId());
        assertThat(back.getOverlapDays()).isEqualTo(7);
        assertThat(back.getWarnBeforeDays()).isEqualTo(30);
        assertThat(back.getDualControl()).isTrue();
    }

    @Test
    @DisplayName("★★ V2：版本记录自己的存储档（默认 L2）与只用于显示的前缀")
    void v2AddsStorageTierAndDisplayPrefix() throws Exception {
        var store = SecretStoreTestSupport.freshStore();
        try (Connection c = store.dataSource().getConnection();
             ResultSet rs = c.getMetaData().getColumns(c.getCatalog(), null, "sec_key_version", null)) {
            List<String> columns = new ArrayList<>();
            while (rs.next()) {
                columns.add(rs.getString("COLUMN_NAME").toLowerCase());
            }
            assertThat(columns).contains("storage_tier", "display_prefix");
        }
        var v = new SecKeyVersion();
        v.setSecKeyId(1L);
        v.setVersionIndex(1);
        v.setStatus("DRAFT");
        v.setPublicMaterial("-----BEGIN PUBLIC KEY-----");
        v.setFingerprint("f");
        v.setSource("UPLOADED");
        v.setEnteredBy("ops-alice");
        store.mapper(SecretMappers.KeyVersionMapper.class).insert(v);
        assertThat(store.mapper(SecretMappers.KeyVersionMapper.class).selectById(v.getId()).getStorageTier())
                .as("没写档位的行取 DDL 默认值").isEqualTo("L2");
    }

    @Test
    @DisplayName("★★ V3：DIGEST 档的版本只有摘要也插得进（放宽 ck_sec_key_version_1），摘要与凭据号各自唯一")
    void v3AcceptsDigestOnlyVersionsWithUniqueDigestAndCredentialKey() {
        var store = SecretStoreTestSupport.freshStore();
        var versions = store.mapper(SecretMappers.KeyVersionMapper.class);
        versions.insert(digestVersion(1, "a".repeat(64), "PLATFORM|OPS_STAFF|k1"));

        var back = versions.selectList(null).getFirst();
        assertThat(back.getMaterialDigest()).isEqualTo("a".repeat(64));
        assertThat(back.getLabel()).isEqualTo("cli");
        assertThat(back.getScope()).isEqualTo("READ_ONLY");
        assertThatThrownBy(() -> versions.insert(digestVersion(2, "a".repeat(64), "PLATFORM|OPS_STAFF|k2")))
                .as("同一摘要不能登记两次").isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> versions.insert(digestVersion(3, "b".repeat(64), "PLATFORM|OPS_STAFF|k1")))
                .as("同一凭据号不能登记两次").isInstanceOf(DuplicateKeyException.class);
        var bare = digestVersion(4, null, null);
        assertThatThrownBy(() -> versions.insert(bare)).as("密文、公开材料、摘要至少一个")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private static SecKeyVersion digestVersion(int index, String digest, String credentialKey) {
        var v = new SecKeyVersion();
        v.setSecKeyId(1L);
        v.setVersionIndex(index);
        v.setStatus("ACTIVE");
        v.setFingerprint("f");
        v.setSource("GENERATED");
        v.setEnteredBy("ops-alice");
        v.setStorageTier("DIGEST");
        v.setMaterialDigest(digest);
        v.setCredentialKey(credentialKey);
        v.setLabel("cli");
        v.setScope("READ_ONLY");
        return v;
    }
}
