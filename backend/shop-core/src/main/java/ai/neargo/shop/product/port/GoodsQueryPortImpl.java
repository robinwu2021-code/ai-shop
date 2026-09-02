package ai.neargo.shop.product.port;

import ai.neargo.shop.product.service.impl.GoodsServiceImpl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** {@link GoodsQueryPort} 实现。查询同样要豁免数据域，理由见 {@link GoodsServiceImpl} 类注释。 */
@Component
public class GoodsQueryPortImpl implements GoodsQueryPort {

    private static final String MARKET_CN = "CN";

    private final SkuMapper skuMapper;
    private final GoodsMapper goodsMapper;
    private final ObjectMapper json;
    /** 限时特价要覆盖售价。product → marketing 走 Port，不直连（ArchUnit 守着） */
    private final CampaignPort campaignPort;

    /** 门店价：取价入口唯一的覆盖层来源 */
    private final ai.neargo.shop.product.mapper.ProductMappers.StorePriceMapper storePriceMapper;

    public GoodsQueryPortImpl(SkuMapper skuMapper, GoodsMapper goodsMapper, ObjectMapper json,
                              ai.neargo.shop.product.mapper.ProductMappers.StorePriceMapper storePriceMapper,
                              CampaignPort campaignPort) {
        this.storePriceMapper = storePriceMapper;
        this.skuMapper = skuMapper;
        this.goodsMapper = goodsMapper;
        this.json = json;
        this.campaignPort = campaignPort;
    }

    @Override
    public java.util.Optional<SkuSnapshot> snapshotOfGoods(String goodsNo) {
        if (goodsNo == null || goodsNo.isBlank()) {
            return java.util.Optional.empty();
        }
        // 取首个 SKU：开团、分享这类「以商品为单位」的动作只需要一份代表性的价与库存
        PrdSku sku = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectOne(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, goodsNo)
                        .eq(PrdSku::getMarket, MARKET_CN)
                        .orderByAsc(PrdSku::getId)
                        .last("limit 1")));
        if (sku == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(snapshot(List.of(sku.getSkuNo())).get(sku.getSkuNo()));
    }

    @Override
    public Map<String, SkuSnapshot> snapshot(List<String> skuNos) {
        return snapshot(skuNos, Map.of());
    }

    @Override
    public Map<String, Long> storePrices(Map<String, String> storeByEntity, List<String> skuNos) {
        if (storeByEntity == null || storeByEntity.isEmpty() || skuNos == null || skuNos.isEmpty()) {
            return Map.of();
        }
        List<String> storeNos = storeByEntity.values().stream()
                .filter(no -> no != null && !no.isBlank()).distinct().toList();
        if (storeNos.isEmpty()) {
            return Map.of();
        }
        /*
         * 一次查完再按主体过滤，而不是按店逐次查：一单最多几家商家，
         * 而「多查几行再丢掉」比「多打几次库」便宜得多。
         */
        List<ai.neargo.shop.product.entity.PrdStorePrice> rows =
                DataScopeContext.executeWithoutScope(() -> storePriceMapper.selectList(
                        Wrappers.<ai.neargo.shop.product.entity.PrdStorePrice>lambdaQuery()
                                .in(ai.neargo.shop.product.entity.PrdStorePrice::getSkuNo, skuNos)
                                .in(ai.neargo.shop.product.entity.PrdStorePrice::getStoreNo, storeNos)
                                .eq(ai.neargo.shop.product.entity.PrdStorePrice::getMarket, MARKET_CN)));
        Map<String, Long> out = new HashMap<>();
        for (var r : rows) {
            // 只认「这家主体这一单要走的那家店」的行：别的店的价不该串进来
            if (r.getPrice() != null && r.getStoreNo().equals(storeByEntity.get(r.getEntityNo()))) {
                out.put(r.getSkuNo(), r.getPrice());
            }
        }
        return out;
    }

    @Override
    public Map<String, SkuSnapshot> snapshot(List<String> skuNos, Map<String, String> storeByEntity) {
        if (skuNos == null || skuNos.isEmpty()) {
            return Map.of();
        }
        /*
         * ★ 显式豁免：这是**下单与购物车共用的唯一价格入口**，调用方是 C 端会话（SELF 维度）。
         * prd_sku 登记 MERCHANT 锚点之后不豁免就是 1=0 —— 症状是「购物车空了、下单说商品不存在」，
         * 而日志干净。同一个类里 snapshotOfGoods 一直是豁免的，这一条漏了。
         */
        List<PrdSku> skus = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .in(PrdSku::getSkuNo, skuNos)
                        .eq(PrdSku::getMarket, MARKET_CN)));
        if (skus.isEmpty()) {
            return Map.of();
        }

        List<String> goodsNos = skus.stream().map(PrdSku::getGoodsNo).distinct().toList();
        Map<String, PrdGoods> goodsMap = DataScopeContext.executeWithoutScope(() ->
                        goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery().in(PrdGoods::getGoodsNo, goodsNos)))
                .stream().collect(Collectors.toMap(PrdGoods::getGoodsNo, Function.identity(), (a, b) -> a));

        /*
         * 限时特价：**在这里覆盖价格，而不是在调用方**。
         *
         * <p>snapshot() 是下单、预览、购物车三条路共用的唯一价格入口 ——
         * 放在这里，三条路自动一致；放在调用方就要改三处，而漏掉一处的症状是
         * 「购物车显示特价、下单按原价扣钱」，最难查的那类。
         *
         * <p>下单时**重新查一次**而不是信端上传来的价：活动可能在用户
         * 加购之后、提交之前结束。以下单那一刻为准是唯一说得清的口径。
         */
        /*
         * 多规格商品**不套用**商品级特价：活动价只有一个，套上去会把
         * 20 斤装拉到 10 斤装的价。这是防御历史数据 —— 新建活动已经在
         * CampaignService 里拦住了，但库里可能已经有这种活动。
         *
         * 宁可「特价不生效」也不能「按错的价卖」：前者商家会来问，后者没人会发现。
         */
        Map<String, Integer> counts = skuCounts(goodsNos);
        Map<String, Long> flash = new HashMap<>(campaignPort.flashPrices(goodsNos));
        flash.keySet().removeIf(no -> counts.getOrDefault(no, 1) > 1);

        /*
         * 门店价：**基准价的覆盖层，落在特价之前**（批 C）。
         *
         * 顺序不能反 —— 反了就是「活动期间按门店价卖」，而活动是平台承诺给买家的。
         * 没有门店行的 SKU 保持主体价（fail-back），与库存的「无行视为 0」刻意相反：
         * 价格视为 0 就是白送。
         */
        Map<String, Long> storePrice = storePrices(storeByEntity, skuNos);

        Map<String, SkuSnapshot> result = new HashMap<>();
        for (PrdSku sku : skus) {
            PrdGoods g = goodsMap.get(sku.getGoodsNo());
            if (g == null) {
                continue;
            }
            int available = nz(sku.getStock()) - nz(sku.getLockedStock());
            result.put(sku.getSkuNo(), new SkuSnapshot(
                    sku.getSkuNo(), sku.getGoodsNo(), sku.getEntityNo(),
                    g.getTitle(), g.getCover(), sku.getSpec(), g.getType(), g.getCategoryNo(),
                    flash.getOrDefault(sku.getGoodsNo(),
                            storePrice.getOrDefault(sku.getSkuNo(),
                                    sku.getPrice() == null ? 0L : sku.getPrice())),
                    Math.max(available, 0),
                    Boolean.TRUE.equals(g.getOnSale()) && "APPROVED".equals(g.getAuditStatus()),
                    readList(g.getFulfillments()),
                    g.getGroupPriceMinor(), g.getGroupMinCount()));
        }
        return result;
    }

    /**
     * 待审积压。**两个数一起查**，见接口注释。
     *
     * <p><b>不绕数据域</b>。第一版写的是 `executeWithoutScope`（照抄了看板上
     * 「待审商家」那一格），理由是「看板是平台视角」。这个理由站不住：
     * 这张卡是**待办**，点进去落到 `/ops/goods/audit-queue`，而那条队列<b>是接域的</b>。
     * 绕开的后果是配了商家域的审核员看到「194 件待审」、点进去只有 3 件 ——
     * 卡片朝着「更吓人」的方向撒谎，而这正是 {@code OpsDataScopeFlowTest}
     * 开头那段说的「看着生效、实际没有的限制」的同一类问题。
     *
     * <p>接上之后卡片与队列对任何人都是同一个数。社区域 / 自提点域的运营会得到 0
     * （`prd_goods` 只有商家锚点，fail-closed），而他们的队列同样是 0 —— 仍然一致。
     */
    @Override
    public AuditBacklog auditBacklog() {
        /*
         * 一次聚合查询取两个数，**不把行捞出来数**。第一版是 selectList 之后
         * .size()：194 行时无所谓，但这是看板端点，每次打开都要跑，
         * 而积压变大正是这张卡存在的场景 —— 它最该省的时候最费。
         */
        Map<String, Object> row = goodsMapper.selectMaps(Wrappers.<PrdGoods>query()
                        .select("COUNT(*) AS cnt", "MIN(updated_at) AS oldest")
                        .eq("audit_status", "AUDITING")
                        .eq("deleted", 0))
                .stream().findFirst().orElse(Map.of());

        long cnt = row.get("cnt") instanceof Number n ? n.longValue() : 0L;
        if (cnt == 0L) {
            return new AuditBacklog(0L, 0L);
        }
        /*
         * 用 `updated_at` 而不是 `created_at`：商家改一版重新提交，
         * 等待是从**这一次提交**开始算的。用创建时间会把「刚改完又交上来」
         * 的那件也算成等了两周，而催的人会去催一件其实刚到的单子。
         */
        Object oldest = row.get("oldest");
        LocalDateTime at = oldest instanceof LocalDateTime t ? t
                : oldest instanceof java.sql.Timestamp ts ? ts.toLocalDateTime() : null;
        long days = at == null ? 0L
                : Math.max(0L, java.time.Duration.between(at, LocalDateTime.now()).toDays());
        return new AuditBacklog(cnt, days);
    }

    @Override
    public Map<String, Integer> skuCounts(java.util.Collection<String> goodsNos) {
        if (goodsNos == null || goodsNos.isEmpty()) {
            return Map.of();
        }
        List<PrdSku> all = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .in(PrdSku::getGoodsNo, goodsNos)
                        .eq(PrdSku::getMarket, MARKET_CN)));
        Map<String, Integer> out = new HashMap<>();
        for (PrdSku s : all) {
            out.merge(s.getGoodsNo(), 1, Integer::sum);
        }
        return out;
    }

    private List<String> readList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
