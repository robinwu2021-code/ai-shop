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
 * 灌入**村级**行政区划（第五级：村委会 / 居委会），62 万行。
 *
 * <p>接在 {@link V31__seed_regions} 之后：那一条灌的是省/市/区县/街道四级共 44703 行，
 * 这一条把树补到国标的最后一层。两条分开而不是合并重灌 ——
 * 四级数据早已在生产上跑着，重灌等于把 4 万行删了再写一遍，
 * 而 {@code sys_region.region_code} 被社区、经营范围引用着。
 *
 * <p><b>为什么不是「爬」下来的</b>：国家统计局自 2024-10 起
 * <b>不再公开具体代码</b>（只保留编制规则），目录页现在直接 403。
 * 最后一份公开数据是 2023 年度（截至 2023-06-30），
 * 与 V31 那四级出自同一份快照，经 {@code modood/Administrative-divisions-of-China} 整理。
 * 两份数据的父子关系已逐条核对：村级引用的 41352 个街道与库里的 41352 个
 * <b>完全一致，两侧孤儿都是 0</b>。
 *
 * <p><b>不含港澳台</b>，与四级同一口径。
 *
 * <p><b>解析器是朴素的 {@code split(",")}</b>（沿用 V31）——
 * 这依赖「名称里没有逗号」。生成资源文件时用 csv 模块逐行验过：62 万行里
 * 含逗号的名称是 0 条。换数据源时要重新验这一条，否则一个带逗号的村名
 * 会让整行错位，而错位的表现是「某个村的名字变成了一串数字」。
 */
public class V181__seed_villages extends BaseJavaMigration {

    private static final String RESOURCE = "/db/data/villages.csv.gz";
    private static final String LEVEL = "VILLAGE";
    /** 一批 2000 行：与 V31 同口径 —— 再大对 max_allowed_packet 不友好，再小则往返太多 */
    private static final int BATCH = 2000;

    @Override
    public void migrate(Context context) throws Exception {
        Connection conn = context.getConnection();

        /*
         * **幂等，但判据是「有没有村级」而不是「表空不空」**。
         *
         * V31 那条用的是「表里有行就跳过」，放在这里会永远跳过 ——
         * 表里本来就有 4 万行四级数据。这类复制粘贴来的守卫最难发现：
         * 迁移「成功」了，只是一行没写。
         */
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM sys_region WHERE level = '" + LEVEL + "'")) {
            if (rs.next() && rs.getInt(1) > 0) {
                return;
            }
        }

        String sql = "INSERT INTO sys_region "
                + "(region_code, parent_code, level, name, enabled, sort, tenant_no, "
                + " created_at, created_by, updated_at, updated_by, version, deleted) "
                + "VALUES (?, ?, ?, ?, 1, 0, 'MAIN', NOW(), 'SYSTEM', NOW(), 'SYSTEM', 0, 0)";

        int written = 0;
        try (InputStream in = V181__seed_villages.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "找不到村级区划数据 " + RESOURCE + " —— 它是这条迁移的输入，缺了就该失败，"
                                + "而不是让区划树静默地停在街道那一层");
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
                        throw new IllegalStateException("村级数据格式不对，这一行只有 "
                                + c.length + " 列：" + line);
                    }
                    ps.setString(1, c[0]);
                    // 村级一定有上级（街道），没有就是数据坏了 —— 与省级的 NULL 是两回事
                    if (c[1].isEmpty()) {
                        throw new IllegalStateException("村级缺上级街道码：" + line);
                    }
                    ps.setString(2, c[1]);
                    ps.setString(3, c[2]);
                    ps.setString(4, c[3]);
                    ps.addBatch();
                    written++;
                    if (++inBatch % BATCH == 0) {
                        ps.executeBatch();
                    }
                }
                ps.executeBatch();
            }
        }

        /*
         * **灌完立刻验一次父子关系**，而不是等有人在界面上点开一个街道
         * 发现下面是空的。
         *
         * 挂不上街道的村在库里看不出异常：它有码、有名、有 level，
         * 只是 `children(街道码)` 永远查不到它 —— 而这种「数据在、就是查不出来」
         * 的故障，从界面上只表现为「这个街道没有社区」。
         */
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT COUNT(*) FROM sys_region v LEFT JOIN sys_region s"
                             + " ON s.region_code = v.parent_code AND s.level = 'STREET'"
                             + " WHERE v.level = '" + LEVEL + "' AND s.id IS NULL")) {
            if (rs.next() && rs.getInt(1) > 0) {
                throw new IllegalStateException(
                        "有 " + rs.getInt(1) + " 条村级区划挂不上任何街道 —— "
                                + "父子关系对不上，这批数据不能留在库里");
            }
        }

        if (written == 0) {
            throw new IllegalStateException("村级数据一行都没写入 —— 资源文件是空的？");
        }
    }
}
