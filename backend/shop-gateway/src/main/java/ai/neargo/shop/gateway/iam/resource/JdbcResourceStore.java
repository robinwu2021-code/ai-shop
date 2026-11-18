package ai.neargo.shop.gateway.iam.resource;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.gateway.core.end.GatewayEndRuntimes;
import ai.neargo.shop.gateway.core.resource.ApiResource;
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
            String rule = resource.rule() == null ? null : resource.rule().toString();
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
    public Set<String> disabledKeys(String endCode) {
        return new HashSet<>(jdbc.sql("SELECT method, pattern FROM sys_api_resource"
                        + " WHERE end_code = ? AND status = 'DISABLED'")
                .param(endCode)
                .query((rs, n) -> rs.getString("method") + " " + rs.getString("pattern")).list());
    }
}
