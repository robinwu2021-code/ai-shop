package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.dto.SpecTemplateVO;
import ai.neargo.shop.product.entity.PrdCommunityPool;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdStoreStock;
import ai.neargo.shop.product.entity.PrdSpecTemplate;
import ai.neargo.shop.product.mapper.ProductMappers.CommunityPoolMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecTemplateMapper;
import ai.neargo.shop.product.service.GoodsService;
import ai.neargo.shop.product.service.MerchantGoodsService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@link MerchantGoodsService} 实现。 */
@Service
public class MerchantGoodsServiceImpl implements MerchantGoodsService {

    private static final String AUDITING = "AUDITING";
    private static final String APPROVED = "APPROVED";
    private static final String REJECTED = "REJECTED";
    /** 一期只做单市场（CN）；priceByMarket 里的其余市场各写一行 SKU。 */
    private static final String HOME_MARKET = "CN";

    private final GoodsMapper goodsMapper;
    private final SkuMapper skuMapper;
    private final SpecTemplateMapper templateMapper;
    private final GoodsService goodsService;
    private final CommunityPoolMapper poolMapper;
    private final ai.neargo.shop.spi.user.MerchantQueryPort merchantPort;
    private final ai.neargo.shop.spi.user.AdmissionPort admissionPort;
    private final ObjectMapper json;

    private final ai.neargo.shop.product.service.CategoryService categoryService;
    /** 门店级库存。**只有商家显式设置过才有行** —— 见 saveStoreStock 的说明 */
    private final ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper storeStockMapper;
    /** 门店级上架关系。与库存同一套「有行按店算、无行回退主体」的语义 */
    private final ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper storeGoodsMapper;

    public MerchantGoodsServiceImpl(GoodsMapper goodsMapper, SkuMapper skuMapper,
                                    SpecTemplateMapper templateMapper,
                                    GoodsService goodsService, CommunityPoolMapper poolMapper,
                                    ai.neargo.shop.spi.user.MerchantQueryPort merchantPort,
                                    ai.neargo.shop.spi.user.AdmissionPort admissionPort,
                                    ai.neargo.shop.product.service.CategoryService categoryService,
                                    ai.neargo.shop.product.mapper.ProductMappers.StoreStockMapper storeStockMapper,
                                    ai.neargo.shop.product.mapper.ProductMappers.StoreGoodsMapper storeGoodsMapper,
                                    ObjectMapper json) {
        this.storeStockMapper = storeStockMapper;
        this.storeGoodsMapper = storeGoodsMapper;
        this.categoryService = categoryService;
        this.poolMapper = poolMapper;
        this.merchantPort = merchantPort;
        this.admissionPort = admissionPort;
        this.goodsMapper = goodsMapper;
        this.skuMapper = skuMapper;
        this.templateMapper = templateMapper;
        this.goodsService = goodsService;
        this.json = json;
    }

    // ---------------------------------------------------------------- 查询

    @Override
    public PageData<GoodsVO> list(String merchantNo, String categoryNo, String keyword, String status, long page, long size) {
        /*
         * merchantNo 为空 = **跨商家查**，给平台审核队列/商品池用。
         * 不做这个判空的话 MyBatis-Plus 会生成 `entity_no = null`，一行都查不到 ——
         * 而平台侧看到的是「没有待审商品」，与「审完了」长得一模一样。
         */
        var w = Wrappers.<PrdGoods>lambdaQuery()
                .eq(merchantNo != null && !merchantNo.isBlank(), PrdGoods::getEntityNo, merchantNo)
                .eq(categoryNo != null && !categoryNo.isBlank(), PrdGoods::getCategoryNo, categoryNo)
                .like(keyword != null && !keyword.isBlank(), PrdGoods::getTitle, keyword);
        applyStatus(w, status);
        // 新建的排在前面：店主刚录完一件商品，第一件事是看它在不在
        w.orderByDesc(PrdGoods::getId);
        /*
         * **豁免数据域是必须的，不是没做**：这个方法与 B 端商家商品列表共用
         * （BizGoodsController#list），而 B 端会话的维度是 SELF ——
         * prd_goods 只有 MERCHANT 锚点，接上就是 1=0，商家的商品列表当场全空。
         *
         * 运营端的待审队列已经拆到 auditQueue()，那一条**是接数据域的**。
         */
        Page<PrdGoods> p = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectPage(Page.of(page, size), w));
        List<GoodsVO> rows = p.getRecords().stream().map(this::toVO).toList();
        return PageData.of(rows, p.getTotal(), page, size);
    }

    @Override
    public PageData<GoodsVO> auditQueue(long page, long size) {
        /*
         * ★ 这里**没有** executeWithoutScope —— 批③ 的核心就是这一行的缺席：
         * 配了商家域的审核员只看到自己负责那几家的待审商品。
         * 会话不带任何维度（超管 / ALL）时 DataScopeHandler 直接放行，与今天一致。
         */
        var w = Wrappers.<PrdGoods>lambdaQuery();
        applyStatus(w, "PENDING");
        w.orderByDesc(PrdGoods::getId);
        Page<PrdGoods> p = goodsMapper.selectPage(Page.of(page, size), w);
        List<GoodsVO> rows = p.getRecords().stream().map(this::toVO).toList();
        return PageData.of(rows, p.getTotal(), page, size);
    }

    @Override
    public PageData<ai.neargo.shop.product.dto.OpsGoodsListVO> listForOps(
            String merchantNo, String categoryNo, String keyword, String status,
            String storeNo, long page, long size) {
        /*
         * 门店投影（P-11.2.1e）：带 storeNo 时查询范围**强制收敛到该店的主体**——
         * 商品挂主体不挂门店（ADR-011 双键模型），「门店的商品」= 主体商品池 ×
         * 该店库存/在售的投影，不是给商品加 store_no。
         */
        if (storeNo != null && !storeNo.isBlank()) {
            String entityNo = merchantPort.entityOfStores(List.of(storeNo)).get(storeNo);
            if (entityNo == null) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            merchantNo = entityNo;
        }
        var w = Wrappers.<PrdGoods>lambdaQuery()
                .eq(merchantNo != null && !merchantNo.isBlank(), PrdGoods::getEntityNo, merchantNo)
                .eq(categoryNo != null && !categoryNo.isBlank(), PrdGoods::getCategoryNo, categoryNo)
                .like(keyword != null && !keyword.isBlank(), PrdGoods::getTitle, keyword);
        applyStatus(w, status);
        w.orderByDesc(PrdGoods::getId);
        /*
         * ★ 接数据域（批③）：这个方法**只有运营调**（GET /ops/goods），
         * 配了商家域的运营只看到自己负责那几家的商品。
         * 上面按 storeNo 收敛出的 merchantNo 是**用户给的过滤条件**，
         * 与数据域是两件事 —— 传一个不属于自己域的 storeNo，过滤条件成立而数据域拒绝，
         * 结果是空列表，正确。
         */
        Page<PrdGoods> p = goodsMapper.selectPage(Page.of(page, size), w);
        if (p.getRecords().isEmpty()) {
            return PageData.empty(page, size);
        }

        List<String> goodsNos = p.getRecords().stream().map(PrdGoods::getGoodsNo).toList();
        /*
         * **不按 market 过滤**——与 GoodsServiceImpl.loadSkus() 的关键差别。那边只要 CN
         * 一行给买家看；这里要把同一个 skuNo 在各市场的行都捞出来，按 skuNo 分组、
         * 组内再按 market 摊成一张价格表，运营才看得出"这件商品缺了哪个市场的价"。
         */
        Map<String, List<PrdSku>> skusByGoods = skuMapper.selectList(
                        Wrappers.<PrdSku>lambdaQuery().in(PrdSku::getGoodsNo, goodsNos)).stream()
                .collect(java.util.stream.Collectors.groupingBy(PrdSku::getGoodsNo));

        Set<String> merchantNos = p.getRecords().stream().map(PrdGoods::getEntityNo).collect(java.util.stream.Collectors.toSet());
        Map<String, ai.neargo.shop.spi.user.MerchantQueryPort.MerchantBrief> merchants = merchantPort.findAll(merchantNos);

        // 类目名批量拼——prd_goods 只存 categoryNo，名字要跟类目表对一遍。总量有限，一次性取全表比按需查 N 次划算
        Map<String, String> categoryNames = categoryService.list(null, null, true).stream()
                .collect(java.util.stream.Collectors.toMap(
                        ai.neargo.shop.product.dto.OpsCategoryVO::categoryNo,
                        ai.neargo.shop.product.dto.OpsCategoryVO::name));

        StoreProjection projection = storeNo == null || storeNo.isBlank()
                ? null
                : loadStoreProjection(storeNo, goodsNos,
                        skusByGoods.values().stream().flatMap(List::stream).map(PrdSku::getSkuNo).distinct().toList());

        List<ai.neargo.shop.product.dto.OpsGoodsListVO> rows = p.getRecords().stream()
                .map(g -> toOpsListVO(g, skusByGoods.getOrDefault(g.getGoodsNo(), List.of()),
                        merchants, categoryNames, projection))
                .toList();
        return PageData.of(rows, p.getTotal(), page, size);
    }

    /**
     * 门店投影的两张覆盖表，语义都是「有任意行即按店管理，没有行的店视为 0 / 未上架」。
     *
     * @param managedGoods   已转店级管理的商品（任意门店有行）
     * @param onSaleAtStore  该店在售的商品
     * @param managedSkus    已启用分店库存的 SKU（任意门店有行）
     * @param stockAtStore   该店的可用库存（stock - locked）
     */
    private record StoreProjection(Set<String> managedGoods, Set<String> onSaleAtStore,
                                   Set<String> managedSkus, Map<String, Integer> stockAtStore) {
    }

    private StoreProjection loadStoreProjection(String storeNo, List<String> goodsNos, List<String> skuNos) {
        List<ai.neargo.shop.product.entity.PrdStoreGoods> goodsRows = DataScopeContext.executeWithoutScope(() ->
                storeGoodsMapper.selectList(Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                        .in(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo, goodsNos)));
        Set<String> managedGoods = goodsRows.stream()
                .map(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> onSaleAtStore = goodsRows.stream()
                .filter(r -> storeNo.equals(r.getStoreNo()) && Boolean.TRUE.equals(r.getOnSale()))
                .map(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo)
                .collect(java.util.stream.Collectors.toSet());

        List<PrdStoreStock> stockRows = skuNos.isEmpty() ? List.of()
                : DataScopeContext.executeWithoutScope(() ->
                        storeStockMapper.selectList(Wrappers.<PrdStoreStock>lambdaQuery()
                                .in(PrdStoreStock::getSkuNo, skuNos)));
        Set<String> managedSkus = stockRows.stream().map(PrdStoreStock::getSkuNo)
                .collect(java.util.stream.Collectors.toSet());
        Map<String, Integer> stockAtStore = stockRows.stream()
                .filter(r -> storeNo.equals(r.getStoreNo()))
                .collect(java.util.stream.Collectors.toMap(PrdStoreStock::getSkuNo,
                        r -> Math.max(nz(r.getStock()) - nz(r.getLockedStock()), 0), (a, b) -> a));
        return new StoreProjection(managedGoods, onSaleAtStore, managedSkus, stockAtStore);
    }

    private ai.neargo.shop.product.dto.OpsGoodsListVO toOpsListVO(
            PrdGoods g, List<PrdSku> skus,
            Map<String, ai.neargo.shop.spi.user.MerchantQueryPort.MerchantBrief> merchants,
            Map<String, String> categoryNames,
            StoreProjection projection) {
        Map<String, String> titleI18n = readMap(g.getTitleI18n());
        var merchant = merchants.get(g.getEntityNo());

        Map<String, List<PrdSku>> byLogicalSku = skus.stream()
                .collect(java.util.stream.Collectors.groupingBy(PrdSku::getSkuNo, LinkedHashMap::new, java.util.stream.Collectors.toList()));
        List<ai.neargo.shop.product.dto.OpsGoodsListVO.OpsSkuVO> skuVOs = byLogicalSku.values().stream()
                .map(rows -> {
                    PrdSku any = rows.get(0);
                    Map<String, Long> prices = rows.stream()
                            .filter(r -> r.getPrice() != null)
                            .collect(java.util.stream.Collectors.toMap(PrdSku::getMarket, PrdSku::getPrice, (a, b) -> a));
                    Integer storeStock = projection == null || !projection.managedSkus().contains(any.getSkuNo())
                            ? null
                            : projection.stockAtStore().getOrDefault(any.getSkuNo(), 0);
                    return new ai.neargo.shop.product.dto.OpsGoodsListVO.OpsSkuVO(
                            any.getSkuNo(), readList(any.getOptionValues()), any.getSpec(),
                            prices, any.getStock() == null ? 0 : any.getStock(), storeStock);
                })
                .toList();

        Boolean storeOnSale = projection == null || !projection.managedGoods().contains(g.getGoodsNo())
                ? null
                : projection.onSaleAtStore().contains(g.getGoodsNo());

        return new ai.neargo.shop.product.dto.OpsGoodsListVO(
                g.getGoodsNo(),
                new ai.neargo.shop.product.dto.OpsGoodsListVO.TitleVO(g.getTitle(), titleI18n.get("en"), titleI18n.get("ar")),
                g.getCover(), g.getEntityNo(), merchant == null ? g.getEntityNo() : merchant.merchantName(),
                g.getCategoryNo(), g.getCategoryNo() == null ? null : categoryNames.get(g.getCategoryNo()),
                opsStatusOf(g), skuVOs, storeOnSale);
    }

    /**
     * {@code PENDING/ON_SALE/OFF_SALE/REJECTED} → 商家侧那四个状态码，和 GoodsVO.status 同一套口径。
     * <b>待审对外叫 PENDING</b>（词典 §11），库里那列仍是 AUDITING。
     *
     * <p><b>故意不复用同名的 {@link #statusOf}</b>：那个按"当前门店"算在售与否
     * （读 {@code BizContext.currentStoreNo()}），运营端跨商家浏览没有"当前门店"这个概念——
     * 这里只看 {@code prd_goods.on_sale} 这个主体级字段，多门店的细分展示留给以后真要做门店维度筛选时再加。
     */
    private String opsStatusOf(PrdGoods g) {
        if (AUDITING.equals(g.getAuditStatus())) {
            return AUDITING;
        }
        if (REJECTED.equals(g.getAuditStatus())) {
            return REJECTED;
        }
        return Boolean.TRUE.equals(g.getOnSale()) ? "ON_SALE" : "OFF_SALE";
    }

    private Map<String, String> readMap(String json1) {
        if (json1 == null || json1.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(json1, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
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

    @Override
    public GoodsVO detail(String merchantNo, String goodsNo) {
        return toVO(mine(merchantNo, goodsNo));
    }

    // ---------------------------------------------------------------- 保存

    @Override
    @Transactional
    public GoodsVO save(String merchantNo, SaveCommand cmd) {
        if (cmd.title() == null || cmd.title().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (cmd.skus() == null || cmd.skus().isEmpty()) {
            // 没有 SKU 的商品价格无从谈起，上架后 C 端会显示 ¥0
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        boolean isNew = cmd.goodsNo() == null || cmd.goodsNo().isBlank();
        PrdGoods g = isNew ? newGoods(merchantNo) : mine(merchantNo, cmd.goodsNo());

        g.setTitle(cmd.title());
        g.setSubtitle(cmd.subtitle());
        g.setTitleI18n(writeMap(cmd.titleI18n()));
        g.setSubtitleI18n(writeMap(cmd.subtitleI18n()));
        g.setType(cmd.type() == null ? "NORMAL" : cmd.type());
        /*
         * 类目：**空串按「不归类」处理，不是「归到一个叫空的类目」**。
         * 不做这层转换的话，库里会出现 category_no='' 的商品 ——
         * 它既不出现在任何类目筛选里，也不为空，查起来看不出哪里不对。
         *
         * 这里不校验经营资质：那是上架时的事（见 requireCategoryAuthorized 的注释）。
         */
        if (cmd.fulfillments() != null) {
            /*
             * 履约方式此前是「有字段没入口」：建商品时写死 ["STORE_PICKUP"]，商家改不了，
             * 于是「这件商品支持怎么送」在商品侧从未被真正表达过。
             *
             * 空数组要拒掉而不是当成「不改」：一件一种履约都不支持的商品谁也买不了，
             * 而它在列表里看起来与正常商品毫无区别。不传（null）才是「不改」。
             */
            if (cmd.fulfillments().isEmpty()) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            for (String f : cmd.fulfillments()) {
                if (!Fulfillments.isValid(f)) {
                    throw BizException.of(ErrorCode.BAD_REQUEST);
                }
            }
            g.setFulfillments(writeJson(cmd.fulfillments()));
        }
        if (cmd.categoryNo() != null) {
            g.setCategoryNo(cmd.categoryNo().isBlank() ? null : cmd.categoryNo());
        }
        g.setCover(cmd.cover() == null ? "" : cmd.cover());
        g.setImages(writeJson(cmd.images()));
        g.setSpecGroups(writeSpecGroups(cmd.specGroups()));
        /*
         * **改动后回到待审核，并强制下架。**
         *
         * 不这样做的话，「上架一件白菜 → 审核通过 → 改成别的东西继续卖」是一条通路，
         * 而审核在这条路上完全不起作用。代价是商家改个错别字也要重审 ——
         * 一期审核是人工的，这个代价由平台承担，不该由买家承担。
         */
        g.setAuditStatus(AUDITING);
        g.setOnSale(false);

        if (isNew) {
            DataScopeContext.executeWithoutScope(() -> goodsMapper.insert(g));
        } else {
            DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        }
        saveSkus(merchantNo, g.getGoodsNo(), cmd.skus(), cmd.specGroups());
        // 改动后强制下架，池要跟着撤 —— 否则改成别的东西之后，旧条目还挂在买家的社区列表里
        syncPool(g, false);
        return toVO(g);
    }

    /**
     * SKU 全量替换，但<b>保留原编号</b>：历史订单、购物车、库存锁都指向 skuNo，
     * 换编号等于让它们指向一个不存在的东西。
     */
    private void saveSkus(String merchantNo, String goodsNo, List<Sku> skus,
                          List<SpecGroup> groups) {
        List<PrdSku> existing = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, goodsNo)));
        Map<String, PrdSku> byNo = new LinkedHashMap<>();
        for (PrdSku s : existing) {
            byNo.put(s.getSkuNo() + "@" + s.getMarket(), s);
        }

        List<String> kept = new ArrayList<>();
        for (Sku sku : skus) {
            String skuNo = sku.skuNo() == null || sku.skuNo().isBlank()
                    ? BizKey.next(BizKey.SKU) : sku.skuNo();
            // 按市场逐行写：(entity_no, sku_no, market) 是价格的唯一权威，
            // 一个 SKU 在三个市场就是三行，而不是一行里塞一个 JSON 价格表
            Map<String, Long> prices = new LinkedHashMap<>();
            prices.put(HOME_MARKET, sku.price());
            if (sku.priceByMarket() != null) {
                prices.putAll(sku.priceByMarket());
            }
            for (var e : prices.entrySet()) {
                String key = skuNo + "@" + e.getKey();
                PrdSku row = byNo.get(key);
                boolean fresh = row == null;
                if (fresh) {
                    row = new PrdSku();
                    row.setSkuNo(skuNo);
                    row.setGoodsNo(goodsNo);
                    row.setEntityNo(merchantNo);
                    row.setMarket(e.getKey());
                    row.setLockedStock(0);
                }
                row.setOptionValues(writeJson(sku.optionValues()));
                row.setSpec(String.join(" · ", sku.optionValues() == null ? List.of() : sku.optionValues()));
                row.setPrice(e.getValue());
                row.setStock(sku.stock());
                PrdSku toSave = row;
                DataScopeContext.executeWithoutScope(() ->
                        fresh ? skuMapper.insert(toSave) : skuMapper.updateById(toSave));
                kept.add(key);
            }
        }

        // 被删掉的规格行：逻辑删除而不是物理删 —— 历史订单要能查回"当时买的是哪个规格"
        for (var e : byNo.entrySet()) {
            if (!kept.contains(e.getKey())) {
                DataScopeContext.executeWithoutScope(() -> skuMapper.deleteById(e.getValue().getId()));
            }
        }
        // groups 已写在 goods 上，这里只用于生成 spec 文案，不再单独落库
        if (groups == null) {
            return;
        }
    }

    // ---------------------------------------------------------------- 上下架 / 库存

    @Override
    @Transactional
    public GoodsVO toggle(String merchantNo, String goodsNo, boolean onSale) {
        PrdGoods g = mine(merchantNo, goodsNo);
        // 未过审不能上架：上架是商家自己能按的按钮，能把 AUDITING 推到 C 端的话审核就形同虚设
        if (onSale && !APPROVED.equals(g.getAuditStatus())) {
            // 专用码：此前用的是 ORDER_STATE_ILLEGAL，商家看到「订单状态不允许该操作」
            // 而他手上一张订单都没有
            throw BizException.of(ErrorCode.GOODS_NOT_APPROVED);
        }
        if (onSale) {
            requireCategoryAuthorized(merchantNo, g.getCategoryNo());
        }
        String storeNo = ai.neargo.shop.auth.BizContext.current().currentStoreNo();
        /*
         * 多门店商家的上下架落在**门店行**上，不动主体的 on_sale。
         *
         * 不分的话，A 店店长点一下「下架」，B 店的货跟着一起没了 —— 而他做的
         * 只是「今天我这儿不卖了」。这与库存那处是同一条理由：钱与货的作用域
         * 必须与操作人能管的范围对齐。
         *
         * 单店（或没有门店上下文）仍改主体级，行为与改造前逐字相同。
         */
        if (perStore(merchantNo, storeNo, goodsNo)) {
            setStoreOnSale(g, storeNo, onSale);
            // 主体级 on_sale 是「这件货整体还卖不卖」的总闸：任一门店在售就得是开的，
            // 否则 storeOnSale 的 && 会把店级的 true 一起吞掉
            boolean anyOn = onSale || storeGoodsRows(goodsNo).stream()
                    .anyMatch(r -> Boolean.TRUE.equals(r.getOnSale()));
            g.setOnSale(anyOn);
            DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
            syncPool(g, anyOn);
            return toVO(g);
        }

        g.setOnSale(onSale);
        DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        syncPool(g, onSale);
        return toVO(g);
    }

    @Override
    public GoodsVO detailForOps(String goodsNo) {
        /*
         * 不走 toVO()：statusOf/storeSkus 读 BizContext 的「当前门店」，
         * 而运营端请求没有 BizContext —— 这里用主体级口径（opsStatusOf），
         * 与商品池列表同一套状态词。
         */
        return toVOWithoutStoreContext(requireByNoInScope(goodsNo));
    }

    @Override
    @Transactional
    public GoodsVO forceOff(String goodsNo, String reason) {
        if (reason == null || reason.isBlank()) {
            // 与审核驳回同一条规矩：没有原因的处置在申诉时站不住
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PrdGoods g = requireByNo(goodsNo);
        /*
         * 强制下架 = 撤销过审（TDD D1）。不新增状态值：REJECTED 之后商家改商品
         * 重新提审走的是**既有**链路，B 端也已经会渲染「被驳回 + 原因」。
         * 与首次驳回靠原因前缀区分。
         */
        g.setAuditStatus(REJECTED);
        g.setAuditReason("平台强制下架：" + reason.trim());
        g.setOnSale(false);
        DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        // 店级行全下且**不打 platform_suspended**：商品要重新过审才能回来，
        // 恢复走审核链路，不走门店解除处置那条标记
        for (var row : storeGoodsRows(goodsNo)) {
            if (Boolean.TRUE.equals(row.getOnSale())) {
                row.setOnSale(false);
                DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.updateById(row));
            }
        }
        syncPool(g, false);
        return toVOWithoutStoreContext(g);
    }

    @Override
    @Transactional
    public GoodsVO platformSuspendGoods(String goodsNo, String reason) {
        if (reason == null || reason.isBlank()) {
            // 与审核驳回同一条规矩：没有原因的处置在申诉时站不住
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PrdGoods g = requireByNo(goodsNo);
        // 只有在售的才谈得上「压下架」。已经下架/被驳回的再压一次，
        // 唯一的效果是把上一条更重要的处置原因覆盖掉
        if (!Boolean.TRUE.equals(g.getOnSale()) || REJECTED.equals(g.getAuditStatus())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        /*
         * **不动 auditStatus** —— 这正是与 forceOff 的分界：过审结论还在，
         * 商家处理完问题自己点一下就能重新上架，不必走一遍重新提审。
         */
        g.setAuditReason("平台下架：" + reason.trim());
        g.setOnSale(false);
        DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        for (var row : storeGoodsRows(goodsNo)) {
            if (Boolean.TRUE.equals(row.getOnSale())) {
                row.setOnSale(false);
                DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.updateById(row));
            }
        }
        // 撤池：只改 on_sale 不撤池的话，被压下的商品在 C 端还搜得到 ——
        // 处置没有落到买家看得见的地方，就等于没处置
        syncPool(g, false);
        return toVOWithoutStoreContext(g);
    }

    @Override
    @Transactional
    public void platformOfflineStore(String entityNo, String storeNo) {
        List<PrdGoods> all = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getEntityNo, entityNo)));
        for (PrdGoods g : all) {
            List<ai.neargo.shop.product.entity.PrdStoreGoods> rows = storeGoodsRows(g.getGoodsNo());
            boolean managed = !rows.isEmpty();
            boolean onAtStore = managed
                    ? rows.stream().anyMatch(r -> storeNo.equals(r.getStoreNo())
                            && Boolean.TRUE.equals(r.getOnSale()))
                    : Boolean.TRUE.equals(g.getOnSale());
            if (!onAtStore) {
                // 只压当前在售的：商家自己下架的不打标记，解除时才不会替他重新上架
                continue;
            }
            setStoreOnSale(g, storeNo, false);
            markPlatformSuspended(g.getGoodsNo(), storeNo, true);
            boolean anyOn = storeGoodsRows(g.getGoodsNo()).stream()
                    .anyMatch(r -> Boolean.TRUE.equals(r.getOnSale()));
            g.setOnSale(anyOn);
            DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
            syncPool(g, anyOn);
        }
    }

    @Override
    @Transactional
    public void platformRestoreStore(String entityNo, String storeNo) {
        List<ai.neargo.shop.product.entity.PrdStoreGoods> suspended = DataScopeContext.executeWithoutScope(() ->
                storeGoodsMapper.selectList(Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getStoreNo, storeNo)
                        .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getEntityNo, entityNo)
                        .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getPlatformSuspended, true)));
        for (var row : suspended) {
            row.setOnSale(true);
            row.setPlatformSuspended(false);
            DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.updateById(row));
            PrdGoods g = DataScopeContext.executeWithoutScope(() ->
                    goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                            .eq(PrdGoods::getGoodsNo, row.getGoodsNo()).last("limit 1")));
            if (g == null) {
                continue;
            }
            /*
             * 处置期间商品可能被驳回/强制下架（audit_status 变了）——那种行不能跟着回架：
             * 恢复门店不等于恢复商品，商品要走它自己的重新提审链路。
             */
            if (!APPROVED.equals(g.getAuditStatus())) {
                continue;
            }
            if (!Boolean.TRUE.equals(g.getOnSale())) {
                g.setOnSale(true);
                DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
            }
            syncPool(g, true);
        }
    }

    /** ops 视角的整装 VO：与 {@link #toVO} 的差别只在不读 BizContext（无「当前门店」）。 */
    private GoodsVO toVOWithoutStoreContext(PrdGoods g) {
        GoodsVO base = goodsService.detail(g.getGoodsNo());
        return new GoodsVO(base.goodsNo(), base.title(), base.subtitle(), base.cover(),
                base.images(), base.type(), base.categoryNo(), base.merchant(),
                base.rating(), base.ratingCount(), base.price(), base.originPrice(),
                base.fulfillments(), base.specGroups(), base.skus(), base.sales(),
                base.cutoffAt(), base.arrivalDesc(), base.weighed(), base.origin(),
                base.durationMin(), base.storeName(), base.limitPerUser(), base.onSale(),
                opsStatusOf(g),
                readMap(g.getTitleI18n()), readMap(g.getSubtitleI18n()),
                g.getAuditReason(),
                GoodsServiceImpl.groupBuyConf(g));
    }

    /**
     * 按 goodsNo 取商品，**不接数据域** —— 写路径（强制下架、平台压下架）用。
     *
     * <p>与读路径分成两个方法而不是加一个布尔参数（批② T2 的同一条结论）：
     * 参数化的话，下一个人在处置路径上传错一次，就把一次平台处置变成了
     * <b>静默失败</b> —— 按钮点了、返回 200、商品还在架上。
     */
    private PrdGoods requireByNo(String goodsNo) {
        PrdGoods g = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getGoodsNo, goodsNo).last("limit 1")));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return g;
    }

    /**
     * 同上，但**接数据域**（批③）—— 运营端的只读详情用。
     *
     * <p>不在自己数据域内的商品返回 {@code NOT_FOUND} 而不是 403：
     * 「这件商品不归你管」与「这件商品不存在」在运营端应当是同一个回答 ——
     * 403 会告诉他这个货号真实存在，那本身就是一次信息泄露。
     */
    private PrdGoods requireByNoInScope(String goodsNo) {
        PrdGoods g = goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getGoodsNo, goodsNo).last("limit 1"));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return g;
    }

    private void markPlatformSuspended(String goodsNo, String storeNo, boolean flag) {
        var row = DataScopeContext.executeWithoutScope(() ->
                storeGoodsMapper.selectOne(Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getStoreNo, storeNo)
                        .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo, goodsNo)
                        .last("limit 1")));
        if (row != null) {
            row.setPlatformSuspended(flag);
            DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.updateById(row));
        }
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    /**
     * 这次上下架该落在门店行上，还是主体的 {@code on_sale} 上。
     *
     * <p>判据就是「这个主体有几家店」——<b>多门店时每一次上下架都落成门店行，
     * 包括在默认门店做的那次</b>。
     *
     * <p>这里原先写的是「操作发生在非默认门店」，被测试当场抓住：
     * 老板在默认店 A 上架（走主体级，不写行），再去 B 店上架（写了行），
     * 此时 A 店因为「有行了但没有 A 的行」而变成未上架 —— 他什么都没对 A 做过。
     *
     * <p>单店商家恒为 false，行为与改造前逐字相同。
     */
    private boolean perStore(String merchantNo, String storeNo, String goodsNo) {
        if (storeNo == null || storeNo.isBlank()) {
            return false;
        }
        return merchantPort.storeNos(merchantNo).size() > 1 || !storeGoodsRows(goodsNo).isEmpty();
    }

    /**
     * 写一条门店上架行（有则更新）。**只在多门店时被调用**。
     *
     * <p><b>第一次转成店级管理时，先把其他门店的现状固化下来</b>。
     *
     * <p>不这么做的后果是实测撞到的：两家店都在卖，商家在 A 店点「下架」——
     * 只写了 A 的行，B 因为「有行了但没有自己的行」而一起变成未上架。
     * 他做的只是「A 店今天不卖」，B 店的货却跟着没了，而且没有任何提示。
     *
     * <p>测试当时没抓到，是因为用例先把两家店都显式上架过一遍 ——
     * 正好绕开了转换那一刻。这类「迁移瞬间」的缺陷，写用例时最容易被跳过。
     */
    private void setStoreOnSale(PrdGoods g, String storeNo, boolean onSale) {
        if (storeGoodsRows(g.getGoodsNo()).isEmpty()) {
            boolean current = Boolean.TRUE.equals(g.getOnSale());
            for (String other : merchantPort.storeNos(g.getEntityNo())) {
                if (other.equals(storeNo)) {
                    continue;
                }
                ai.neargo.shop.product.entity.PrdStoreGoods seed =
                        new ai.neargo.shop.product.entity.PrdStoreGoods();
                seed.setStoreNo(other);
                seed.setGoodsNo(g.getGoodsNo());
                seed.setEntityNo(g.getEntityNo());
                seed.setOnSale(current);
                DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.insert(seed));
            }
        }
        writeStoreOnSale(g, storeNo, onSale);
    }

    private void writeStoreOnSale(PrdGoods g, String storeNo, boolean onSale) {
        ai.neargo.shop.product.entity.PrdStoreGoods row =
                DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.selectOne(
                        Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                                .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getStoreNo, storeNo)
                                .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo, g.getGoodsNo())
                                .last("limit 1")));
        if (row == null) {
            row = new ai.neargo.shop.product.entity.PrdStoreGoods();
            row.setStoreNo(storeNo);
            row.setGoodsNo(g.getGoodsNo());
            row.setEntityNo(g.getEntityNo());
            row.setOnSale(onSale);
            ai.neargo.shop.product.entity.PrdStoreGoods toInsert = row;
            DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.insert(toInsert));
            return;
        }
        row.setOnSale(onSale);
        ai.neargo.shop.product.entity.PrdStoreGoods toUpdate = row;
        DataScopeContext.executeWithoutScope(() -> storeGoodsMapper.updateById(toUpdate));
    }

    /**
     * 类目经营准入：这家店有没有获批经营这个类目。
     *
     * <p><b>校验点选在「上架」而不是「保存商品」</b>：商家把草稿归到一个自己还没资质的
     * 类目下不该被拦 —— 他可能正准备去申请。真正不能发生的是这件商品**对消费者可见**。
     *
     * <p>类目没挂 {@code requiredCode} 即无门槛，直接放行；挂了但商家没有，
     * 报一个专用错误码，让端上能把「缺哪张资质、去哪申请」说出来 ——
     * 通用的「请求参数有误」会让商家反复改商品信息，而问题根本不在商品上。
     */
    private void requireCategoryAuthorized(String merchantNo, String categoryNo) {
        boolean categorized = categoryNo != null && !categoryNo.isBlank();
        String required = categorized ? categoryService.requiredCodeOf(categoryNo) : null;
        boolean needsQualification = required != null && !required.isBlank();

        /*
         * 弱主体准入（保证金 / 限品类）。
         *
         * <b>放在所有 early return 之前</b>，因为保证金与类目无关：小微卖什么都要有钱兜底。
         * 上一版放在「没归类就 return」之后，于是**不填类目的商品完全绕过这道闸**——
         * 而 categoryNo 是选填的，绕过它只需要少填一个字段。
         * 「无门槛类目」（requiredCode 为空）与「无类目」（categoryNo 为空）是两件事，
         * 上一版的注释只覆盖了前者，测试也只传了前者，所以两边都没发现。
         */
        admissionPort.requireListingAllowed(merchantNo, categoryNo, needsQualification);

        if (!categorized) {
            // 没归类的商品不卡在资质这一关：归类是否必填是另一个决定，不该由准入校验顺手做掉
            return;
        }
        if (!needsQualification) {
            return;
        }
        if (!merchantPort.authorizedCategoryCodes(merchantNo).contains(required)) {
            throw BizException.of(ErrorCode.CATEGORY_NOT_AUTHORIZED);
        }
        /*
         * 资质过期也不能上架。
         *
         * **这一道与 category_codes 那道判的不是同一件事**：后者判「当初批没批过」，
         * 是审核时写死的一串编码，证过期了它不会变；这一道判「现在还有效吗」。
         * 只有前者的话，商家的食品经营许可证到期后什么都不用做，
         * 商品继续在架、继续能上新，而平台收不到任何信号。
         *
         * 与定时扫描是两道防线，针对不同时机：任务覆盖「已经在架的」，
         * 这里覆盖「正要上架的」—— 任务有间隔，上架随时发生。
         */
        if (merchantPort.hasExpiredQualification(merchantNo)) {
            throw BizException.of(ErrorCode.QUALIFICATION_EXPIRED);
        }
    }

    /**
     * 同步社区池。<b>上架的真正含义是「进哪些社区的池」</b> ——
     * C 端按社区查商品读的就是 {@code prd_community_pool}，不是 {@code on_sale}。
     *
     * <p>不做这一步的后果与入驻那个缺口一模一样：商家点了上架、列表里显示"在售"、
     * 而买家<b>在任何地方都搜不到这件货</b>，且没有任何报错。
     * 上一次是商家没进社区，这一次是商品没进池 —— 同一个形状的故障。
     *
     * <p>范围来自 {@link MerchantQueryPort#reachableCommunities}（ADR-009 三档已展开），
     * 不是 product 域自己去读商家的社区表：那样两处口径迟早分岔。
     */
    private void syncPool(PrdGoods g, boolean onSale) {
        List<PrdCommunityPool> existing = DataScopeContext.executeWithoutScope(() ->
                poolMapper.selectList(Wrappers.<PrdCommunityPool>lambdaQuery()
                        .eq(PrdCommunityPool::getGoodsNo, g.getGoodsNo())));
        if (!onSale) {
            // 下架 = 从所有池里撤出。留在池里的话 C 端还能搜到，点进去才发现买不了
            for (PrdCommunityPool row : existing) {
                DataScopeContext.executeWithoutScope(() -> poolMapper.deleteById(row.getId()));
            }
            return;
        }
        List<String> want = merchantPort.reachableCommunities(g.getEntityNo());
        List<String> have = existing.stream().map(PrdCommunityPool::getCommunityNo).toList();
        // 差集增删，不是"先全删再全插"：池表有 uk_community_goods 唯一键，
        // 而删除是逻辑删 —— 删完再插同一对会撞键（这个坑在商家社区表上刚踩过）
        for (PrdCommunityPool row : existing) {
            if (!want.contains(row.getCommunityNo())) {
                DataScopeContext.executeWithoutScope(() -> poolMapper.deleteById(row.getId()));
            }
        }
        for (String communityNo : want) {
            if (have.contains(communityNo)) {
                continue;
            }
            /*
             * 先试着复活被逻辑删的行。**下架是逻辑删，而唯一键不含 deleted** ——
             * 直接 insert 会撞 uk_community_goods，表现为上架接口 500，
             * 而商家看到的是「系统开小差」，与商品本身毫无关系。
             */
            if (DataScopeContext.executeWithoutScope(() ->
                    poolMapper.revive(communityNo, g.getGoodsNo())) > 0) {
                continue;
            }
            PrdCommunityPool row = new PrdCommunityPool();
            row.setCommunityNo(communityNo);
            row.setGoodsNo(g.getGoodsNo());
            row.setEntityNo(g.getEntityNo());
            row.setSortWeight(0);
            DataScopeContext.executeWithoutScope(() -> poolMapper.insert(row));
        }
    }

    @Override
    @Transactional
    public GoodsVO saveStock(String merchantNo, String goodsNo, String skuNo, int stock) {
        PrdGoods g = mine(merchantNo, goodsNo);
        if (stock < 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **这个 SKU 已经按店管理时，写到当前门店去** —— 否则写与读不是同一个数。
         *
         * 主体总量与分店库存是两笔账：SKU 一旦有过分店库存行，
         * 商品详情读的就是分店那份（见 {@code applyStoreStock}）。这条端点写的是主体总量，
         * 于是商家点「保存」→ **返回成功 → 页面数字纹丝不动 → 没有任何提示**。
         * 实测：store-stock 设 33 → 读回 33；stock 设 99 → code 0，读回还是 33。
         *
         * b-app 已经绕开了（多店走 store-stock、单店走 stock），但**两边判据不同**：
         * 端上按「商家是否多店」，后端按「这个 SKU 是否已按店管理」——
         * 单店商家若有按店库存的 SKU（比如关掉过一家店），就会落进这个静默无效。
         *
         * 改成「写落到读的地方」而不是报错：报错的话，那位商家在界面上
         * **没有任何可用路径**去改这件商品的库存（单店模式不显示分店库存入口）。
         */
        if (isStoreManaged(skuNo)) {
            String storeNo = ai.neargo.shop.auth.BizContext.current().currentStoreNo();
            if (storeNo != null && !storeNo.isBlank()) {
                return saveStoreStock(merchantNo, storeNo, goodsNo, skuNo, stock);
            }
        }
        List<PrdSku> rows = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, goodsNo).eq(PrdSku::getSkuNo, skuNo)));
        if (rows.isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        for (PrdSku row : rows) {
            // 库存不分市场：货就那么多，卖到哪个市场都是同一批。
            // 价格分市场、库存不分 —— 这两件事的口径不同，正是分开存的理由
            row.setStock(stock);
            DataScopeContext.executeWithoutScope(() -> skuMapper.updateById(row));
        }
        // 补货**不触发重审**：这是每天都在做的事，走完整保存等于每次补货都要重新过审
        return toVO(g);
    }

    @Override
    @Transactional
    public GoodsVO saveStoreStock(String merchantNo, String storeNo, String goodsNo,
                                  String skuNo, int stock) {
        PrdGoods g = mine(merchantNo, goodsNo);
        if (stock < 0 || storeNo == null || storeNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        boolean exists = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectCount(Wrappers.<PrdSku>lambdaQuery()
                        .eq(PrdSku::getGoodsNo, goodsNo).eq(PrdSku::getSkuNo, skuNo))) > 0;
        if (!exists) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        PrdStoreStock row = DataScopeContext.executeWithoutScope(() ->
                storeStockMapper.selectOne(Wrappers.<PrdStoreStock>lambdaQuery()
                        .eq(PrdStoreStock::getStoreNo, storeNo)
                        .eq(PrdStoreStock::getSkuNo, skuNo)));
        if (row == null) {
            row = new PrdStoreStock();
            row.setStoreNo(storeNo);
            row.setSkuNo(skuNo);
            row.setEntityNo(merchantNo);
            row.setLockedStock(0);
            row.setStock(stock);
            PrdStoreStock toInsert = row;
            DataScopeContext.executeWithoutScope(() -> storeStockMapper.insert(toInsert));
        } else {
            row.setStock(stock);
            PrdStoreStock toUpdate = row;
            DataScopeContext.executeWithoutScope(() -> storeStockMapper.updateById(toUpdate));
        }
        return toVO(g);
    }

    /**
     * 这个 SKU 是否已经切成「按店管理」。判据与 {@code applyStoreStock} 一致：
     * <b>只要存在任何一行分店库存</b>，读取口径就已经换成分店那份了。
     */
    private boolean isStoreManaged(String skuNo) {
        return skuNo != null && !skuNo.isBlank()
                && DataScopeContext.executeWithoutScope(() ->
                storeStockMapper.selectCount(Wrappers.<PrdStoreStock>lambdaQuery()
                        .eq(PrdStoreStock::getSkuNo, skuNo))) > 0;
    }

    @Override
    public java.util.Map<String, Integer> outOfStockCountByStore(String merchantNo,
                                                                 java.util.Collection<String> storeNos) {
        // 空集合 = 一家都不看（fail-closed），与订单侧同一套口径。
        // 当成「不过滤」的话，一个没被授权到任何门店的店员会看到全主体的缺货
        if (merchantNo == null || merchantNo.isBlank() || (storeNos != null && storeNos.isEmpty())) {
            return java.util.Map.of();
        }
        List<PrdStoreStock> rows = DataScopeContext.executeWithoutScope(() ->
                storeStockMapper.selectList(Wrappers.<PrdStoreStock>lambdaQuery()
                        .eq(PrdStoreStock::getEntityNo, merchantNo)
                        .in(storeNos != null, PrdStoreStock::getStoreNo,
                                storeNos == null ? List.of() : storeNos)));
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (PrdStoreStock r : rows) {
            if (r.getStoreNo() == null || r.getStoreNo().isBlank()) {
                continue;
            }
            /*
             * 可用量 = stock − locked。**扣掉锁定量而不是只看 stock**：
             * 20 件全被未付款的单锁着，货架上就是空的 —— 只看 stock 的话
             * 商家在「缺货 0」的页面上眼看着卖不出去。
             */
            int available = nzi(r.getStock()) - nzi(r.getLockedStock());
            if (available <= 0) {
                out.merge(r.getStoreNo(), 1, Integer::sum);
            }
        }
        return out;
    }

    private static int nzi(Integer v) {
        return v == null ? 0 : v;
    }

    @Override
    @Transactional
    public GoodsVO audit(String goodsNo, boolean approved, String reason) {
        if (!approved && (reason == null || reason.isBlank())) {
            // 不写理由的驳回等于让商家猜着改
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PrdGoods g = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getGoodsNo, goodsNo).last("limit 1")));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        g.setAuditStatus(approved ? APPROVED : REJECTED);
        // 原因落在商品行上（V96）：它是商家能看到的那半边。过审清空 ——
        // 旧原因留着会被当成「还有问题没改完」
        g.setAuditReason(approved ? null : reason.trim());
        if (!approved) {
            // 驳回同时强制下架**并撤出社区池**：只改 on_sale 不撤池的话，
            // 被驳回的商品在 C 端还搜得到 —— 审核结论没有落到买家看得见的地方
            g.setOnSale(false);
        }
        DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        if (!approved) {
            syncPool(g, false);
        }
        return toVO(g);
    }

    // ---------------------------------------------------------------- 规格模板

    @Override
    public List<SpecTemplateVO> specTemplates(String merchantNo, String categoryType) {
        var w = Wrappers.<PrdSpecTemplate>lambdaQuery()
                /*
                 * 归档的不下发（V102）。**归档了商家还能选，等于没归档** ——
                 * 而运营会以为自己把那套错的规格下线了，直到发现新品还在用它。
                 * 存量行的 status 由迁移的 DEFAULT 'ACTIVE' 兜住。
                 */
                .eq(PrdSpecTemplate::getStatus, PrdSpecTemplate.ACTIVE)
                // 平台模板 + 我自己的。别家商家自存的模板与我无关
                .and(q -> q.eq(PrdSpecTemplate::getScope, PrdSpecTemplate.PLATFORM)
                        .or(o -> o.eq(PrdSpecTemplate::getScope, PrdSpecTemplate.MERCHANT)
                                .eq(PrdSpecTemplate::getEntityNo, merchantNo)));
        if (categoryType != null && !categoryType.isBlank()) {
            // 类目过滤只作用于平台模板：商家自存的模板不限类目（他自己知道用在哪）
            w.and(q -> q.eq(PrdSpecTemplate::getCategoryType, categoryType)
                    .or(o -> o.isNull(PrdSpecTemplate::getCategoryType)));
        }
        return DataScopeContext.executeWithoutScope(() -> templateMapper.selectList(w))
                .stream().map(this::toVO).toList();
    }

    @Override
    @Transactional
    public SpecTemplateVO saveSpecTemplate(String merchantNo, String name, List<SpecOption> options) {
        if (name == null || name.isBlank() || options == null || options.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PrdSpecTemplate t = new PrdSpecTemplate();
        t.setTemplateNo(BizKey.next(BizKey.SPEC_TEMPLATE));
        // 一律存成 MERCHANT：商家改不了平台模板 —— 平台模板是跨店可比的基础
        t.setScope(PrdSpecTemplate.MERCHANT);
        t.setStatus(PrdSpecTemplate.ACTIVE);
        t.setEntityNo(merchantNo);
        t.setName(name);
        t.setOptions(writeJson(options));
        DataScopeContext.executeWithoutScope(() -> templateMapper.insert(t));
        return toVO(t);
    }

    // ---------------------------------------------------------------- helpers

    private PrdGoods newGoods(String merchantNo) {
        PrdGoods g = new PrdGoods();
        g.setGoodsNo(BizKey.next(BizKey.GOODS));
        g.setEntityNo(merchantNo);
        g.setRating(50);
        g.setRatingCount(0);
        g.setSales(0);
        g.setLimitPerUser(0);
        /*
         * 新建默认**实物类全支持**，由商家去收窄。
         *
         * 此前默认是 ["STORE_PICKUP"] 且商家改不了 —— 那个值从来不表示
         * 「只支持到店自提」，只是个占位，而这些商品一直在被下成快递单。
         * F-1 给下单加了「必须支持」的校验之后，照原样留着会让新商品一建出来
         * 就只能自提。默认放宽、由商家收窄，是唯一不会凭空拦单的方向。
         *
         * ⚠️ **是 PHYSICAL 不是 ALL**：服务类履约（到店核销 / 上门预约）
         * 要由商家显式选择 —— 一件大米不该一建出来就声称支持到店核销。
         */
        g.setFulfillments(writeJson(new java.util.ArrayList<>(Fulfillments.PHYSICAL)));
        return g;
    }

    /**
     * 取自己的商品。<b>不是自己的按 404 处理而不是 403</b> ——
     * 403 等于告诉对方「这个编号确实存在，只是不归你」，那是一条可以拿来枚举别家商品的信道。
     */
    private PrdGoods mine(String merchantNo, String goodsNo) {
        PrdGoods g = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectOne(Wrappers.<PrdGoods>lambdaQuery()
                        .eq(PrdGoods::getGoodsNo, goodsNo)
                        .eq(PrdGoods::getEntityNo, merchantNo).last("limit 1")));
        if (g == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return g;
    }

    private void applyStatus(com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<PrdGoods> w,
                             String status) {
        if (status == null || status.isBlank()) {
            return;
        }
        switch (status) {
            case "ON_SALE" -> w.eq(PrdGoods::getOnSale, true);
            case "OFF_SALE" -> w.eq(PrdGoods::getOnSale, false).eq(PrdGoods::getAuditStatus, APPROVED);
            // PENDING 是对外口径；AUDITING 是库里的词，老客户端还在传 —— 两个都收
            case "PENDING", "AUDITING" -> w.eq(PrdGoods::getAuditStatus, AUDITING);
            case "REJECTED" -> w.eq(PrdGoods::getAuditStatus, REJECTED);
            // 未知取值当作不过滤：前端多传一个筛选项不该让列表变空，那看着像"一件商品都没有"
            default -> { }
        }
    }

    /**
     * 商家侧状态：审核结果优先于上下架 —— 没过审时"已下架"这个说法会让人以为点一下就能卖。
     *
     * <p>不再是 static：在售与否现在要看**当前门店**，那需要读库。
     */
    private String statusOf(PrdGoods g) {
        if (AUDITING.equals(g.getAuditStatus())) {
            /*
             * **下发 PENDING，不是库里那个 AUDITING**（2026-08-12 收敛）。
             *
             * 词典 §11 的通用状态词表规定「已提交待处理」= PENDING，ops-web 的
             * SkuStatus 一直照着写，只有这里发的是列名的原值 —— 于是同一件事
             * 在三端有两个词，而枚举登记表里那句「已归一」当时只归在端上。
             *
             * 库里的 audit_status 不动：它是审核结果那一轴（AUDITING/APPROVED/REJECTED），
             * 改列值要迁移，而收益只是名字好看 —— 对外口径统一就够了。
             */
            return "PENDING";
        }
        if (REJECTED.equals(g.getAuditStatus())) {
            return "REJECTED";
        }
        return storeOnSale(g) ? "ON_SALE" : "OFF_SALE";
    }

    /**
     * 这件商品在**当前门店**上不上架。
     *
     * <p>语义与门店库存（V13）逐字同款：
     * <ul>
     *   <li>一条店级行都没有 → 走 {@code prd_goods.on_sale}，单店行为完全不变
     *   <li>有了任意一条 → 该商品整体转为店级管理，<b>没有行的店视为未上架</b>
     * </ul>
     *
     * <p>最后半句不能改成「没有行就回退主体级」：那样商家给 A 店单独上架之后，
     * B 店会跟着一起上架 —— 而他刚做的恰恰是「只在 A 店卖」。
     */
    private boolean storeOnSale(PrdGoods g) {
        boolean entityOn = Boolean.TRUE.equals(g.getOnSale());
        String storeNo = ai.neargo.shop.auth.BizContext.current().currentStoreNo();
        List<ai.neargo.shop.product.entity.PrdStoreGoods> rows = storeGoodsRows(g.getGoodsNo());
        if (rows.isEmpty()) {
            return entityOn;
        }
        /*
         * storeNo 在 B 端不会为空 —— 不传 X-Store-No 时 BizContext 取默认店，
         * 「主体视角」这个东西在 B 端并不存在。
         * 这里原先有一个 storeNo == null 的分支（「任一门店在售就算在售」），
         * 是我凭空造的语义，测试当场证伪：不带头访问看到的就是默认店的答案。
         * 留成死代码的话，下一个人会照着它推理出一个不存在的视角。
         */
        if (storeNo == null || storeNo.isBlank()) {
            return entityOn;
        }
        return entityOn && rows.stream()
                .filter(r -> storeNo.equals(r.getStoreNo()))
                .anyMatch(r -> Boolean.TRUE.equals(r.getOnSale()));
    }

    private List<ai.neargo.shop.product.entity.PrdStoreGoods> storeGoodsRows(String goodsNo) {
        return DataScopeContext.executeWithoutScope(() ->
                storeGoodsMapper.selectList(Wrappers.<ai.neargo.shop.product.entity.PrdStoreGoods>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdStoreGoods::getGoodsNo, goodsNo)));
    }

    private GoodsVO toVO(PrdGoods g) {
        // 复用买家侧的组装：同一件商品在两个端展示出两套价格/库存口径是最难查的一类 bug
        GoodsVO base = goodsService.detail(g.getGoodsNo());
        return new GoodsVO(base.goodsNo(), base.title(), base.subtitle(), base.cover(),
                base.images(), base.type(), base.categoryNo(), base.merchant(),
                base.rating(), base.ratingCount(), base.price(), base.originPrice(),
                base.fulfillments(), base.specGroups(), storeSkus(base.skus()), base.sales(),
                base.cutoffAt(), base.arrivalDesc(), base.weighed(), base.origin(),
                base.durationMin(), base.storeName(), base.limitPerUser(), base.onSale(),
                statusOf(g),
                /*
                 * **译文原文只在这一侧下发**。编辑页按语言逐格填、保存是整份覆盖 ——
                 * 拿不到原文它就只能回填当前那一格，于是用中文改一次，
                 * 英文与阿语的标题被清空，而且不报错（C 端回落中文，看起来一切正常）。
                 */
                readMap(g.getTitleI18n()), readMap(g.getSubtitleI18n()),
                g.getAuditReason(),
                // 「可开团的商品」那一栏就是按它筛的
                GoodsServiceImpl.groupBuyConf(g));
    }

    /**
     * 把 SKU 的库存换成**当前门店**的。
     *
     * <p>商家给某家店设了 1 件之后，商品页却显示主体总量 91 —— 他会以为还有货。
     * 库存分店（V13）只解决了「扣谁的数」，没解决「商家看到的是谁的数」，
     * 而后者才是他每天盯着的那个数字。
     *
     * <p>没启用分店库存的 SKU 原样返回，单店商家因此看不出任何变化。
     */
    private List<GoodsVO.SkuVO> storeSkus(List<GoodsVO.SkuVO> skus) {
        String storeNo = ai.neargo.shop.auth.BizContext.current().currentStoreNo();
        if (storeNo == null || storeNo.isBlank() || skus == null || skus.isEmpty()) {
            return skus;
        }
        List<String> skuNos = skus.stream().map(GoodsVO.SkuVO::skuNo).toList();
        Map<String, PrdStoreStock> byStore = DataScopeContext.executeWithoutScope(() ->
                        storeStockMapper.selectList(Wrappers.<PrdStoreStock>lambdaQuery()
                                .in(PrdStoreStock::getSkuNo, skuNos)))
                .stream().collect(java.util.stream.Collectors.toMap(
                        r -> r.getSkuNo() + "@" + r.getStoreNo(), r -> r, (a, b) -> a));
        // 这些 SKU 里有没有任何一个启用了分店库存 —— 判据与 StockPortImpl 保持一致
        java.util.Set<String> perStore = byStore.values().stream()
                .map(PrdStoreStock::getSkuNo).collect(java.util.stream.Collectors.toSet());
        if (perStore.isEmpty()) {
            return skus;
        }
        return skus.stream().map(sku -> {
            if (!perStore.contains(sku.skuNo())) {
                return sku;
            }
            PrdStoreStock row = byStore.get(sku.skuNo() + "@" + storeNo);
            /*
             * 这家店没有行 = 0，不是回退总量 —— 与下单侧同一口径。
             * 两处口径不一致的话，会出现「页面显示有货、下单说库存不足」。
             */
            int stock = row == null || row.getStock() == null ? 0 : row.getStock();
            int locked = row == null || row.getLockedStock() == null ? 0 : row.getLockedStock();
            int available = Math.max(stock - locked, 0);
            return new GoodsVO.SkuVO(sku.skuNo(), sku.optionValues(), sku.spec(),
                    sku.price(), sku.originPrice(), available, sku.nominalGram());
        }).toList();
    }

    private SpecTemplateVO toVO(PrdSpecTemplate t) {
        List<SpecTemplateVO.Option> options;
        try {
            options = json.readValue(t.getOptions() == null ? "[]" : t.getOptions(),
                    new TypeReference<List<SpecTemplateVO.Option>>() {
                    });
        } catch (Exception e) {
            options = List.of();
        }
        return new SpecTemplateVO(t.getTemplateNo(), t.getScope(), t.getCategoryType(),
                t.getName(), options, t.getEntityNo());
    }

    private String writeSpecGroups(List<SpecGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return "[]";
        }
        /*
         * **optionCodes 与 templateNo 必须一起存下去。**
         *
         * 此前这里只写 name/options，两者在保存那一刻被静默丢弃 —— 于是
         * B-4.5 说的「一期只写入不消费」连写入都没有，而平台规格模板（P-3.4/E27）
         * 存在的唯一理由就是那个 code：没有它，三家店的「500g」「五百克」「0.5kg」
         * 永远聚合不到一起，模板与手输没有任何区别。
         *
         * 丢弃发生在写库这一步，所以从接口到页面全程看不出来 —— 建品成功、
         * 规格显示正常，只是那一列 code 从来没存在过。
         */
        return writeJson(groups.stream()
                .map(g -> {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("name", g.name());
                    m.put("options", g.options() == null ? List.of() : g.options());
                    if (g.optionCodes() != null && !g.optionCodes().isEmpty()) {
                        m.put("optionCodes", g.optionCodes());
                    }
                    if (g.templateNo() != null && !g.templateNo().isBlank()) {
                        m.put("templateNo", g.templateNo());
                    }
                    return m;
                })
                .toList());
    }

    private String writeMap(Map<String, String> m) {
        return m == null || m.isEmpty() ? null : writeJson(m);
    }

    private String writeJson(Object v) {
        try {
            return json.writeValueAsString(v == null ? List.of() : v);
        } catch (Exception e) {
            return "[]";
        }
    }
}
