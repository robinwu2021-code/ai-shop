package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.OpsSkuDetailVO;
import ai.neargo.shop.product.dto.OpsSpecTemplateVO;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdSpecTemplate;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecTemplateMapper;
import ai.neargo.shop.product.service.CategoryService;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.product.service.PlatformProductService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link PlatformProductService} 实现。 */
@Service
public class PlatformProductServiceImpl implements PlatformProductService {

    /**
     * {@code prd_sku} 是「一市场一行」，分页必须落在**一个逻辑 SKU 一行**上，
     * 否则同一个规格会在列表里出现两次（CN 一行、SG 一行），
     * 而运营看到的是「同一件商品重复了」。基准市场恒有行（保存时必写），拿它当锚。
     */
    private static final String HOME_MARKET = "CN";

    private static final String AUDITING = "AUDITING";
    private static final String REJECTED = "REJECTED";

    private final SkuMapper skuMapper;
    private final GoodsMapper goodsMapper;
    private final SpecTemplateMapper templateMapper;
    private final MerchantGoodsService merchantGoodsService;
    private final MerchantQueryPort merchantPort;
    private final CategoryService categoryService;
    private final ObjectMapper json;

    public PlatformProductServiceImpl(SkuMapper skuMapper, GoodsMapper goodsMapper,
                                      SpecTemplateMapper templateMapper,
                                      MerchantGoodsService merchantGoodsService,
                                      MerchantQueryPort merchantPort,
                                      CategoryService categoryService,
                                      ObjectMapper json) {
        this.skuMapper = skuMapper;
        this.goodsMapper = goodsMapper;
        this.templateMapper = templateMapper;
        this.merchantGoodsService = merchantGoodsService;
        this.merchantPort = merchantPort;
        this.categoryService = categoryService;
        this.json = json;
    }

    // ---------------------------------------------------------------- P-3.3 查询

    @Override
    public PageData<OpsSkuDetailVO> listSkus(String merchantNo, String categoryNo, String keyword,
                                             String status, boolean presaleOnly,
                                             long page, long size) {
        /*
         * 过滤维度（商家 / 类目 / 关键词 / 状态）**全都挂在父商品上** —— SKU 自己只有
         * 规格、价、库存。所以先用这些条件把 goodsNo 圈出来，再按 SKU 分页。
         *
         * 一个条件都没有时**不做这一步**：那时 goodsNo 集合等于全表，
         * 拼进 IN (...) 只是把整张商品表搬进一条 SQL 的参数里。
         */
        boolean filtered = notBlank(merchantNo) || notBlank(categoryNo)
                || notBlank(keyword) || notBlank(status);
        List<String> goodsNos = filtered ? matchingGoodsNos(merchantNo, categoryNo, keyword, status) : null;
        if (goodsNos != null && goodsNos.isEmpty()) {
            return PageData.empty(page, size);
        }

        LambdaQueryWrapper<PrdSku> w = Wrappers.<PrdSku>lambdaQuery()
                .eq(PrdSku::getMarket, HOME_MARKET)
                .in(goodsNos != null, PrdSku::getGoodsNo, goodsNos == null ? List.of() : goodsNos)
                // presaleOnly 做在 SQL 里而不是交给前端过滤：真实库里预售 SKU 大概率
                // 不在第一页，前端过滤会让「库存与预售」tab 长期显示为空，且接口 200
                .gt(presaleOnly, PrdSku::getPresaleQuota, 0)
                .orderByDesc(PrdSku::getId);
        Page<PrdSku> p = DataScopeContext.executeWithoutScope(() -> skuMapper.selectPage(Page.of(page, size), w));
        return PageData.of(toVOs(p.getRecords()), p.getTotal(), page, size);
    }

    @Override
    public List<OpsSkuDetailVO> listOversellSkus() {
        /*
         * 两列相比不能用 lambda 的 gt（那要求右边是个值），只能 apply 一段 SQL。
         *
         * 为什么这条能有行：下单闸门是 `sold_count + qty <= presale_quota`，正常成交超不出去；
         * **超卖只可能是平台自己把额度调小调出来的**（次日现采临时收紧）。
         * 所以这张表上每一行都有人认领 —— 这正是 setPresale 刻意不拦「额度小于已售」的理由。
         */
        LambdaQueryWrapper<PrdSku> w = Wrappers.<PrdSku>lambdaQuery()
                .eq(PrdSku::getMarket, HOME_MARKET)
                .gt(PrdSku::getPresaleQuota, 0)
                .apply("sold_count > presale_quota")
                .orderByDesc(PrdSku::getId);
        return toVOs(DataScopeContext.executeWithoutScope(() -> skuMapper.selectList(w)));
    }

    // ---------------------------------------------------------------- P-3.3 处置

    @Override
    @Transactional
    public OpsSkuDetailVO setPresale(String skuNo, int presaleQuota, String cutoffAt, String arriveAt) {
        List<PrdSku> rows = rowsOf(skuNo);
        if (presaleQuota < 0) {
            // 负额度会让 `sold_count + qty <= presale_quota` 恒不成立 ——
            // 表现是「开了预售反而更买不了」，而配置页上看着是开着的
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        LocalDateTime cutoff = parseTime(cutoffAt);
        LocalDateTime arrive = parseTime(arriveAt);
        for (PrdSku row : rows) {
            /*
             * 截单必须早于到货（P-3.3.2）：否则货到了还能继续下单，
             * 而那批订单没有对应的采购，最后只能挨个退。
             *
             * 拿「这次要存的到货时间」比，没传就沿用行上原来的 —— 只改额度的那次提交
             * 不该绕过这条校验（它本来就该对现存的两个时间成立）。
             */
            LocalDateTime effectiveArrive = arrive != null ? arrive : row.getArriveAt();
            if (cutoff != null && effectiveArrive != null && !cutoff.isBefore(effectiveArrive)) {
                throw BizException.of(ErrorCode.PRESALE_CUTOFF_AFTER_ARRIVAL);
            }
        }
        for (PrdSku row : rows) {
            // 额度与库存同口径：**不分市场**（货就那么多），各市场行写同一个值
            row.setPresaleQuota(presaleQuota);
            row.setCutoffAt(cutoff);
            if (arrive != null) {
                // 空 = 不改。与「清空到货时间」分开：一次只改额度的提交若顺手把它清了，
                // 「截单必须早于到货」下一次就形同虚设
                row.setArriveAt(arrive);
            }
            DataScopeContext.executeWithoutScope(() -> skuMapper.updateById(row));
        }
        return toVOs(rowsOf(skuNo).stream().filter(r -> HOME_MARKET.equals(r.getMarket())).toList()).get(0);
    }

    @Override
    @Transactional
    public OpsSkuDetailVO auditSku(String skuNo, boolean pass, String reason) {
        // 解析到父商品再委托：审核判的是「这件商品能不能卖」，标题/图/类目/资质都在 goods 上
        merchantGoodsService.audit(goodsNoOf(skuNo), pass, reason);
        return detailOf(skuNo);
    }

    @Override
    @Transactional
    public OpsSkuDetailVO forceOffSku(String skuNo, String reason) {
        // 压下架而**不撤过审** —— 与 goods 级 force-off 的分界见 MerchantGoodsService 的说明
        merchantGoodsService.platformSuspendGoods(goodsNoOf(skuNo), reason);
        return detailOf(skuNo);
    }

    // ---------------------------------------------------------------- P-3.4 规格模板

    @Override
    public PageData<OpsSpecTemplateVO> listSpecTemplates(String categoryType, String keyword,
                                                         boolean showArchived, long page, long size) {
        LambdaQueryWrapper<PrdSpecTemplate> w = Wrappers.<PrdSpecTemplate>lambdaQuery()
                // 平台端只看平台模板。商家自存的模板归商家 ——
                // 运营在这里改一条，那家店的历史规格就对不上了
                .eq(PrdSpecTemplate::getScope, PrdSpecTemplate.PLATFORM)
                .eq(notBlank(categoryType), PrdSpecTemplate::getCategoryType, categoryType)
                .like(notBlank(keyword), PrdSpecTemplate::getName, keyword)
                .eq(!showArchived, PrdSpecTemplate::getStatus, PrdSpecTemplate.ACTIVE)
                .orderByDesc(PrdSpecTemplate::getId);
        Page<PrdSpecTemplate> p = DataScopeContext.executeWithoutScope(() ->
                templateMapper.selectPage(Page.of(page, size), w));
        return PageData.of(p.getRecords().stream().map(this::toVO).toList(), p.getTotal(), page, size);
    }

    @Override
    @Transactional
    public OpsSpecTemplateVO saveSpecTemplate(SaveTemplateCommand cmd) {
        if (cmd.name() == null || cmd.name().isBlank()
                || cmd.options() == null || cmd.options().isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **每个选项都必须带 code** —— 这是平台模板存在的唯一理由（B-4.5）：
         * 自由文本下三家店会把同一件事写成「5 斤」「五斤」「2.5kg」，
         * 聚合、比价、搜索全部对不上。没有 code 的平台模板与商家手输的没有区别，
         * 它唯一的作用是让人**以为**规格统一了。
         */
        Set<String> codes = new LinkedHashSet<>();
        for (MerchantGoodsService.SpecOption o : cmd.options()) {
            if (o == null || o.code() == null || o.code().isBlank()
                    || o.label() == null || o.label().isBlank()) {
                throw BizException.of(ErrorCode.SPEC_TEMPLATE_CODE_REQUIRED);
            }
            // 组内 code 重复会让「500g」和「1kg」在聚合时并成同一个规格 —— 正是 code 要防的事
            if (!codes.add(o.code().trim())) {
                throw BizException.of(ErrorCode.SPEC_TEMPLATE_DUPLICATE);
            }
        }

        PrdSpecTemplate existing = notBlank(cmd.templateNo()) ? platformTemplate(cmd.templateNo()) : null;
        final String selfNo = existing == null ? null : existing.getTemplateNo();
        // 同一品类下重名：商家的下拉里会出现两个「重量」，选哪个都对不上
        Long dup = DataScopeContext.executeWithoutScope(() -> templateMapper.selectCount(
                Wrappers.<PrdSpecTemplate>lambdaQuery()
                        .eq(PrdSpecTemplate::getScope, PrdSpecTemplate.PLATFORM)
                        .eq(PrdSpecTemplate::getName, cmd.name().trim())
                        .eq(notBlank(cmd.categoryType()), PrdSpecTemplate::getCategoryType, cmd.categoryType())
                        .isNull(!notBlank(cmd.categoryType()), PrdSpecTemplate::getCategoryType)
                        // 更新自己不算重名 —— 少了这一句，改一下选项就报「已有同名模板」
                        .ne(selfNo != null, PrdSpecTemplate::getTemplateNo, selfNo == null ? "" : selfNo)));
        if (dup != null && dup > 0) {
            throw BizException.of(ErrorCode.SPEC_TEMPLATE_DUPLICATE);
        }

        boolean create = existing == null;
        PrdSpecTemplate t = existing;
        if (create) {
            t = new PrdSpecTemplate();
            t.setTemplateNo(BizKey.next(BizKey.SPEC_TEMPLATE));
            t.setStatus(PrdSpecTemplate.ACTIVE);
        }
        /*
         * scope 由后端写死，请求体里传什么都忽略 —— 否则运营端一个笔误就能造出
         * 一条 entityNo 为空的「商家模板」，而它会出现在**所有**商家的下拉里，
         * 且没有任何人能改它（商家侧只让改自己的）。
         */
        t.setScope(PrdSpecTemplate.PLATFORM);
        t.setEntityNo(null);
        t.setCategoryType(notBlank(cmd.categoryType()) ? cmd.categoryType() : null);
        t.setName(cmd.name().trim());
        t.setOptions(writeJson(cmd.options().stream()
                .map(o -> new MerchantGoodsService.SpecOption(o.code().trim(), o.label().trim()))
                .toList()));
        PrdSpecTemplate row = t;
        DataScopeContext.executeWithoutScope(() ->
                create ? templateMapper.insert(row) : templateMapper.updateById(row));
        return toVO(platformTemplate(t.getTemplateNo()));
    }

    @Override
    @Transactional
    public OpsSpecTemplateVO archiveSpecTemplate(String templateNo) {
        PrdSpecTemplate t = platformTemplate(templateNo);
        t.setStatus(PrdSpecTemplate.DISABLED);
        DataScopeContext.executeWithoutScope(() -> templateMapper.updateById(t));
        return toVO(t);
    }

    @Override
    @Transactional
    public OpsSpecTemplateVO unarchiveSpecTemplate(String templateNo) {
        PrdSpecTemplate t = platformTemplate(templateNo);
        t.setStatus(PrdSpecTemplate.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> templateMapper.updateById(t));
        return toVO(t);
    }

    // ---------------------------------------------------------------- helpers

    private List<String> matchingGoodsNos(String merchantNo, String categoryNo,
                                          String keyword, String status) {
        LambdaQueryWrapper<PrdGoods> w = Wrappers.<PrdGoods>lambdaQuery()
                .eq(notBlank(merchantNo), PrdGoods::getEntityNo, merchantNo)
                .eq(notBlank(categoryNo), PrdGoods::getCategoryNo, categoryNo)
                .like(notBlank(keyword), PrdGoods::getTitle, keyword);
        applyStatus(w, status);
        /*
         * ★ 接数据域（批③）：这个类**只有运营调**（/ops/skus、/ops/spec-templates），
         * 所以 SKU 检索按商品过滤这一步要跟着运营的商家域收敛。
         *
         * ⚠️ 它只在**带过滤条件**时被调用。不带条件的 SKU 列表直接查 prd_sku，
         * 而 prd_sku **没有登记数据域**（未注册表 = 放行）—— 那个口子还在，
         * 登记在 ops-data-scope.test.ts 的 ANCHOR_WAIVED 里，不要以为这一行补上就全防住了。
         */
        return goodsMapper.selectList(w).stream().map(PrdGoods::getGoodsNo).toList();
    }

    /**
     * 状态过滤与 {@code GET /ops/goods} 同一套口径：对外的 PENDING 对应库里的 AUDITING
     * （词典 §11），ON_SALE / OFF_SALE 落在 {@code on_sale} 上且**必须同时要求已过审** ——
     * 少了后半句，一件待审商品只要 on_sale 恰好是 1 就会被当成在售。
     */
    private void applyStatus(LambdaQueryWrapper<PrdGoods> w, String status) {
        if (!notBlank(status)) {
            return;
        }
        switch (status) {
            case "PENDING", AUDITING -> w.eq(PrdGoods::getAuditStatus, AUDITING);
            case REJECTED -> w.eq(PrdGoods::getAuditStatus, REJECTED);
            case "ON_SALE" -> w.eq(PrdGoods::getAuditStatus, "APPROVED").eq(PrdGoods::getOnSale, true);
            case "OFF_SALE" -> w.eq(PrdGoods::getAuditStatus, "APPROVED").eq(PrdGoods::getOnSale, false);
            // 认不出的状态值**不静默放行**：放行的话筛选看着生效、结果是全量，
            // 而「筛错了」与「这个状态下正好有这么多」在只看行数时一模一样
            default -> w.eq(PrdGoods::getAuditStatus, "__NONE__");
        }
    }

    private List<PrdSku> rowsOf(String skuNo) {
        List<PrdSku> rows = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery().eq(PrdSku::getSkuNo, skuNo)));
        if (rows.isEmpty()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return rows;
    }

    private String goodsNoOf(String skuNo) {
        return rowsOf(skuNo).get(0).getGoodsNo();
    }

    private OpsSkuDetailVO detailOf(String skuNo) {
        List<PrdSku> home = rowsOf(skuNo).stream()
                .filter(r -> HOME_MARKET.equals(r.getMarket())).toList();
        return toVOs(home.isEmpty() ? rowsOf(skuNo).subList(0, 1) : home).get(0);
    }

    /** 一批基准市场行 → VO：父商品、商家名、类目名、各市场价都在这里一次性拼齐（不逐行 N 次查）。 */
    private List<OpsSkuDetailVO> toVOs(List<PrdSku> anchors) {
        if (anchors.isEmpty()) {
            return List.of();
        }
        List<String> skuNos = anchors.stream().map(PrdSku::getSkuNo).distinct().toList();
        List<String> goodsNos = anchors.stream().map(PrdSku::getGoodsNo).distinct().toList();

        Map<String, Map<String, Long>> pricesBySku = DataScopeContext.executeWithoutScope(() ->
                        skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery().in(PrdSku::getSkuNo, skuNos)))
                .stream().filter(r -> r.getPrice() != null)
                .collect(Collectors.groupingBy(PrdSku::getSkuNo,
                        Collectors.toMap(PrdSku::getMarket, PrdSku::getPrice, (a, b) -> a)));

        Map<String, PrdGoods> goodsByNo = DataScopeContext.executeWithoutScope(() ->
                        goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery().in(PrdGoods::getGoodsNo, goodsNos)))
                .stream().collect(Collectors.toMap(PrdGoods::getGoodsNo, g -> g, (a, b) -> a));

        Set<String> merchantNos = goodsByNo.values().stream().map(PrdGoods::getEntityNo)
                .collect(Collectors.toSet());
        Map<String, MerchantQueryPort.MerchantBrief> merchants = merchantNos.isEmpty()
                ? Map.of() : merchantPort.findAll(merchantNos);

        // 类目名批量拼：prd_goods 只存 categoryNo。总量有限，一次取全表比按需查 N 次划算
        Map<String, String> categoryNames = categoryService.list(null, null, true).stream()
                .collect(Collectors.toMap(ai.neargo.shop.product.dto.OpsCategoryVO::categoryNo,
                        ai.neargo.shop.product.dto.OpsCategoryVO::name, (a, b) -> a));

        List<OpsSkuDetailVO> out = new ArrayList<>();
        for (PrdSku s : anchors) {
            PrdGoods g = goodsByNo.get(s.getGoodsNo());
            Map<String, String> titleI18n = readMap(g == null ? null : g.getTitleI18n());
            var brief = g == null ? null : merchants.get(g.getEntityNo());
            out.add(new OpsSkuDetailVO(
                    s.getSkuNo(), s.getGoodsNo(),
                    new OpsSkuDetailVO.TitleVO(g == null ? s.getSpec() : g.getTitle(),
                            titleI18n.get("en"), titleI18n.get("ar")),
                    g == null ? null : g.getEntityNo(),
                    brief == null ? (g == null ? null : g.getEntityNo()) : brief.merchantName(),
                    g == null ? null : g.getCategoryNo(),
                    g == null || g.getCategoryNo() == null ? null : categoryNames.get(g.getCategoryNo()),
                    statusOf(g),
                    pricesBySku.getOrDefault(s.getSkuNo(), Map.of()),
                    nz(s.getStock()), nz(s.getPresaleQuota()), nz(s.getSoldCount()),
                    iso(s.getCutoffAt()), iso(s.getArriveAt()), iso(s.getCreatedAt()),
                    g == null ? null : g.getAuditReason()));
        }
        return out;
    }

    /** 与 {@code GET /ops/goods} 同一套口径 —— 两处不一致会让同一件商品在两个 tab 里显示成两种状态。 */
    private String statusOf(PrdGoods g) {
        if (g == null) {
            return null;
        }
        if (AUDITING.equals(g.getAuditStatus())) {
            // 对外叫 PENDING（词典 §11），库里那列仍是 AUDITING
            return "PENDING";
        }
        if (REJECTED.equals(g.getAuditStatus())) {
            return REJECTED;
        }
        return Boolean.TRUE.equals(g.getOnSale()) ? "ON_SALE" : "OFF_SALE";
    }

    private PrdSpecTemplate platformTemplate(String templateNo) {
        PrdSpecTemplate t = DataScopeContext.executeWithoutScope(() ->
                templateMapper.selectOne(Wrappers.<PrdSpecTemplate>lambdaQuery()
                        .eq(PrdSpecTemplate::getTemplateNo, templateNo)
                        .eq(PrdSpecTemplate::getScope, PrdSpecTemplate.PLATFORM)
                        .last("limit 1")));
        if (t == null) {
            // 商家模板在这里一律 404 而不是 403：别家存了什么模板也不该被平台端顺手改掉，
            // 更不该被探知存在与否
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return t;
    }

    private OpsSpecTemplateVO toVO(PrdSpecTemplate t) {
        List<OpsSpecTemplateVO.Option> options;
        try {
            options = t.getOptions() == null ? List.of()
                    : json.readValue(t.getOptions(), new TypeReference<List<OpsSpecTemplateVO.Option>>() {
                    });
        } catch (RuntimeException e) {
            options = List.of();
        }
        boolean archived = PrdSpecTemplate.DISABLED.equals(t.getStatus());
        return new OpsSpecTemplateVO(t.getTemplateNo(), t.getScope(), t.getCategoryType(),
                t.getName(), options,
                // 归档时间没有单独一列：DISABLED 的最后一次更新就是它被归档的时刻。
                // 单开一列要保证与 status 永远同步，而两个字段总有一天会不同步
                archived ? iso(t.getUpdatedAt()) : null,
                iso(t.getCreatedAt()));
    }

    private String writeJson(Object v) {
        return json.writeValueAsString(v);
    }

    private Map<String, String> readMap(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(raw, new TypeReference<Map<String, String>>() {
            });
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /**
     * 端上传来的时间：ISO 瞬时（{@code 2026-08-07T10:00:00Z}）与本地时间两种都收。
     *
     * <p>只收一种的代价见过：运营端表单里手打的是 {@code 2026-08-07T10:00}，
     * 而 mock 数据里全是带 Z 的 —— 于是「mock 上能存，连真后端就报参数错误」，
     * 且错误信息指着一个格式完全正确的时间。
     */
    private LocalDateTime parseTime(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(raw);
            } catch (DateTimeParseException e) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
