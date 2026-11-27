package ai.neargo.commerce.persistence;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.core.catalog.*;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.*;
import java.util.*;
import java.util.function.*;

import javax.sql.DataSource;

/**
 * Conservative tenant catalogue serialization. Current reads acquire all tenant catalogue rows in
 * fixed table order. Only changed rows are written, with tenant+business-key+version predicates.
 * Requires shared JDBC UoW for writes.
 */
public final class JdbcCatalogRepository implements CatalogRepository {
    private final DataSource ds;
    private final JdbcTemplate dictionary;
    private final CatalogChangeJournal journal;
    private final JdbcTemplate jdbc;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<TreeMap<String, String>> NAMES = new TypeReference<>() {};

    public JdbcCatalogRepository(DataSource ds) {
        this(ds, null);
    }

    public JdbcCatalogRepository(DataSource ds, DataSource foundation) {
        dictionary = foundation == null ? null : new JdbcTemplate(foundation);
        this.ds = Objects.requireNonNull(ds);
        jdbc = new JdbcTemplate(ds);
        journal = foundation == null ? null : new JdbcMallCatalogChanges(jdbc, foundation);
    }

    @Override
    public CatalogChangeJournal changeJournal() { return journal; }

    private List<Map<String, Object>> rows(String table, String tenant, CatalogLockMode mode) {
        return jdbc.queryForList(
                "SELECT * FROM "
                        + table
                        + " WHERE tenant_no=? AND deleted=0 ORDER BY id"
                        + switch (mode) {
                            case NONE -> "";
                            case SHARED -> " FOR SHARE";
                            case EXCLUSIVE -> " FOR UPDATE";
                        },
                tenant);
    }

    private static String str(Map<String, Object> r, String k) {
        var v = r.get(k);
        return v == null ? null : v.toString();
    }

    private static long num(Map<String, Object> r, String k) {
        return ((Number) r.get(k)).longValue();
    }

    private static int integer(Map<String, Object> r, String k) {
        return (int) num(r, k);
    }

    private static boolean flag(Map<String, Object> r, String k) {
        var v = r.get(k);
        return v instanceof Boolean b ? b : ((Number) v).intValue() != 0;
    }

    private static Long nullableLong(Map<String, Object> r, String k) {
        return r.get(k) == null ? null : num(r, k);
    }

    private static List<String> strings(String json) {
        if (json == null) return List.of();
        var body = json.trim();
        if (body.equals("[]")) return List.of();
        if (!body.startsWith("[") || !body.endsWith("]"))
            throw new IllegalStateException("catalog scope must be a JSON string array");
        return Arrays.stream(body.substring(1, body.length() - 1).split(","))
                .map(String::trim)
                .map(
                        s -> {
                            if (!s.matches("\"[A-Za-z0-9_.:-]+\""))
                                throw new IllegalStateException("invalid scope code");
                            return s.substring(1, s.length() - 1);
                        })
                .toList();
    }

    private static String json(List<String> codes) {
        return "[" + String.join(",", codes.stream().map(CatalogSupport::json).toList()) + "]";
    }

    @Override
    public Collection<CatalogState.Category> loadCategories(String tenant) {
        JdbcWriteGuard.require(ds, tenant);
        return categories(tenant, CatalogLockMode.SHARED);
    }

    @Override
    public CatalogState lockCategoryTree(String tenant) {
        JdbcWriteGuard.require(ds, tenant);
        var tree = new CatalogState();
        for (var category : categories(tenant, CatalogLockMode.EXCLUSIVE)) {
            tree.categories.put(category.draft().categoryCode(), category);
        }
        return tree;
    }

    @Override
    public boolean hasOnSaleProduct(String tenant, String category, CatalogState state) {
        JdbcWriteGuard.require(ds, tenant);
        return !jdbc.queryForList("""
                SELECT id FROM mall_product
                WHERE tenant_no=? AND category_code=? AND sale_status='ON_SALE' AND deleted=0
                LIMIT 1 FOR UPDATE
                """, Long.class, tenant, category).isEmpty();
    }

    private List<CatalogState.Category> categories(String tenant, CatalogLockMode mode) {
        var result = new ArrayList<CatalogState.Category>();
        for (var r : rows("mall_category", tenant, mode)) {
            var d =
                    new CategoryDraft(
                            str(r, "category_code"),
                            str(r, "parent_code"),
                            JSON.readValue(str(r, "names"), NAMES),
                            str(r, "icon"),
                            integer(r, "sort_order"),
                            enabledCategory(str(r, "status")),
                            num(r, "version"),
                            "persisted category");
            result.add(new CatalogState.Category(d, integer(r, "level"), num(r, "version")));
        }
        return result;
    }

    private static boolean enabledCategory(String status) {
        return "ACTIVE".equals(status) || "ENABLED".equals(status);
    }

    public CatalogState load(String tenant, CatalogLockMode mode) {
        if (dictionary != null) return mallState(tenant, mode, System::currentTimeMillis);
        if (mode != CatalogLockMode.NONE) JdbcWriteGuard.require(ds, tenant);
        else JdbcWriteGuard.tenant(tenant);
        var s = new CatalogState();
        for (var r : rows("sys_business_type", tenant, mode)) {
            var d =
                    new BusinessTypeDraft(
                            str(r, "business_type_code"),
                            str(r, "name"),
                            integer(r, "sort"),
                            flag(r, "enabled"),
                            num(r, "version"),
                            null);
            s.types.put(
                    d.typeCode(),
                    new CatalogState.Type(d, num(r, "plan_catalog_version"), num(r, "version")));
        }
        loadEnds(s, "sys_business_end", tenant, mode);
        for (var r : rows("sys_industry_plan", tenant, mode)) {
            var p =
                    new CatalogState.Plan(
                            str(r, "plan_no"),
                            str(r, "business_type_code"),
                            PlanTier.valueOf(str(r, "tier_code")),
                            str(r, "current_version_no"),
                            str(r, "sale_status"),
                            num(r, "version"));
            s.plans.put(p.planNo(), p);
        }
        var planVersions = rows("sys_industry_plan_version", tenant, mode);
        var planEnds = rows("sys_plan_version_end", tenant, mode);
        for (var r : planVersions) {
            String no = str(r, "plan_version_no");
            var p = s.plans.get(str(r, "plan_no"));
            if (p == null) throw new IllegalStateException("orphan Plan version");
            var codes =
                    planEnds.stream()
                            .filter(e -> no.equals(str(e, "plan_version_no")))
                            .sorted(Comparator.comparingInt(e -> integer(e, "sort")))
                            .map(e -> str(e, "entitlement_code"))
                            .toList();
            var d =
                    new PlanDraft(
                            p.planNo(),
                            no,
                            p.typeCode(),
                            p.tier(),
                            str(r, "display_name"),
                            str(r, "summary"),
                            codes,
                            str(r, "policy_version"),
                            num(r, "version"),
                            str(r, "change_note"));
            s.planVersions.put(
                    no,
                    new CatalogState.PlanVersion(
                            d,
                            integer(r, "revision"),
                            str(r, "status"),
                            num(r, "version"),
                            nullableLong(r, "published_at"),
                            str(r, "published_by")));
        }
        loadMerchandise(s, tenant, mode);
        return s;
    }

    private void loadEnds(CatalogState s, String table, String tenant, CatalogLockMode mode) {
        for (var r : rows(table, tenant, mode)) {
            var d =
                    new BusinessEndDraft(
                            str(r, "end_code"),
                            str(r, "entitlement_code"),
                            str(r, "name"),
                            EndScope.valueOf(str(r, "scope")),
                            strings(str(r, "allowed_business_types")),
                            releaseStatus(str(r, "release_status")),
                            flag(r, "sale_enabled"),
                            flag(r, "bootstrap_required"),
                            flag(r, "enabled"),
                            str(r, "client_id"),
                            str(r, "route_policy_ref"),
                            str(r, "acceptance_ref"),
                            num(r, "version"),
                            "persisted registration");
            s.ends.put(d.code(), new CatalogState.End(d, num(r, "version")));
        }
    }

    private static ReleaseStatus releaseStatus(String status) {
        return switch (status) {
            case "GA" -> ReleaseStatus.VERIFIED;
            case "DEV", "BETA" -> ReleaseStatus.PLANNED;
            default -> ReleaseStatus.valueOf(status);
        };
    }

    private void loadMerchandise(CatalogState s, String tenant, CatalogLockMode mode) {
        for (var category : categories(tenant, mode)) {
            s.categories.put(category.draft().categoryCode(), category);
        }
        for (var r : rows("mall_product", tenant, mode)) {
            var d =
                    new ProductDraft(
                            str(r, "product_no"),
                            str(r, "category_code"),
                            ProductKind.valueOf(str(r, "product_type")),
                            str(r, "name"),
                            str(r, "description"),
                            strings(str(r, "allowed_business_types")),
                            integer(r, "sort"),
                            num(r, "version"),
                            "persisted product");
            s.products.put(
                    d.productNo(),
                    new CatalogState.Product(d, str(r, "sale_status"), num(r, "version")));
        }
        for (var r : rows("mall_sku", tenant, mode)) {
            var sku =
                    new CatalogState.Sku(
                            str(r, "sku_no"),
                            str(r, "product_no"),
                            str(r, "sku_name"),
                            integer(r, "max_per_order"),
                            str(r, "current_version_no"),
                            str(r, "sale_status"),
                            num(r, "version"));
            s.skus.put(sku.skuNo(), sku);
        }
        var skuVersions = rows("mall_sku_version", tenant, mode);
        var components = rows("mall_sku_component", tenant, mode);
        for (var r : skuVersions) {
            String no = str(r, "sku_version_no");
            var sku = s.skus.get(str(r, "sku_no"));
            if (sku == null) throw new IllegalStateException("orphan SKU version");
            var cs =
                    components.stream()
                            .filter(c -> no.equals(str(c, "sku_version_no")))
                            .sorted(Comparator.comparingInt(c -> integer(c, "component_seq")))
                            .map(
                                    c ->
                                            new SkuComponent(
                                                    str(c, "component_no"),
                                                    Set.of("END_ACCESS", "ENTITLEMENT").contains(str(c, "component_type"))
                                                            ? ComponentKind.END_PACKAGE
                                                            : ComponentKind.valueOf(
                                                                    str(c, "component_type")),
                                                    str(c, "plan_version_no"),
                                                    str(c, "entitlement_code"),
                                                    str(c, "hardware_sku_no"),
                                                    c.get("period_days") == null
                                                            ? null
                                                            : integer(c, "period_days"),
                                                    integer(c, "unit_count"),
                                                    num(c, "allocation_weight"),
                                                    flag(c, "is_gift"),
                                                    str(c, "activation_policy"),
                                                    str(c, "title")))
                            .toList();
            var d =
                    new SkuDraft(
                            sku.productNo(),
                            sku.skuNo(),
                            no,
                            str(r, "sku_name_snapshot"),
                            new Money(str(r, "currency"), num(r, "list_price_minor")),
                            new Money(str(r, "currency"), num(r, "sale_price_minor")),
                            strings(str(r, "allowed_business_types")),
                            str(r, "refund_policy_snapshot"),
                            cs,
                            r.get("max_per_order_snapshot") == null
                                    ? 0
                                    : integer(r, "max_per_order_snapshot"),
                            num(r, "version"),
                            "persisted SKU");
            s.skuVersions.put(
                    no,
                    new CatalogState.SkuVersion(
                            d,
                            integer(r, "revision"),
                            str(r, "status"),
                            num(r, "version"),
                            nullableLong(r, "published_at")));
        }

    }

    @Override
    public Collection<CatalogState.Type> businessTypes(String tenant) {
        if (dictionary == null) return CatalogRepository.super.businessTypes(tenant);
        JdbcWriteGuard.tenant(tenant);
        var result = new ArrayList<CatalogState.Type>();
        var types = dictionary.queryForList("""
                SELECT item_code,name,sort_no,status,version,
                       JSON_UNQUOTE(JSON_EXTRACT(attrs,'$.plan_catalog_version')) AS catalog_version
                FROM sys_dict_item WHERE tenant_no=? AND dict_type='BUSINESS_TYPE'
                  AND item_level='TYPE' AND deleted=0 ORDER BY sort_no,item_code
                """, tenant);
        for (var row : types) {
            long version = num(row, "version");
            var type = new BusinessTypeDraft(str(row, "item_code"), str(row, "name"),
                    integer(row, "sort_no"), "ENABLED".equals(str(row, "status")), version, null);
            String token = str(row, "catalog_version");
            if (token == null) throw new IllegalStateException(
                    "Missing plan_catalog_version for business type " + type.typeCode());
            result.add(new CatalogState.Type(type, Long.parseLong(token), version));
        }
        return result;
    }

    @Override
    public void saveBusinessType(String tenant, CatalogState.Type before, CatalogState.Type after,
            CatalogWriteContext context) {
        JdbcMallMasterData.saveBusinessType(dictionary, tenant, before, after, context);
    }

    @Override
    public CatalogState overview(String tenant, LongSupplier clock) {
        if (dictionary == null) return CatalogRepository.super.overview(tenant, clock);
        return mallState(tenant, CatalogLockMode.SHARED, clock);
    }

    @Override
    public CatalogState lockForWrite(String tenant, LongSupplier clock) {
        return dictionary == null ? CatalogRepository.super.lockForWrite(tenant, clock)
                : mallState(tenant, CatalogLockMode.EXCLUSIVE, clock);
    }

    private CatalogState mallState(String tenant, CatalogLockMode mode, LongSupplier clock) {
        if (mode != CatalogLockMode.NONE) JdbcWriteGuard.require(ds, tenant);
        var state = new CatalogState();
        loadEnds(state, "mall_end_entitlement", tenant, mode);
        var plans = rows("sys_industry_plan", tenant, mode);
        // A publication may commit while the plan locks are being acquired.
        long now = clock.getAsLong();
        // If no version is effective yet, expose the latest revision as the editable definition.
        plans.sort(Comparator.comparingInt((Map<String, Object> row) -> integer(row, "revision")).reversed());
        var members = rows("sys_plan_version_end", tenant, mode);
        var codes = new HashMap<String, List<String>>();
        members.stream().sorted(Comparator.comparingInt(r -> integer(r, "sort"))).forEach(r ->
                codes.computeIfAbsent(str(r, "plan_version_no"), ignored -> new ArrayList<>())
                        .add(str(r, "entitlement_code")));
        for (var row : plans) {
            String plan = str(row, "plan_no"), no = str(row, "version_no");
            String status = str(row, "status");
            long version = num(row, "version");
            var draft = new PlanDraft(plan, no, str(row, "business_type_code"),
                    PlanTier.valueOf(str(row, "tier_code")), str(row, "display_name"),
                    str(row, "summary"), codes.getOrDefault(no, List.of()), str(row, "policy_version"),
                    version, str(row, "change_note"));
            var revision = integer(row, "revision");
            state.planVersions.put(no, new CatalogState.PlanVersion(draft, revision,
                    "ACTIVE".equals(status) ? "PUBLISHED" : status, version,
                    nullableLong(row, "published_at"), str(row, "published_by")));
            Long from = nullableLong(row, "effective_from"), to = nullableLong(row, "effective_to");
            boolean current = "ACTIVE".equals(status) && (from == null || from <= now) && (to == null || now < to);
            var prior = state.plans.get(plan);
            if (prior == null || (current && (prior.currentVersionNo() == null
                    || state.planVersions.get(prior.currentVersionNo()).revision() < revision))) {
                state.plans.put(plan, new CatalogState.Plan(plan, draft.typeCode(), draft.tier(),
                        current ? no : null, str(row, "sale_status"), version));
            }
        }
        loadMerchandise(state, tenant, mode);
        if (mode != CatalogLockMode.NONE) journal.finishPending(tenant);
        for (var type : businessTypes(tenant)) {
            var code = type.draft().typeCode();
            state.types.put(code, type);
            for (var tier : PlanTier.catalogTiers(code)) {
                if (state.plans.values().stream().noneMatch(p -> p.typeCode().equals(code) && p.tier() == tier)) {
                    String plan = code + ":" + tier;
                    state.plans.put(plan, new CatalogState.Plan(plan, code, tier, null, "DRAFT", 1));
                }
            }
        }
        return state;
    }

    private static LinkedHashMap<String, Object> fields(Object... entries) {
        var map = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) map.put((String) entries[i], entries[i + 1]);
        return map;
    }

    private void row(
            String table,
            String key,
            String no,
            String tenant,
            long before,
            long version,
            CatalogWriteContext context,
            LinkedHashMap<String, Object> data) {
        var now = LocalDateTime.ofInstant(context.occurredAt(), ZoneOffset.UTC);
        data.put("updated_at", now);
        data.put("updated_by", context.principalNo());
        data.put("version", version);
        if (before < 0) {
            data.put("tenant_no", tenant);
            data.put(key, no);
            data.put("created_at", now);
            data.put("created_by", context.principalNo());
            data.put("deleted", 0);
            var marks = String.join(",", Collections.nCopies(data.size(), "?"));
            jdbc.update(
                    "INSERT INTO "
                            + table
                            + " ("
                            + String.join(
                                    ",", data.keySet().stream().map(c -> "`" + c + "`").toList())
                            + ") VALUES ("
                            + marks
                            + ")",
                    data.values().toArray());
        } else {
            var args = new ArrayList<>(data.values());
            args.add(tenant);
            args.add(no);
            args.add(before);
            var set = String.join(",", data.keySet().stream().map(c -> "`" + c + "`=?").toList());
            int changed =
                    jdbc.update(
                            "UPDATE "
                                    + table
                                    + " SET "
                                    + set
                                    + " WHERE tenant_no=? AND "
                                    + key
                                    + "=? AND version=? AND deleted=0",
                            args.toArray());
            if (changed != 1)
                throw new CatalogException(
                        CatalogErrorCode.VERSION_CONFLICT, "stale catalog row " + table + ":" + no);
        }
    }

    private <T> void changes(
            Map<String, T> before, Map<String, T> after, BiConsumer<T, T> persist) {
        if (!after.keySet().containsAll(before.keySet()))
            throw new IllegalStateException("physical catalog deletion forbidden");
        after.forEach(
                (key, next) -> {
                    var old = before.get(key);
                    if (!Objects.equals(old, next)) persist.accept(old, next);
                });
    }

    public void save(
            String tenant, CatalogState before, CatalogState after, CatalogWriteContext context) {
        JdbcWriteGuard.require(ds, tenant);
        if (dictionary == null) saveLegacyPlans(tenant, before, after, context);
        else {
            JdbcMallMasterData.save(ds, tenant, before, after, context);
            saveMallPlans(tenant, before, after, context);
        }
        saveMerchandise(tenant, before, after, context);
    }

    private void saveLegacyPlans(String tenant, CatalogState before, CatalogState after, CatalogWriteContext context) {
        changes(
                before.types,
                after.types,
                (o, n) -> {
                    var d = n.draft();
                    row(
                            "sys_business_type",
                            "business_type_code",
                            d.typeCode(),
                            tenant,
                            o == null ? -1 : o.version(),
                            n.version(),
                            context,
                            fields(
                                    "name",
                                    d.name(),
                                    "enabled",
                                    d.enabled(),
                                    "sort",
                                    d.sortOrder(),
                                    "plan_catalog_version",
                                    n.catalogVersion()));
                });
        changes(
                before.ends,
                after.ends,
                (o, n) -> {
                    var d = n.draft();
                    row(
                            "sys_business_end",
                            "end_code",
                            d.code(),
                            tenant,
                            o == null ? -1 : o.version(),
                            n.version(),
                            context,
                            fields(
                                    "entitlement_code",
                                    d.entitlementCode(),
                                    "name",
                                    d.name(),
                                    "scope",
                                    d.scope().name(),
                                    "allowed_business_types",
                                    json(d.allowedBusinessTypes()),
                                    "release_status",
                                    d.releaseStatus().name(),
                                    "sale_enabled",
                                    d.saleEnabled(),
                                    "bootstrap_required",
                                    d.bootstrapRequired(),
                                    "enabled",
                                    d.enabled(),
                                    "client_id",
                                    d.clientId(),
                                    "route_policy_ref",
                                    d.routePolicyRef(),
                                    "acceptance_ref",
                                    d.acceptanceRef()));
                });
        changes(
                before.plans,
                after.plans,
                (o, n) ->
                        row(
                                "sys_industry_plan",
                                "plan_no",
                                n.planNo(),
                                tenant,
                                o == null ? -1 : o.version(),
                                n.version(),
                                context,
                                fields(
                                        "business_type_code",
                                        n.typeCode(),
                                        "tier_code",
                                        n.tier().name(),
                                        "rank",
                                        n.tier().ordinal(),
                                        "current_version_no",
                                        n.currentVersionNo(),
                                        "sale_status",
                                        n.saleStatus(),
                                        "sort",
                                        n.tier().ordinal())));
        changes(
                before.planVersions,
                after.planVersions,
                (o, n) -> {
                    if (o != null && o.status().equals("PUBLISHED"))
                        throw new CatalogException(
                                CatalogErrorCode.IMMUTABLE_VERSION, "published Plan immutable");
                    var d = n.draft();
                    row(
                            "sys_industry_plan_version",
                            "plan_version_no",
                            d.planVersionNo(),
                            tenant,
                            o == null ? -1 : o.version(),
                            n.version(),
                            context,
                            fields(
                                    "plan_no",
                                    d.planNo(),
                                    "revision",
                                    n.revision(),
                                    "display_name",
                                    d.displayName(),
                                    "summary",
                                    d.summary(),
                                    "status",
                                    n.status(),
                                    "policy_version",
                                    d.policyVersion(),
                                    "published_at",
                                    n.publishedAt(),
                                    "published_by",
                                    n.publishedBy(),
                                    "change_note",
                                    d.reason()));
                    syncPlanEnds(
                            tenant,
                            o == null ? List.of() : o.draft().entitlementCodes(),
                            d,
                            after,
                            context);
                });
    }

    private void saveMallPlans(String tenant, CatalogState before, CatalogState after, CatalogWriteContext context) {
        var timestamp = LocalDateTime.ofInstant(context.occurredAt(), ZoneOffset.UTC);
        changes(before.planVersions, after.planVersions, (old, next) -> {
            var draft = next.draft();
            // Publication writes the version and the definition together below.
            if (!next.status().equals("DRAFT")) return;
            if (old == null) {
                int inserted = jdbc.update("""
                        INSERT INTO sys_industry_plan
                          (tenant_no,plan_no,version_no,revision,business_type_code,tier_code,`rank`,sort,
                           store_quota,staff_quota,cross_store_stats,trial_days,display_name,summary,
                           policy_version,change_note,status,sale_status,version,deleted,created_at,updated_at,created_by,updated_by)
                        SELECT tenant_no,plan_no,?,?,business_type_code,tier_code,`rank`,sort,
                               store_quota,staff_quota,cross_store_stats,trial_days,?,?,?,?,'DRAFT','OFF_SALE',
                               1,0,?,?,?,? FROM sys_industry_plan
                        WHERE tenant_no=? AND plan_no=? AND deleted=0 ORDER BY revision DESC LIMIT 1
                        """, draft.planVersionNo(), next.revision(), draft.displayName(), draft.summary(),
                        draft.policyVersion(), draft.reason(), timestamp, timestamp, context.principalNo(),
                        context.principalNo(), tenant, draft.planNo());
                if (inserted == 0) {
                    var tier = draft.tier().ordinal();
                    jdbc.update("""
                            INSERT INTO sys_industry_plan
                              (tenant_no,plan_no,version_no,revision,business_type_code,tier_code,`rank`,sort,
                               display_name,summary,policy_version,change_note,status,sale_status,version,deleted,
                               created_at,updated_at,created_by,updated_by)
                            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT','OFF_SALE',1,0,?,?,?,?)
                            """, tenant, draft.planNo(), draft.planVersionNo(), next.revision(), draft.typeCode(),
                            draft.tier().name(), tier, tier, draft.displayName(), draft.summary(),
                            draft.policyVersion(), draft.reason(), timestamp, timestamp, context.principalNo(),
                            context.principalNo());
                }
            } else {
                row("sys_industry_plan", "version_no", draft.planVersionNo(), tenant, old.version(),
                        next.version(), context, fields("display_name", draft.displayName(), "summary",
                                draft.summary(), "policy_version", draft.policyVersion(), "change_note", draft.reason()));
            }
            syncPlanEnds(tenant, old == null ? List.of() : old.draft().entitlementCodes(), draft, after, context);
        });
        changes(before.plans, after.plans, (old, next) -> {
            if (old == null) return; // Type creation exposes definitions before their first draft.
            String target = next.currentVersionNo();
            if (target == null) {
                target = before.planVersions.values().stream()
                        .filter(v -> v.draft().planNo().equals(next.planNo()))
                        .max(Comparator.comparingInt(CatalogState.PlanVersion::revision))
                        .orElseThrow().draft().planVersionNo();
            }
            var original = before.planVersions.get(target);
            var data = fields("sale_status", next.saleStatus());
            if (!Objects.equals(old.currentVersionNo(), next.currentVersionNo())) {
                if (old.currentVersionNo() != null) {
                    var previous = before.planVersions.get(old.currentVersionNo());
                    row("sys_industry_plan", "version_no", old.currentVersionNo(), tenant, previous.version(),
                            previous.version() + 1, context, fields("effective_to", context.occurredAt().toEpochMilli(),
                                    "sale_status", "OFF_SALE"));
                }
                data.put("status", "ACTIVE");
                data.put("effective_from", context.occurredAt().toEpochMilli());
                data.put("effective_to", null);
                if (original.status().equals("DRAFT")) {
                    data.put("published_at", context.occurredAt().toEpochMilli());
                    data.put("published_by", context.principalNo());
                }
            }
            row("sys_industry_plan", "version_no", target, tenant, original.version(),
                    Math.max(original.version(), old.version()) + 1, context, data);
        });
    }

    private void saveMerchandise(String tenant, CatalogState before, CatalogState after, CatalogWriteContext context) {
        changes(
                before.categories,
                after.categories,
                (o, n) -> {
                    var d = n.draft();
                    row(
                            "mall_category",
                            "category_code",
                            d.categoryCode(),
                            tenant,
                            o == null ? -1 : o.version(),
                            n.version(),
                            context,
                            fields(
                                    "parent_code",
                                    d.parentCode(),
                                    "level",
                                    n.level(),
                                    "names",
                                    JSON.writeValueAsString(new TreeMap<>(d.names())),
                                    "icon",
                                    d.icon(),
                                    "sort_order",
                                    d.sortOrder(),
                                    "status",
                                    dictionary != null && d.enabled() ? "ACTIVE" : n.status()));
                });
        changes(
                before.products,
                after.products,
                (o, n) -> {
                    var d = n.draft();
                    row(
                            "mall_product",
                            "product_no",
                            d.productNo(),
                            tenant,
                            o == null ? -1 : o.version(),
                            n.version(),
                            context,
                            fields(
                                    "category_code",
                                    d.categoryCode(),
                                    "product_type",
                                    d.kind().name(),
                                    "name",
                                    d.name(),
                                    "description",
                                    d.description(),
                                    "allowed_business_types",
                                    json(d.allowedBusinessTypes()),
                                    "sale_status",
                                    n.saleStatus(),
                                    "sort",
                                    d.sortOrder()));
                });
        changes(
                before.skus,
                after.skus,
                (o, n) ->
                        row(
                                "mall_sku",
                                "sku_no",
                                n.skuNo(),
                                tenant,
                                o == null ? -1 : o.version(),
                                n.version(),
                                context,
                                fields(
                                        "product_no",
                                        n.productNo(),
                                        "sku_name",
                                        n.title(),
                                        "current_version_no",
                                        n.currentVersionNo(),
                                        "sale_status",
                                        n.saleStatus(),
                                        "max_per_order",
                                        n.maxPerOrder())));
        changes(
                before.skuVersions,
                after.skuVersions,
                (o, n) -> {
                    if (o != null && o.status().equals("PUBLISHED"))
                        throw new CatalogException(
                                CatalogErrorCode.IMMUTABLE_VERSION, "published SKU immutable");
                    var d = n.draft();
                    row(
                            "mall_sku_version",
                            "sku_version_no",
                            d.skuVersionNo(),
                            tenant,
                            o == null ? -1 : o.version(),
                            n.version(),
                            context,
                            fields(
                                    "sku_no",
                                    d.skuNo(),
                                    "revision",
                                    n.revision(),
                                    "status",
                                    n.status(),
                                    "currency",
                                    d.price().currency(),
                                    "list_price_minor",
                                    d.listPrice().minor(),
                                    "sale_price_minor",
                                    d.price().minor(),
                                    "allowed_business_types",
                                    json(d.allowedBusinessTypes()),
                                    "refund_policy_snapshot",
                                    d.refundPolicySnapshot(),
                                    "published_at",
                                    n.publishedAt(),
                                    "sku_name_snapshot",
                                    d.title(),
                                    "max_per_order_snapshot",
                                    d.maxPerOrder()));
                    syncComponents(
                            tenant, o == null ? List.of() : o.draft().components(), d, context);
                });
    }

    private void updateMember(
            String table,
            String tenant,
            String parentColumn,
            String parentNo,
            String keyColumn,
            String key,
            LinkedHashMap<String, Object> data,
            CatalogWriteContext context) {
        data.put("updated_by", context.principalNo());
        data.put("updated_at", LocalDateTime.ofInstant(context.occurredAt(), ZoneOffset.UTC));
        var args = new ArrayList<>(data.values());
        args.add(tenant);
        args.add(parentNo);
        args.add(key);
        var assignments =
                String.join(",", data.keySet().stream().map(c -> "`" + c + "`=?").toList());
        int changed =
                jdbc.update(
                        "UPDATE "
                                + table
                                + " SET "
                                + assignments
                                + ",version=version+1 WHERE tenant_no=? AND "
                                + parentColumn
                                + "=? AND "
                                + keyColumn
                                + "=? AND deleted=0",
                        args.toArray());
        if (changed != 1)
            throw new CatalogException(CatalogErrorCode.VERSION_CONFLICT, "stale catalogue member");
    }

    private void syncPlanEnds(
            String tenant,
            List<String> old,
            PlanDraft draft,
            CatalogState state,
            CatalogWriteContext context) {
        var next = draft.entitlementCodes();
        for (String code : old)
            if (!next.contains(code))
                jdbc.update(
                        "DELETE FROM sys_plan_version_end WHERE tenant_no=? AND plan_version_no=?"
                                + " AND entitlement_code=?",
                        tenant,
                        draft.planVersionNo(),
                        code);
        for (int seq = 0; seq < next.size(); seq++) {
            String code = next.get(seq);
            if (old.contains(code)) {
                if (old.indexOf(code) != seq)
                    updateMember(
                            "sys_plan_version_end",
                            tenant,
                            "plan_version_no",
                            draft.planVersionNo(),
                            "entitlement_code",
                            code,
                            fields("sort", seq),
                            context);
            } else {
                var end =
                        state.ends.values().stream()
                                .filter(e -> e.draft().entitlementCode().equals(code))
                                .findFirst()
                                .orElseThrow();
                // The membership key is tenant + Plan version + code, rather than a global external
                // number.
                var now = LocalDateTime.ofInstant(context.occurredAt(), ZoneOffset.UTC);
                jdbc.update(
                        "INSERT INTO sys_plan_version_end"
                            + " (tenant_no,created_at,updated_at,created_by,updated_by,version,deleted,plan_version_no,end_code,entitlement_code,sort)"
                            + " VALUES (?,?,?,?,?,1,0,?,?,?,?)",
                        tenant,
                        now,
                        now,
                        context.principalNo(),
                        context.principalNo(),
                        draft.planVersionNo(),
                        end.draft().code(),
                        code,
                        seq);
            }
        }
    }

    private void syncComponents(
            String tenant, List<SkuComponent> old, SkuDraft draft, CatalogWriteContext context) {
        var next = draft.components();
        var nextNos = next.stream().map(SkuComponent::componentNo).toList();
        var oldNos = old.stream().map(SkuComponent::componentNo).toList();
        for (String no : oldNos)
            if (!nextNos.contains(no))
                jdbc.update(
                        "DELETE FROM mall_sku_component WHERE tenant_no=? AND sku_version_no=? AND"
                                + " component_no=?",
                        tenant,
                        draft.skuVersionNo(),
                        no);
        // Move existing positions temporarily to avoid the unique sequence key during a reorder.
        // All intermediate values remain under the already-held catalogue transaction locks.
        for (int seq = 0; seq < next.size(); seq++) {
            String no = next.get(seq).componentNo();
            int previous = oldNos.indexOf(no);
            if (previous >= 0 && previous != seq)
                jdbc.update(
                        "UPDATE mall_sku_component SET component_seq=? WHERE tenant_no=? AND"
                                + " sku_version_no=? AND component_no=?",
                        -seq - 1,
                        tenant,
                        draft.skuVersionNo(),
                        no);
        }
        for (int seq = 0; seq < next.size(); seq++) {
            var c = next.get(seq);
            int previous = oldNos.indexOf(c.componentNo());
            var data =
                    fields(
                            "sku_version_no",
                            draft.skuVersionNo(),
                            "component_seq",
                            seq,
                            "component_type",
                            c.kind() == ComponentKind.END_PACKAGE
                                    ? (dictionary == null ? "END_ACCESS" : "ENTITLEMENT") : c.kind().name(),
                            "plan_version_no",
                            c.planVersionNo(),
                            "entitlement_code",
                            c.entitlementCode(),
                            "hardware_sku_no",
                            c.hardwareSkuNo(),
                            "period_days",
                            c.periodDays(),
                            "unit_count",
                            c.unitCount(),
                            "allocation_weight",
                            c.allocationWeight(),
                            "is_gift",
                            c.gift(),
                            "activation_policy",
                            c.activationPolicy(),
                            "title",
                            c.title());
            if (previous < 0)
                row(
                        "mall_sku_component",
                        "component_no",
                        c.componentNo(),
                        tenant,
                        -1,
                        1,
                        context,
                        data);
            else if (previous != seq || !old.get(previous).equals(c))
                updateMember(
                        "mall_sku_component",
                        tenant,
                        "sku_version_no",
                        draft.skuVersionNo(),
                        "component_no",
                        c.componentNo(),
                        data,
                        context);
        }
    }
}
