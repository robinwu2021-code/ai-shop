package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.zip.GZIPInputStream;

/**
 * 灌入全国行政区划四级数据（ADR-013 阶段一）。
 *
 * <p><b>为什么是 Java 迁移，而不是 INSERT 写进 .sql</b>：四级共 44703 行。
 * {@code gen-test-schema.py} 会把非 SELECT 的 INSERT 原样抄进 {@code schema-test.sql}
 * 当种子 —— 那份生成物会涨到几 MB，且每个 Spring 测试上下文都要灌一遍。
 * 而参考数据不是 schema：把它写成 4 万行 SQL 字面量，之后每次看 schema diff
 * 都得先翻过这 4 万行。
 *
 * <p>Java 迁移同样受 Flyway 版本管理（新库照样能建起来、版本表里有记录），
 * 只是不出现在 {@code V*.sql} 的 glob 里 —— 正好是我们要的。
 *
 * <p><b>数据来源</b>：国家统计局统计用区划代码，经
 * {@code modood/Administrative-divisions-of-China} 整理（截至 2023-06-30）。
 * ⚠️ 国家统计局自 2024-10 起不再公开具体代码，所以这份数据<b>不会再更新</b>；
 * 且<b>不含港澳台</b>（31 个省级单位）。要用更新的口径得换源，见 ADR-013 §6.3。
 */
public class V31__seed_regions extends BaseJavaMigration {

    private static final String RESOURCE = "/db/data/regions.csv.gz";
    /** 一批 2000 行：再大对 MySQL 的 max_allowed_packet 不友好，再小则往返太多次 */
    private static final int BATCH = 2000;

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();

        /*
         * **幂等**：表里已经有行就整个跳过。
         *
         * Flyway 自己会保证一个版本只跑一次，但这条守卫防的是另一种情况 ——
         * 有人在 baseline 之后手工灌过、或从别的环境导过一份。
         * 没有它的话这里会撞唯一键，而报出来的是「迁移失败」，
         * 得有人进库看一眼才知道其实数据早就在了。
         */
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sys_region")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        String sql = "INSERT INTO sys_region "
                + "(region_code, parent_code, level, name, enabled, sort, tenant_no, "
                + " created_at, created_by, updated_at, updated_by, version, deleted) "
                + "VALUES (?, ?, ?, ?, 1, 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0)";

        try (InputStream in = V31__seed_regions.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "找不到区划数据 " + RESOURCE + " —— 它是这条迁移的输入，缺了就该失败，"
                                + "而不是建出一张空表让「按区选范围」在界面上永远是空的");
            }
            try (BufferedReader reader = new BufferedReader(
                         new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
                 PreparedStatement ps = conn.prepareStatement(sql)) {

                reader.readLine();   // 表头
                int inBatch = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    String[] c = line.split(",", -1);
                    if (c.length < 4) {
                        throw new IllegalStateException("区划数据格式不对，这一行只有 "
                                + c.length + " 列：" + line);
                    }
                    ps.setString(1, c[0]);
                    // 省级没有上级：存 NULL 而不是空串 —— 空串会让「取顶层」的查询
                    // 既要判 IS NULL 又要判 = ''，两处漏一处就少半棵树
                    ps.setString(2, c[1].isEmpty() ? null : c[1]);
                    ps.setString(3, c[2]);
                    ps.setString(4, c[3]);
                    ps.addBatch();
                    if (++inBatch % BATCH == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
        }
    }
}
