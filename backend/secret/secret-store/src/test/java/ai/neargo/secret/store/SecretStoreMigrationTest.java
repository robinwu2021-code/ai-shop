package ai.neargo.secret.store;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.secret.store.entity.SecKeyPolicy;
import ai.neargo.secret.store.mapper.SecretMappers;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** db/secret V1、V2 原样执行（默认 H2 MySQL 模式；-Dsecret.mysql.url 时真 MySQL），七张表齐、历史记在 sec_flyway_history，实体与列对得上 */
class SecretStoreMigrationTest {

    @Test
    @DisplayName("★★ db/secret 原样跑通：七张 sec_* 表 + sec_flyway_history，DDL 默认值生效")
    void migrationCreatesSevenTables() throws Exception {
        var store = SecretStoreTestSupport.freshStore();

        assertThat(store.migrationsApplied()).as("V1 七表 + V2 存储分档").isEqualTo(2);
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
        var v = new ai.neargo.secret.store.entity.SecKeyVersion();
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
}
