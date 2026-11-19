package ai.neargo.shop.gateway.iam.resource;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.gateway.core.end.GatewayEndRuntimes;
import ai.neargo.shop.gateway.core.end.Access;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.resource.PermRule;
import ai.neargo.shop.gateway.core.resource.PermRuleText;
import ai.neargo.shop.gateway.core.resource.ResourceStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 接口资源表的存取：{@code sys_api_resource} 与 {@code sys_api_resource_point} 只在这里读写。
 *
 * <p>运营端有自己的数据源时用它自己的，没有就用主库（ADR-061）。
 * 用 JdbcClient 手写 SQL：两张表的读写就这几句，不值得再建一套实体与 Mapper。
 */
@Component
public class JdbcResourceStore implements ResourceStore {

    private record Row(long id, String access, String ruleText, String handler, String location, boolean stale) {
    }

    private final JdbcClient jdbc;

    public JdbcResourceStore(DataSource dataSource, GatewayEndRuntimes runtimes) {
        this.jdbc = runtimes.jdbc(Realm.OPERATOR).orElseGet(() -> JdbcClient.create(dataSource));
    }

    @Override
    @Transactional
    public SyncResult syncScanned(String endCode, List<ApiResource> scanned, String appVersion) {
        Map<String, Row> existing = new HashMap<>();
        jdbc.sql("SELECT id, method, pattern, access, rule_text, handler, location, stale FROM sys_api_resource"
                        + " WHERE end_code = ? AND source = 'SCAN'")
                .param(endCode)
                .query((rs, n) -> {
                    existing.put(rs.getString("method") + " " + rs.getString("pattern"),
                            new Row(rs.getLong("id"), rs.getString("access"), rs.getString("rule_text"),
                                    rs.getString("handler"), rs.getString("location"), rs.getInt("stale") == 1));
                    return n;
                }).list();

        int inserted = 0;
        int updated = 0;
        Set<String> seen = new HashSet<>();
        for (ApiResource resource : scanned) {
            String key = resource.method() + " " + resource.pattern();
            seen.add(key);
            String access = resource.access().name();
            String rule = resource.rule() == null ? resource.basis() : resource.rule().toString();
            Row row = existing.get(key);
            if (row == null) {
                jdbc.sql("INSERT INTO sys_api_resource (end_code, method, pattern, source, location, access,"
                                + " rule_text, handler, app_version) VALUES (?, ?, ?, 'SCAN', ?, ?, ?, ?, ?)")
                        .params(endCode, resource.method(), resource.pattern(), resource.location(), access, rule,
                                resource.handler(), appVersion)
                        .update();
                long id = jdbc.sql("SELECT id FROM sys_api_resource WHERE end_code = ? AND method = ? AND pattern = ?")
                        .params(endCode, resource.method(), resource.pattern()).query(Long.class).single();
                writePoints(id, resource);
                inserted++;
            } else if (row.stale() || !row.access().equals(access) || !Objects.equals(row.ruleText(), rule)
                    || !Objects.equals(row.handler(), resource.handler())
                    || !row.location().equals(resource.location())) {
                // status 与 updated_by 是人改的，这里不碰
                jdbc.sql("UPDATE sys_api_resource SET location = ?, access = ?, rule_text = ?, handler = ?, stale = 0,"
                                + " app_version = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
                        .params(resource.location(), access, rule, resource.handler(), appVersion, row.id())
                        .update();
                jdbc.sql("DELETE FROM sys_api_resource_point WHERE resource_id = ?").param(row.id()).update();
                writePoints(row.id(), resource);
                updated++;
            }
        }
        int stale = 0;
        for (Map.Entry<String, Row> entry : existing.entrySet()) {
            if (!seen.contains(entry.getKey()) && !entry.getValue().stale()) {
                jdbc.sql("UPDATE sys_api_resource SET stale = 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
                        .param(entry.getValue().id()).update();
                stale++;
            }
        }
        return new SyncResult(inserted, updated, stale);
    }

    private void writePoints(long resourceId, ApiResource resource) {
        if (resource.rule() == null) {
            return;
        }
        for (String code : new java.util.LinkedHashSet<>(resource.rule().codes())) {
            jdbc.sql("INSERT INTO sys_api_resource_point (resource_id, perm_code) VALUES (?, ?)")
                    .params(resourceId, code).update();
        }
    }

    @Override
    public List<ApiResource> loadManual(String endCode) {
        Map<Long, List<String>> codes = new HashMap<>();
        jdbc.sql("SELECT p.resource_id, p.perm_code FROM sys_api_resource_point p JOIN sys_api_resource r"
                        + " ON r.id = p.resource_id WHERE r.end_code = ? AND r.source = 'MANUAL' ORDER BY p.id")
                .param(endCode)
                .query((rs, n) -> codes.computeIfAbsent(rs.getLong(1), k -> new java.util.ArrayList<>())
                        .add(rs.getString(2))).list();
        return jdbc.sql("SELECT id, method, pattern, location, target_path, access FROM sys_api_resource"
                        + " WHERE end_code = ? AND source = 'MANUAL' AND stale = 0")
                .param(endCode)
                .query((rs, n) -> {
                    Access access = Access.valueOf(rs.getString("access"));
                    List<String> required = codes.getOrDefault(rs.getLong("id"), List.of());
                    // 录入的链接：列出的权限码全部都要有。要「任一即可」就录成多条功能点对应的一个码
                    PermRule rule = access == Access.POINT
                            ? PermRule.all(required.stream().map(PermRule::code).toList()) : null;
                    return new ApiResource(rs.getString("method"), rs.getString("pattern"), rs.getString("location"),
                            access, rule, null, rs.getString("target_path"));
                }).list();
    }

    @Override
    public List<ApiResource> loadEffective(String endCode) {
        return jdbc.sql("SELECT method, pattern, location, access, rule_text, handler, adjusted_access, adjusted_rule"
                        + " FROM sys_api_resource WHERE end_code = ? AND source = 'SCAN' AND stale = 0")
                .param(endCode)
                .query((rs, n) -> {
                    String adjusted = rs.getString("adjusted_access");
                    Access access = Access.valueOf(adjusted == null ? rs.getString("access") : adjusted);
                    String text = adjusted == null ? rs.getString("rule_text") : rs.getString("adjusted_rule");
                    String method = rs.getString("method");
                    String pattern = rs.getString("pattern");
                    String handler = rs.getString("handler");
                    if (access != Access.POINT) {
                        // 不带权限式的行，rule_text 里放的是「凭什么是这个类别」
                        return ApiResource.local(method, pattern, access, handler, text);
                    }
                    // 式子读不懂就按「留给入口判」：不猜，也不因为库里一行写坏了而放行
                    return PermRuleText.parse(text)
                            .map(rule -> new ApiResource(method, pattern, ApiResource.LOCAL, Access.POINT, rule, handler))
                            .orElseGet(() -> ApiResource.local(method, pattern, Access.DEFER, handler, null));
                }).list();
    }

    @Override
    @Transactional
    public void adjust(long id, String access, String rule, String operator) {
        jdbc.sql("UPDATE sys_api_resource SET adjusted_access = ?, adjusted_rule = ?, adjusted_by = ?,"
                        + " adjusted_at = CURRENT_TIMESTAMP, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
                .params(access, access == null ? null : rule, operator, id).update();
    }

    @Override
    @Transactional
    public int seedManual(String endCode, List<ApiResource> seeds) {
        int inserted = 0;
        for (ApiResource seed : seeds) {
            Integer present = jdbc.sql("SELECT COUNT(*) FROM sys_api_resource WHERE end_code = ? AND method = ?"
                    + " AND pattern = ?").params(endCode, seed.method(), seed.pattern()).query(Integer.class).single();
            if (present != null && present > 0) {
                continue;
            }
            jdbc.sql("INSERT INTO sys_api_resource (end_code, method, pattern, source, location, target_path, access,"
                            + " rule_text) VALUES (?, ?, ?, 'MANUAL', ?, ?, ?, ?)")
                    .params(endCode, seed.method(), seed.pattern(), seed.location(), seed.targetPath(),
                            seed.access().name(), seed.rule() == null ? null : seed.rule().toString())
                    .update();
            long id = jdbc.sql("SELECT id FROM sys_api_resource WHERE end_code = ? AND method = ? AND pattern = ?")
                    .params(endCode, seed.method(), seed.pattern()).query(Long.class).single();
            writePoints(id, seed);
            inserted++;
        }
        return inserted;
    }

    private static final String ADMIN_COLUMNS = "id, end_code, method, pattern, source, location, target_path,"
            + " access, rule_text, handler, status, stale, app_version, updated_by, updated_at,"
            + " adjusted_access, adjusted_rule, adjusted_by, adjusted_at";

    private static AdminRow adminRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdminRow(rs.getLong("id"), rs.getString("end_code"), rs.getString("method"),
                rs.getString("pattern"), rs.getString("source"), rs.getString("location"),
                rs.getString("target_path"), rs.getString("access"), rs.getString("rule_text"),
                rs.getString("handler"), rs.getString("status"), rs.getInt("stale") == 1,
                rs.getString("app_version"), rs.getString("updated_by"),
                rs.getObject("updated_at", java.time.LocalDateTime.class),
                rs.getString("adjusted_access"), rs.getString("adjusted_rule"), rs.getString("adjusted_by"),
                rs.getObject("adjusted_at", java.time.LocalDateTime.class));
    }

    /** 拼筛选条件；参数按顺序放进 args。 */
    private static String where(AdminQuery query, List<Object> args) {
        StringBuilder sql = new StringBuilder(" WHERE 1 = 1");
        if (hasText(query.endCode())) {
            sql.append(" AND end_code = ?");
            args.add(query.endCode());
        }
        if (hasText(query.source())) {
            sql.append(" AND source = ?");
            args.add(query.source());
        }
        if (hasText(query.status())) {
            sql.append(" AND status = ?");
            args.add(query.status());
        }
        if (hasText(query.keyword())) {
            sql.append(" AND (pattern LIKE ? OR rule_text LIKE ?)");
            String like = "%" + query.keyword().trim() + "%";
            args.add(like);
            args.add(like);
        }
        return sql.toString();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @Override
    public List<AdminRow> page(AdminQuery query, long page, long size) {
        List<Object> args = new java.util.ArrayList<>();
        String where = where(query, args);
        args.add(size);
        args.add((page - 1) * size);
        return jdbc.sql("SELECT " + ADMIN_COLUMNS + " FROM sys_api_resource" + where
                        + " ORDER BY pattern, method, id LIMIT ? OFFSET ?")
                .params(args).query((rs, n) -> adminRow(rs)).list();
    }

    @Override
    public long count(AdminQuery query) {
        List<Object> args = new java.util.ArrayList<>();
        String where = where(query, args);
        return jdbc.sql("SELECT COUNT(*) FROM sys_api_resource" + where).params(args).query(Long.class).single();
    }

    @Override
    public AdminCounts counts() {
        return jdbc.sql("SELECT COUNT(*) AS total,"
                        + " COALESCE(SUM(CASE WHEN status = 'DISABLED' THEN 1 ELSE 0 END), 0) AS disabled,"
                        + " COALESCE(SUM(CASE WHEN stale = 1 THEN 1 ELSE 0 END), 0) AS stale_rows,"
                        + " COALESCE(SUM(CASE WHEN source = 'MANUAL' THEN 1 ELSE 0 END), 0) AS manual"
                        + " FROM sys_api_resource")
                .query((rs, n) -> new AdminCounts(rs.getLong("total"), rs.getLong("disabled"),
                        rs.getLong("stale_rows"), rs.getLong("manual"))).single();
    }

    @Override
    public java.util.Optional<AdminRow> find(long id) {
        return jdbc.sql("SELECT " + ADMIN_COLUMNS + " FROM sys_api_resource WHERE id = ?").param(id)
                .query((rs, n) -> adminRow(rs)).optional();
    }

    @Override
    public void setStatus(long id, String status, String operator) {
        jdbc.sql("UPDATE sys_api_resource SET status = ?, updated_by = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?")
                .params(status, operator, id).update();
    }

    @Override
    public Set<String> disabledKeys(String endCode) {
        return new HashSet<>(jdbc.sql("SELECT method, pattern FROM sys_api_resource"
                        + " WHERE end_code = ? AND status = 'DISABLED'")
                .param(endCode)
                .query((rs, n) -> rs.getString("method") + " " + rs.getString("pattern")).list());
    }
}
