package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.product.service.AiGoodsService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.trade.service.AiMetricsService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AI 经营助手（soukmind）取数接口 —— soukmind 标准业务契约 v1 的 ai-shop 实现（{@code /internal/ai/v1/**}）。
 *
 * <p><b>为什么放在 {@code portal.internal}</b>：与 {@link JobHandlerEndpoint} 同一个理由 ——
 * {@code /internal/**} 不进任何 Security 链（只有同机的 soukmind 调，nginx 不对外），
 * {@code ApiResponseWrapper} 按包名排除本包，于是响应能用契约规定的外壳 {@code {code,msg,data,meta}}
 * 而不是本仓的 {@code ApiResponse}。<b>代价是鉴权要自己做</b>：服务密钥 {@code shop.ai.internal-token}，
 * 常数时间比对；<b>未配置 = 一律 401</b>（fail-closed，忘配不会裸奔）。
 *
 * <p>租户在请求头：{@code X-Merchant-Id}（商户号）、{@code X-Store-Id}（门店·可空=商户级）、
 * {@code X-Account-Id}（员工·可空）。门店必须属于该商户，带员工时还必须在他有权的门店内 —— 否则 40300，不回任何数据。
 *
 * <p>金额：服务层给分，这里换成元（两位小数）；{@code meta.currency=CNY}。
 * 设计与验收：{@code docs/technical/TDD-AI取数接口.md}。
 */
@RestController
@RequestMapping("/internal/ai/v1")
public class AiDataEndpoint {

    static final int OK = 0;
    static final int BAD_PARAM = 40001;
    static final int TOKEN_INVALID = 40100;
    static final int FORBIDDEN = 40300;
    static final int NOT_FOUND = 40400;
    static final int RANGE_TOO_LARGE = 41300;
    /** 区间上限（天，含头含尾）。与 NearBoss 数据分析 API 同一条线 */
    static final int MAX_DAYS = 400;
    private static final String CURRENCY = "CNY";

    private final String token;
    private final int lowStockThreshold;
    private final AiMetricsService metrics;
    private final AiGoodsService goods;
    private final MerchantQueryPort merchants;
    private final TokenStore tokenStore;
    private final ObjectProvider<BizIdentityResolver> resolver;

    public AiDataEndpoint(@Value("${shop.ai.internal-token:}") String token,
                          @Value("${shop.ai.low-stock-threshold:10}") int lowStockThreshold,
                          AiMetricsService metrics, AiGoodsService goods, MerchantQueryPort merchants,
                          TokenStore tokenStore, ObjectProvider<BizIdentityResolver> resolver) {
        this.token = token;
        this.lowStockThreshold = lowStockThreshold;
        this.metrics = metrics;
        this.goods = goods;
        this.merchants = merchants;
        this.tokenStore = tokenStore;
        this.resolver = resolver;
    }

    // ---------------------------------------------------------------- 连通 / 身份 / 门店

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health(
            @RequestHeader(value = "X-Internal-Token", required = false) String given) {
        if (!authorized(given)) {
            return unauthorized();
        }
        return ok(Map.of("status", "UP"), null);
    }

    /** 业务令牌（B 端 {@code btk_…}）→ 身份。只认服务密钥，不看租户头 */
    @PostMapping("/session/verify")
    public ResponseEntity<Map<String, Object>> verify(
            @RequestHeader(value = "X-Internal-Token", required = false) String given,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(given)) {
            return unauthorized();
        }
        String bizToken = str(body, "token");
        Optional<LoginUser> user = bizToken == null ? Optional.empty()
                : tokenStore.get(bizToken).map(TokenStore.SessionData::user)
                        .filter(u -> u.realm() == Realm.MERCHANT);
        if (user.isEmpty()) {
            return fail(TOKEN_INVALID, "token invalid");
        }
        BizContext ctx = resolver.getIfAvailable(() -> BizIdentityResolver.NONE)
                .resolve(user.get().userNo(), str(body, "store_id"));
        if (ctx == null || ctx.merchantNo() == null) {
            return fail(TOKEN_INVALID, "token invalid");
        }
        // 属主 = 名下全部门店；员工 = 被授权的那几家
        Collection<String> storeNos = ctx.owner() ? merchants.storeNos(ctx.merchantNo()) : ctx.storeNos();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("merchant_id", ctx.merchantNo());
        data.put("account_id", user.get().userNo());
        data.put("display_name", user.get().nickname());
        data.put("role", roleOf(ctx));
        data.put("stores", stores(storeNos));
        data.put("perms", List.copyOf(ctx.effectivePerms()));
        return ok(data, null);
    }

    @PostMapping("/stores/list")
    public ResponseEntity<Map<String, Object>> storesList(
            @RequestHeader(value = "X-Internal-Token", required = false) String given,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantNo) {
        if (!authorized(given)) {
            return unauthorized();
        }
        if (blank(merchantNo)) {
            return fail(BAD_PARAM, "X-Merchant-Id required");
        }
        return ok(Map.of("items", stores(merchants.storeNos(merchantNo))), null);
    }

    // ---------------------------------------------------------------- 经营数据

    @PostMapping("/metrics/query")
    public ResponseEntity<Map<String, Object>> metricsQuery(
            @RequestHeader(value = "X-Internal-Token", required = false) String given,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantNo,
            @RequestHeader(value = "X-Store-Id", required = false) String storeHeader,
            @RequestHeader(value = "X-Account-Id", required = false) String accountNo,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(given)) {
            return unauthorized();
        }
        Scope scope = scope(merchantNo, storeOf(storeHeader, body), accountNo);
        if (scope.error != null) {
            return scope.error;
        }
        Range range = range(body);
        if (range.error != null) {
            return range.error;
        }
        List<String> ms = new ArrayList<>();
        if (body != null && body.get("metrics") instanceof Collection<?> c) {
            c.forEach(m -> ms.add(String.valueOf(m)));
        }
        if (!blank(str(body, "dimension"))) {
            // 维度拆分一期不支持：如实回空 + NONE，soukmind 会说「暂无数据」而不是编一个
            return ok(Map.of("breakdown", List.of()), "NONE");
        }
        AiMetricsService.Result r = metrics.query(merchantNo, scope.storeNos, range.from, range.to, ms,
                str(body, "time_granularity"), str(body, "compare"), str(body, "aggregation"));
        List<Map<String, Object>> series = new ArrayList<>();
        for (AiMetricsService.Row row : r.series()) {
            Map<String, Object> values = new LinkedHashMap<>();
            row.values().forEach((m, cell) -> {
                boolean money = AiMetricsService.MONEY.contains(m);
                Map<String, Object> v = new LinkedHashMap<>();
                v.put("value", num(cell.value(), money));
                v.put("prev", num(cell.prev(), money));
                v.put("pct", cell.pct());
                values.put(m, v);
            });
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("period", row.period());
            out.put("values", values);
            series.add(out);
        }
        return ok(Map.of("series", series), r.hasData() ? "ORDER_AGGREGATED" : "NONE");
    }

    // ---------------------------------------------------------------- 商品

    @PostMapping("/goods/sales-ranking")
    public ResponseEntity<Map<String, Object>> salesRanking(
            @RequestHeader(value = "X-Internal-Token", required = false) String given,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantNo,
            @RequestHeader(value = "X-Store-Id", required = false) String storeHeader,
            @RequestHeader(value = "X-Account-Id", required = false) String accountNo,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(given)) {
            return unauthorized();
        }
        Scope scope = scope(merchantNo, storeOf(storeHeader, body), accountNo);
        if (scope.error != null) {
            return scope.error;
        }
        Range range = range(body);
        if (range.error != null) {
            return range.error;
        }
        List<Map<String, Object>> items = metrics.salesRanking(merchantNo, scope.storeNos, range.from, range.to,
                        asc(body), limit(body, 10, 100)).stream()
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("spuId", s.goodsNo());
                    m.put("productName", s.title());
                    m.put("salesQty", s.qty());
                    m.put("salesAmount", yuan(s.amountMinor()));
                    return m;
                }).toList();
        return ok(Map.of("items", items), items.isEmpty() ? "NONE" : "ORDER_AGGREGATED");
    }

    @PostMapping("/goods/cumulative-ranking")
    public ResponseEntity<Map<String, Object>> cumulativeRanking(
            @RequestHeader(value = "X-Internal-Token", required = false) String given,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantNo,
            @RequestHeader(value = "X-Store-Id", required = false) String storeHeader,
            @RequestHeader(value = "X-Account-Id", required = false) String accountNo,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(given)) {
            return unauthorized();
        }
        Scope scope = scope(merchantNo, storeOf(storeHeader, body), accountNo);
        if (scope.error != null) {
            return scope.error;
        }
        List<Map<String, Object>> items = goods.cumulativeRanking(merchantNo, scope.storeNo, asc(body),
                limit(body, 10, 100)).stream().map(g -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("spuId", g.goodsNo());
                    m.put("spuName", g.title());
                    m.put("totalSales", g.totalSales());
                    m.put("minPrice", yuan(g.minPrice()));
                    m.put("maxPrice", yuan(g.maxPrice()));
                    m.put("totalStock", g.totalStock());
                    m.put("status", g.onSale() ? "ON_SALE" : "OFF_SALE");
                    return m;
                }).toList();
        return ok(Map.of("items", items), null);
    }

    @PostMapping("/goods/low-stock")
    public ResponseEntity<Map<String, Object>> lowStock(
            @RequestHeader(value = "X-Internal-Token", required = false) String given,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantNo,
            @RequestHeader(value = "X-Store-Id", required = false) String storeHeader,
            @RequestHeader(value = "X-Account-Id", required = false) String accountNo,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!authorized(given)) {
            return unauthorized();
        }
        Scope scope = scope(merchantNo, storeOf(storeHeader, body), accountNo);
        if (scope.error != null) {
            return scope.error;
        }
        Integer t = integer(body == null ? null : body.get("threshold"));
        int threshold = t == null ? lowStockThreshold : t;
        List<Map<String, Object>> items = goods.lowStock(merchantNo, scope.storeNo, threshold,
                limit(body, 50, 200)).stream().map(s -> skuMap(s, threshold)).toList();
        return ok(Map.of("items", items), null);
    }

    @GetMapping("/goods/list")
    public ResponseEntity<Map<String, Object>> list(
            @RequestHeader(value = "X-Internal-Token", required = false) String given,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantNo,
            @RequestHeader(value = "X-Store-Id", required = false) String storeHeader,
            @RequestHeader(value = "X-Account-Id", required = false) String accountNo,
            @RequestParam(value = "store_id", required = false) String storeParam,
            @RequestParam(value = "category_id", required = false) String categoryNo,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page_num", required = false, defaultValue = "1") int pageNum,
            @RequestParam(value = "page_size", required = false, defaultValue = "20") int pageSize) {
        if (!authorized(given)) {
            return unauthorized();
        }
        Scope scope = scope(merchantNo, blank(storeHeader) ? storeParam : storeHeader, accountNo);
        if (scope.error != null) {
            return scope.error;
        }
        AiGoodsService.Page page = goods.list(merchantNo, scope.storeNo, categoryNo, keyword, pageNum, pageSize);
        List<Map<String, Object>> items = page.items().stream().map(g -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", g.goodsNo());
            m.put("spuName", g.title());
            m.put("mainImage", g.cover());
            m.put("minPrice", yuan(g.minPrice()));
            m.put("maxPrice", yuan(g.maxPrice()));
            m.put("totalStock", g.totalStock());
            m.put("totalSales", g.totalSales());
            m.put("soldOut", g.totalStock() <= 0);
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("total", page.total());
        return ok(data, null);
    }

    @GetMapping("/goods/detail")
    public ResponseEntity<Map<String, Object>> detail(
            @RequestHeader(value = "X-Internal-Token", required = false) String given,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantNo,
            @RequestHeader(value = "X-Store-Id", required = false) String storeHeader,
            @RequestHeader(value = "X-Account-Id", required = false) String accountNo,
            @RequestParam(value = "store_id", required = false) String storeParam,
            @RequestParam(value = "spu_id", required = false) String goodsNo) {
        if (!authorized(given)) {
            return unauthorized();
        }
        Scope scope = scope(merchantNo, blank(storeHeader) ? storeParam : storeHeader, accountNo);
        if (scope.error != null) {
            return scope.error;
        }
        Optional<AiGoodsService.Detail> d = blank(goodsNo) ? Optional.empty()
                : goods.detail(merchantNo, scope.storeNo, goodsNo);
        if (d.isEmpty()) {
            return fail(NOT_FOUND, "goods not found");
        }
        AiGoodsService.GoodsRow g = d.get().goods();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", g.goodsNo());
        data.put("spuName", g.title());
        data.put("subtitle", d.get().subtitle());
        data.put("mainImage", g.cover());
        data.put("categoryId", d.get().categoryNo());
        data.put("minPrice", yuan(g.minPrice()));
        data.put("maxPrice", yuan(g.maxPrice()));
        data.put("totalStock", g.totalStock());
        data.put("totalSales", g.totalSales());
        data.put("status", g.onSale() ? "ON_SALE" : "OFF_SALE");
        data.put("skuList", d.get().skus().stream().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("skuId", s.skuNo());
            m.put("price", yuan(s.price()));
            m.put("stock", s.stock());
            m.put("specValueStr", s.spec());
            return m;
        }).toList());
        return ok(data, null);
    }

    @GetMapping("/goods/sku")
    public ResponseEntity<Map<String, Object>> sku(
            @RequestHeader(value = "X-Internal-Token", required = false) String given,
            @RequestHeader(value = "X-Merchant-Id", required = false) String merchantNo,
            @RequestHeader(value = "X-Store-Id", required = false) String storeHeader,
            @RequestHeader(value = "X-Account-Id", required = false) String accountNo,
            @RequestParam(value = "sku_id", required = false) String skuNo) {
        if (!authorized(given)) {
            return unauthorized();
        }
        Scope scope = scope(merchantNo, storeHeader, accountNo);
        if (scope.error != null) {
            return scope.error;
        }
        Optional<AiGoodsService.SkuRow> s = blank(skuNo) ? Optional.empty()
                : goods.sku(merchantNo, scope.storeNo, skuNo);
        if (s.isEmpty()) {
            // 他人商户的 SKU 与不存在同一个回答：区分开等于告诉调用方别家有这个号
            return fail(NOT_FOUND, "sku not found");
        }
        Map<String, Object> data = skuMap(s.get(), null);
        data.put("costPrice", s.get().costPrice() == null ? null : yuan(s.get().costPrice()));
        data.put("status", s.get().onSale() ? "ON_SALE" : "OFF_SALE");
        return ok(data, null);
    }

    // ---------------------------------------------------------------- 租户 / 区间

    /** 校验过的租户范围：{@code storeNos} 喂给指标服务（null = 商户全部），{@code storeNo} 喂给商品服务 */
    private record Scope(Collection<String> storeNos, String storeNo, ResponseEntity<Map<String, Object>> error) {
    }

    private Scope scope(String merchantNo, String storeNo, String accountNo) {
        if (blank(merchantNo)) {
            return new Scope(null, null, fail(BAD_PARAM, "X-Merchant-Id required"));
        }
        String store = blank(storeNo) ? null : storeNo.trim();
        if (store != null && !merchants.storeNos(merchantNo).contains(store)) {
            return new Scope(null, null, fail(FORBIDDEN, "store not in merchant"));
        }
        Set<String> allowed = null;
        if (!blank(accountNo)) {
            BizContext ctx = resolver.getIfAvailable(() -> BizIdentityResolver.NONE).resolve(accountNo, store);
            if (ctx == null || !merchantNo.equals(ctx.merchantNo())) {
                return new Scope(null, null, fail(FORBIDDEN, "account not in merchant"));
            }
            allowed = ctx.allowedStoresOrAll();
            if (allowed != null && (store == null ? allowed.isEmpty() : !allowed.contains(store))) {
                return new Scope(null, null, fail(FORBIDDEN, "store not granted"));
            }
        }
        // 商户级（没指定门店）：属主 = 不按门店过滤（含没有门店号的历史单，与工作台同）；员工 = 他有权的那几家
        Collection<String> storeNos = store != null ? List.of(store) : allowed;
        return new Scope(storeNos, store, null);
    }

    private record Range(LocalDate from, LocalDate to, ResponseEntity<Map<String, Object>> error) {
    }

    /** 毫秒边界 → 本地日（JVM 时区，与工作台切日同一条时间轴），含头含尾 */
    private static Range range(Map<String, Object> body) {
        Long from = longOf(body == null ? null : body.get("date_from"));
        Long to = longOf(body == null ? null : body.get("date_to"));
        if (from == null || to == null || from > to) {
            return new Range(null, null, fail(BAD_PARAM, "date_from/date_to invalid"));
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate f = Instant.ofEpochMilli(from).atZone(zone).toLocalDate();
        LocalDate t = Instant.ofEpochMilli(to).atZone(zone).toLocalDate();
        if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_DAYS) {
            return new Range(null, null, fail(RANGE_TOO_LARGE, "range exceeds " + MAX_DAYS + " days"));
        }
        return new Range(f, t, null);
    }

    // ---------------------------------------------------------------- 外壳与小工具

    private boolean authorized(String given) {
        if (given == null || token == null || token.isBlank()) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                given.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static ResponseEntity<Map<String, Object>> unauthorized() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    private static ResponseEntity<Map<String, Object>> ok(Object data, String basis) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("currency", CURRENCY);
        if (basis != null) {
            meta.put("data_basis", basis);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", OK);
        out.put("msg", "ok");
        out.put("data", data);
        out.put("meta", meta);
        return ResponseEntity.ok(out);
    }

    /** 业务错误回 HTTP 200 + 非零 code（契约 §0.2），soukmind 按 code 措辞 */
    private static ResponseEntity<Map<String, Object>> fail(int code, String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("msg", msg);
        out.put("data", null);
        return ResponseEntity.ok(out);
    }

    private List<Map<String, Object>> stores(Collection<String> storeNos) {
        if (storeNos == null || storeNos.isEmpty()) {
            return List.of();
        }
        Map<String, String> names = merchants.storeNames(storeNos);
        return storeNos.stream().sorted().map(s -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("store_id", s);
            m.put("name", names.getOrDefault(s, s));
            return m;
        }).toList();
    }

    /** 业务系统角色码：属主 OWNER；员工取当前店上的角色（多个取字典序第一个·soukmind 只做粗映射） */
    private static String roleOf(BizContext ctx) {
        if (ctx.owner()) {
            return "OWNER";
        }
        return ctx.staffRoles().stream().sorted().findFirst().orElse("STAFF");
    }

    private static Map<String, Object> skuMap(AiGoodsService.SkuRow s, Integer warning) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("skuId", s.skuNo());
        m.put("spuId", s.goodsNo());
        m.put("productName", s.title());
        m.put("specValueStr", s.spec());
        m.put("stock", s.stock());
        if (warning != null) {
            m.put("stockWarning", warning);
        }
        m.put("soldOut", s.stock() <= 0);
        m.put("price", yuan(s.price()));
        return m;
    }

    private static String storeOf(String header, Map<String, Object> body) {
        return blank(header) ? str(body, "store_id") : header;
    }

    private static boolean asc(Map<String, Object> body) {
        return "ASC".equalsIgnoreCase(str(body, "order"));
    }

    private static int limit(Map<String, Object> body, int dflt, int max) {
        Integer n = integer(body == null ? null : body.get("limit"));
        return n == null || n <= 0 ? dflt : Math.min(n, max);
    }

    private static BigDecimal yuan(long minor) {
        return BigDecimal.valueOf(minor).movePointLeft(2).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** 金额类分 → 元；计数/比率原样（比率保留两位） */
    private static Object num(Double v, boolean money) {
        if (v == null) {
            return null;
        }
        if (money) {
            return BigDecimal.valueOf(v).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
        }
        return v == Math.rint(v) ? (Object) v.longValue() : BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP);
    }

    private static String str(Map<String, Object> body, String key) {
        Object v = body == null ? null : body.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v).trim();
    }

    private static Long longOf(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? null : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(Object v) {
        Long l = longOf(v);
        return l == null ? null : (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, l));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
