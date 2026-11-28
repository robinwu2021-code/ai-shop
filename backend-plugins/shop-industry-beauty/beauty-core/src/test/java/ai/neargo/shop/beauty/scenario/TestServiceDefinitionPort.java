package ai.neargo.shop.beauty.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.ServiceDefinitionPort;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 测试版服务定义端口（TDD-美业域迁移新库 §2.8 P5）：商品域的真实实现在宿主，美业测试用 JDBC 读写测试库里按定稿结构建的
 * {@code prd_service_definition} / {@code prd_fulfillment_requirement}，语义照真实实现：一个版本是一组行，覆盖行合并默认行，
 * 期望版本不符冲突、同一操作号重放回原版本、发布带过上一版的其余行。
 *
 * <p>简化：测试里的服务商品都只有一个 SKU、商品号由 {@link #goodsOf} 给出（默认 {@code G}），所以按 SKU 发布一律写默认行；
 * 不写商品行的时长镜像（美业测试的商品事实是桩）。
 */
public class TestServiceDefinitionPort implements ServiceDefinitionPort {
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private final JdbcTemplate sql;
    private final java.util.function.Function<String, String> goodsOf;
    private final JsonMapper json = JsonMapper.builder().build();

    public TestServiceDefinitionPort(JdbcTemplate sql) { this(sql, sku -> "G"); }

    public TestServiceDefinitionPort(JdbcTemplate sql, java.util.function.Function<String, String> goodsOf) {
        this.sql = sql;
        this.goodsOf = goodsOf;
    }

    /** 按交易库设计 DDL 建美业用到的交易表（{@link BeautySchemaMigrationIT#TRADE_TABLES}），已建过的跳过。 */
    public static void tradeTables(JdbcTemplate sql) {
        try {
            for (String statement : BeautyIndustryDatabaseIT.statements("db/n_trade/V1__trade_design.sql"))
                for (String table : BeautySchemaMigrationIT.TRADE_TABLES)
                    if (statement.startsWith("CREATE TABLE `" + table + "`"))
                        sql.execute(statement.replaceFirst("CREATE TABLE", "CREATE TABLE IF NOT EXISTS"));
        } catch (java.io.IOException ex) {
            throw new AssertionError(ex);
        }
    }

    /** 直接写一版（测试种子）：默认行，带给定的要求编号。返回版本号。 */
    public static int seed(JdbcTemplate sql, String entity, String store, String goods, int duration, int before,
            int after, List<String> notes, List<Requirement> requirements, Instant publishedAt) {
        int version = current(sql, goods) + 1;
        var port = new TestServiceDefinitionPort(sql);
        port.insertRow(entity, store, goods, DEFAULT_SKU, version, new Timing(duration, before, after),
                List.of(new Step("SERVICE", 0)), notes, null, "SEED", publishedAt);
        for (var r : requirements) {
            port.insertRequirement(entity, store, goods, DEFAULT_SKU, version, r, r.requirementNo());
        }
        return version;
    }

    @Override
    public synchronized Definition publish(Publish c) {
        String goods = goodsOf.apply(c.skuNo());
        String rowSku = Boolean.TRUE.equals(c.skuOverride()) ? c.skuNo() : DEFAULT_SKU;
        if (c.sourceRef() != null) {
            var replay = sql.queryForList("SELECT definition_version FROM prd_service_definition WHERE goods_no=? "
                    + "AND source_ref=? LIMIT 1", Integer.class, goods, c.sourceRef());
            if (!replay.isEmpty()) { return view(c.entityNo(), c.storeNo(), goods, replay.get(0), c.skuNo()); }
        }
        int current = current(sql, goods);
        if (c.expectedVersion() != current) { throw BizException.of(ErrorCode.CONFLICT); }
        int next = current + 1;
        var now = Instant.now();
        if (current > 0) {
            sql.update("INSERT INTO prd_service_definition (tenant_no, entity_no, store_no, goods_no, sku_no, "
                    + "definition_version, duration_min, buffer_before_min, buffer_after_min, steps, notes, "
                    + "schema_version, snapshot_hash, status, source_ref, published_by, published_at) "
                    + "SELECT tenant_no, entity_no, store_no, goods_no, sku_no, ?, duration_min, buffer_before_min, "
                    + "buffer_after_min, steps, notes, schema_version, snapshot_hash, status, ?, ?, ? "
                    + "FROM prd_service_definition WHERE goods_no=? AND definition_version=? AND sku_no<>?",
                    next, c.sourceRef(), c.actor(), Timestamp.from(now), goods, current, rowSku);
            for (var old : requirements(goods, current)) {
                if (!old.skuNo().equals(rowSku)) {
                    insertRequirement(c.entityNo(), c.storeNo(), goods, old.skuNo(), next, old.requirement(), null);
                }
            }
        }
        insertRow(c.entityNo(), c.storeNo(), goods, rowSku, next, c.timing(), c.steps(), c.notes(), c.sourceRef(),
                c.actor(), now);
        if (c.requirements() != null) {
            for (var r : c.requirements()) {
                insertRequirement(c.entityNo(), c.storeNo(), goods, rowSku, next, r, null);
            }
        }
        return view(c.entityNo(), c.storeNo(), goods, next, c.skuNo());
    }

    @Override
    public Definition current(String entityNo, String storeNo, String skuNo) {
        String goods = goodsOf.apply(skuNo);
        int version = current(sql, goods);
        return version == 0 ? null : view(entityNo, storeNo, goods, version, skuNo);
    }

    @Override
    public Definition byVersion(String entityNo, String storeNo, String skuNo, int definitionVersion) {
        return view(entityNo, storeNo, goodsOf.apply(skuNo), definitionVersion, skuNo);
    }

    @Override
    public List<Definition> list(String entityNo, String storeNo, Instant at) {
        List<Definition> result = new ArrayList<>();
        for (var head : sql.queryForList("SELECT goods_no, MAX(definition_version) AS v FROM prd_service_definition "
                + "WHERE entity_no=? AND store_no=? AND published_at<=? GROUP BY goods_no ORDER BY goods_no",
                entityNo, storeNo, Timestamp.from(at))) {
            String goods = (String) head.get("goods_no");
            int version = ((Number) head.get("v")).intValue();
            for (String sku : sql.queryForList("SELECT sku_no FROM prd_service_definition WHERE goods_no=? "
                    + "AND definition_version=? ORDER BY sku_no", String.class, goods, version)) {
                result.add(view(entityNo, storeNo, goods, version, sku));
            }
        }
        return result;
    }

    private static int current(JdbcTemplate sql, String goods) {
        Integer version = sql.queryForObject("SELECT COALESCE(MAX(definition_version), 0) FROM prd_service_definition "
                + "WHERE goods_no=?", Integer.class, goods);
        return version == null ? 0 : version;
    }

    private Definition view(String entity, String store, String goods, int version, String skuNo) {
        var rows = sql.queryForList("SELECT * FROM prd_service_definition WHERE goods_no=? AND definition_version=? "
                + "AND entity_no=? AND (store_no=? OR store_no IS NULL)", goods, version, entity, store);
        Map<String, Object> row = null;
        Map<String, Object> base = null;
        for (var r : rows) {
            if (skuNo.equals(r.get("sku_no"))) { row = r; }
            if ("".equals(r.get("sku_no"))) { base = r; }
        }
        if (row == null) { row = base; }
        if (row == null || base == null) { return null; }
        boolean override = !"".equals(row.get("sku_no"));
        String own = (String) row.get("sku_no");
        var reqs = requirements(goods, version);
        var mine = reqs.stream().filter(r -> r.skuNo().equals(own)).map(Stored::requirement).toList();
        var applied = override && mine.isEmpty()
                ? reqs.stream().filter(r -> r.skuNo().isEmpty()).map(Stored::requirement).toList() : mine;
        String steps = text(row.get("steps") != null ? row.get("steps") : base.get("steps"));
        String notes = text(row.get("notes") != null ? row.get("notes") : base.get("notes"));
        return new Definition(entity, store, goods, skuNo, version, override,
                new Timing(((Number) row.get("duration_min")).intValue(),
                        ((Number) row.get("buffer_before_min")).intValue(),
                        ((Number) row.get("buffer_after_min")).intValue()),
                List.of(json.readValue(steps == null ? "[]" : steps, Step[].class)),
                List.of(json.readValue(notes == null ? "[]" : notes, String[].class)),
                applied,
                (String) row.get("status"), (String) row.get("snapshot_hash"),
                ((LocalDateTime) row.get("published_at")).toInstant(ZoneOffset.UTC));
    }

    private static String text(Object value) { return value == null ? null : value.toString(); }

    /** 一条要求连同它所属的行（空串 = 默认行）。 */
    private record Stored(String skuNo, Requirement requirement) { }

    private List<Stored> requirements(String goods, int version) {
        return sql.query("SELECT * FROM prd_fulfillment_requirement WHERE goods_no=? AND definition_version=? "
                + "ORDER BY sku_no, sequence_no, id", (rs, i) -> new Stored(
                rs.getString("sku_no") == null ? DEFAULT_SKU : rs.getString("sku_no"),
                new Requirement(rs.getString("requirement_no"), rs.getString("role_code"),
                        rs.getString("resource_type"), rs.getString("skill_goods_no"), rs.getInt("qty"),
                        (Integer) rs.getObject("sequence_no"), (Integer) rs.getObject("offset_min"),
                        (Integer) rs.getObject("duration_min"), rs.getString("selection_rule"))), goods, version);
    }

    private void insertRow(String entity, String store, String goods, String sku, int version, Timing timing,
            List<Step> steps, List<String> notes, String sourceRef, String actor, Instant publishedAt) {
        sql.update("INSERT INTO prd_service_definition (tenant_no, entity_no, store_no, goods_no, sku_no, "
                + "definition_version, duration_min, buffer_before_min, buffer_after_min, steps, notes, schema_version, "
                + "status, source_ref, published_by, published_at) VALUES ('MAIN',?,?,?,?,?,?,?,?,?,?,1,'ACTIVE',?,?,?)",
                entity, store, goods, sku, version, timing.durationMin(), timing.bufferBeforeMin(),
                timing.bufferAfterMin(), steps == null ? null : json.writeValueAsString(steps),
                notes == null ? null : json.writeValueAsString(notes), sourceRef, actor,
                Timestamp.valueOf(LocalDateTime.ofInstant(publishedAt, ZoneOffset.UTC)));
    }

    /** 写一条要求；{@code number} 为空时生成。 */
    private void insertRequirement(String entity, String store, String goods, String sku, int version, Requirement r,
            String number) {
        sql.update("INSERT INTO prd_fulfillment_requirement (tenant_no, entity_no, requirement_no, goods_no, sku_no, "
                + "store_no, definition_version, requirement_type, role_code, resource_type, skill_goods_no, qty, "
                + "sequence_no, offset_min, duration_min, selection_rule) "
                + "VALUES ('MAIN',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", entity,
                number != null ? number : "PFQT" + SEQUENCE.incrementAndGet(), goods, sku.isEmpty() ? null : sku,
                store, version, r.roleCode() != null ? "ROLE" : "RESOURCE", r.roleCode(), r.resourceType(),
                r.skillGoodsNo(), r.qty(), r.sequenceNo(), r.offsetMin(), r.durationMin(), r.selectionRule());
    }
}
