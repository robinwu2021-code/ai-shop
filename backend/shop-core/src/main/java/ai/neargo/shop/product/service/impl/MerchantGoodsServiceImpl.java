package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.dto.SpecTemplateVO;
import ai.neargo.shop.product.entity.PrdCommunityPool;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
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
    private final ObjectMapper json;

    private final ai.neargo.shop.product.service.CategoryService categoryService;

    public MerchantGoodsServiceImpl(GoodsMapper goodsMapper, SkuMapper skuMapper,
                                    SpecTemplateMapper templateMapper,
                                    GoodsService goodsService, CommunityPoolMapper poolMapper,
                                    ai.neargo.shop.spi.user.MerchantQueryPort merchantPort,
                                    ai.neargo.shop.product.service.CategoryService categoryService,
                                    ObjectMapper json) {
        this.categoryService = categoryService;
        this.poolMapper = poolMapper;
        this.merchantPort = merchantPort;
        this.goodsMapper = goodsMapper;
        this.skuMapper = skuMapper;
        this.templateMapper = templateMapper;
        this.goodsService = goodsService;
        this.json = json;
    }

    // ---------------------------------------------------------------- 查询

    @Override
    public PageData<GoodsVO> list(String merchantNo, String status, long page, long size) {
        /*
         * merchantNo 为空 = **跨商家查**，给平台审核队列用。
         * 不做这个判空的话 MyBatis-Plus 会生成 `entity_no = null`，一行都查不到 ——
         * 而平台侧看到的是「没有待审商品」，与「审完了」长得一模一样。
         */
        var w = Wrappers.<PrdGoods>lambdaQuery()
                .eq(merchantNo != null && !merchantNo.isBlank(), PrdGoods::getEntityNo, merchantNo);
        applyStatus(w, status);
        // 新建的排在前面：店主刚录完一件商品，第一件事是看它在不在
        w.orderByDesc(PrdGoods::getId);
        Page<PrdGoods> p = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectPage(Page.of(page, size), w));
        List<GoodsVO> rows = p.getRecords().stream().map(this::toVO).toList();
        return PageData.of(rows, p.getTotal(), page, size);
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
        g.setOnSale(onSale);
        DataScopeContext.executeWithoutScope(() -> goodsMapper.updateById(g));
        syncPool(g, onSale);
        return toVO(g);
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
        if (categoryNo == null || categoryNo.isBlank()) {
            // 没归类的商品不卡在这里：归类是否必填是另一个决定，不该由准入校验顺手做掉
            return;
        }
        String required = categoryService.requiredCodeOf(categoryNo);
        if (required == null || required.isBlank()) {
            return;
        }
        if (!merchantPort.authorizedCategoryCodes(merchantNo).contains(required)) {
            throw BizException.of(ErrorCode.CATEGORY_NOT_AUTHORIZED);
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
        g.setFulfillments("[\"STORE_PICKUP\"]");
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
            case "AUDITING" -> w.eq(PrdGoods::getAuditStatus, AUDITING);
            case "REJECTED" -> w.eq(PrdGoods::getAuditStatus, REJECTED);
            // 未知取值当作不过滤：前端多传一个筛选项不该让列表变空，那看着像"一件商品都没有"
            default -> { }
        }
    }

    /** 商家侧状态：审核结果优先于上下架 —— 没过审时"已下架"这个说法会让人以为点一下就能卖。 */
    private static String statusOf(PrdGoods g) {
        if (AUDITING.equals(g.getAuditStatus())) {
            return "AUDITING";
        }
        if (REJECTED.equals(g.getAuditStatus())) {
            return "REJECTED";
        }
        return Boolean.TRUE.equals(g.getOnSale()) ? "ON_SALE" : "OFF_SALE";
    }

    private GoodsVO toVO(PrdGoods g) {
        // 复用买家侧的组装：同一件商品在两个端展示出两套价格/库存口径是最难查的一类 bug
        GoodsVO base = goodsService.detail(g.getGoodsNo());
        return new GoodsVO(base.goodsNo(), base.title(), base.subtitle(), base.cover(),
                base.images(), base.type(), base.categoryNo(), base.merchant(),
                base.rating(), base.ratingCount(), base.price(), base.originPrice(),
                base.fulfillments(), base.specGroups(), base.skus(), base.sales(),
                base.cutoffAt(), base.arrivalDesc(), base.weighed(), base.origin(),
                base.durationMin(), base.storeName(), base.limitPerUser(), base.onSale(),
                statusOf(g));
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
        return writeJson(groups.stream()
                .map(g -> Map.of("name", g.name(), "options", g.options() == null ? List.of() : g.options()))
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
