package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.dto.CategorySpecVO;
import ai.neargo.shop.product.dto.CategoryVO;
import ai.neargo.shop.product.dto.SpecTemplateVO;
import ai.neargo.shop.product.entity.PrdCategorySpec;
import ai.neargo.shop.product.entity.PrdCategorySpecValue;
import ai.neargo.shop.product.entity.PrdMerchantSpecOverride;
import ai.neargo.shop.product.entity.PrdSpecDim;
import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.entity.PrdSpecValue;
import ai.neargo.shop.product.mapper.ProductMappers.CategorySpecMapper;
import ai.neargo.shop.product.mapper.ProductMappers.CategorySpecValueMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecDimMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecValueMapper;
import ai.neargo.shop.product.service.CategoryService;
import ai.neargo.shop.product.service.SpecLibraryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.SpecNormalizer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规格库读侧的组装。
 *
 * <p>三张表拼出商家看到的那几组规格：{@code prd_category_spec}（这一类目用哪些维度）
 * ⋈ {@code prd_spec_dim}（维度是什么）⋈ {@code prd_spec_value}（有哪些值），
 * 中间再让 {@code prd_category_spec_value} 裁一刀（这一类目只给其中几个值，并且可以换个说法）。
 */
@Service
public class SpecLibraryServiceImpl implements SpecLibraryService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SpecLibraryServiceImpl.class);

    private final SpecDimMapper dimMapper;
    private final SpecValueMapper valueMapper;
    private final CategorySpecMapper catSpecMapper;
    private final CategorySpecValueMapper catValueMapper;
    private final CategoryService categoryService;
    /** 合并值时要改写 SKU 快照 —— 那是「跨店可比」真正的落点 */
    private final SkuMapper skuMapper;
    /** 统计「这个维度用在几件商品上」—— 停用前要知道自己在动多大范围 */
    private final ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapper;
    /** 「我的规格」按货架类目分组 —— 这家店摆了哪几类，只有商家域知道 */
    private final ai.neargo.shop.spi.user.StoreCategoryPort storeCategoryPort;
    /** 商家对平台规格的覆盖（V213）：本店用哪几个、什么顺序、叫什么 */
    private final ai.neargo.shop.product.mapper.ProductMappers.MerchantSpecOverrideMapper overrideMapper;

    public SpecLibraryServiceImpl(SpecDimMapper dimMapper, SpecValueMapper valueMapper,
                                  CategorySpecMapper catSpecMapper,
                                  CategorySpecValueMapper catValueMapper,
                                  CategoryService categoryService,
                                  SkuMapper skuMapper,
                                  ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper goodsMapper,
                                  ai.neargo.shop.spi.user.StoreCategoryPort storeCategoryPort,
                                  ai.neargo.shop.product.mapper.ProductMappers.MerchantSpecOverrideMapper overrideMapper) {
        this.storeCategoryPort = storeCategoryPort;
        this.overrideMapper = overrideMapper;
        this.skuMapper = skuMapper;
        this.goodsMapper = goodsMapper;
        this.dimMapper = dimMapper;
        this.valueMapper = valueMapper;
        this.catSpecMapper = catSpecMapper;
        this.catValueMapper = catValueMapper;
        this.categoryService = categoryService;
    }

    @Override
    public List<SpecTemplateVO> templatesForCategory(String merchantNo, String categoryNo) {
        if (categoryNo == null || categoryNo.isBlank()) {
            return List.of();
        }
        List<PrdCategorySpec> binds = activeBindings(categoryNo);
        if (binds.isEmpty()) {
            return List.of();
        }
        Map<String, PrdSpecDim> dims = dimsOf(binds.stream().map(PrdCategorySpec::getDimNo).toList());
        Map<String, List<PrdCategorySpecValue>> subsets = subsetsOf(categoryNo);
        /*
         * 商家的覆盖（V213）：本店用哪几个、什么顺序、叫什么。
         * **稀疏** —— 没有行就完全跟平台走，所以运营新加的维度会自动到达没动过手的商家。
         */
        Overrides ov = overridesOf(merchantNo, categoryNo);

        List<SpecTemplateVO> out = new ArrayList<>();
        for (PrdCategorySpec b : binds) {
            PrdSpecDim dim = dims.get(b.getDimNo());
            if (dim == null) {
                // 绑定指向一个已归档/不存在的维度：跳过而不是抛 —— 运营归档一个维度
                // 不该让所有商家的建品页 500。守卫测试会把这种悬空绑定报出来
                continue;
            }
            /*
             * **只给销售规格。** PROP（材质、产地、保质期）下发到这里，商家就会把它们
             * 建成规格维度，于是「不锈钢 × 24cm × 黑色」变成一个要单独定价与备库存的行 ——
             * 而他其实只想说「这口锅是不锈钢的」。PROP 的入口是二期的「商品参数」区。
             */
            String usage = b.getUsageType() != null && !b.getUsageType().isBlank()
                    ? b.getUsageType() : dim.getUsageType();
            if (!PrdSpecDim.SALE.equals(usage)) {
                continue;
            }
            // 本店停用的维度：整条不下发。停用维度会连带它下面的取值一起消失
            if (!ov.dimEnabled(dim.getDimNo())) {
                continue;
            }
            /*
             * **先减后加。**减 = 他移除的那几档；加 = 类目没给、但他从平台值池里挑来的
             * （「平台重量有 750g，只是蔬菜这一类没配它，而我这袋就是 750g」）。
             * 只能减的话，商家碰到类目子集里没有的档位就只剩手输一条路 ——
             * 而手输的值没有编码，跨店聚合就断了。
             */
            List<SpecTemplateVO.Option> options = ov.applyToValues(dim.getDimNo(),
                    optionsOf(merchantNo, dim, subsets.getOrDefault(b.getDimNo(), List.of())),
                    () -> optionsOf(merchantNo, dim, List.of()));
            if (options.isEmpty()) {
                continue;
            }
            /*
             * **templateNo 位置放 dimNo。**契约里这个字段的语义是「这组规格取自哪个模板」，
             * 而在新模型里回答这个问题的就是维度编号。端上原样回传，保存时我们靠它
             * 定位维度、把选项反查成值编号（见 resolveValueNos）。
             */
            out.add(new SpecTemplateVO(dim.getDimNo(), PrdSpecDim.PLATFORM,
                    categoryService.categoryTypeOf(categoryNo), categoryNo,
                    // 本店叫法优先 —— 只换展示，dimNo 不变，跨店聚合照常
                    ov.dimLabel(dim.getDimNo(), dim.getName()),
                    options, null, Boolean.TRUE.equals(b.getIsPrimary())));
        }
        /*
         * **他自己加进来的规格**：类目没绑，但他在「我的规格」里加了。
         *
         * 只遍历类目绑定的话，加进来的规格落了库却永远不显示 —— 界面上就是
         * 「点了 ＋ 选了一个，什么都没发生」。与档位那层的「加」是同一件事：
         * 平台给的那份是起点，不是上限。
         */
        Set<String> shown = out.stream().map(SpecTemplateVO::templateNo).collect(Collectors.toSet());
        for (String dimNo : ov.addedDims()) {
            if (shown.contains(dimNo)) {
                continue;
            }
            PrdSpecDim dim = DataScopeContext.executeWithoutScope(() ->
                    dimMapper.selectOne(Wrappers.<PrdSpecDim>lambdaQuery()
                            .eq(PrdSpecDim::getDimNo, dimNo)
                            .eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE).last("limit 1")));
            if (dim == null || !PrdSpecDim.SALE.equals(dim.getUsageType())) {
                continue;   // 归档了或是 PROP：跳过而不是抛，理由同上面那条悬空绑定
            }
            // 类目没给子集，所以给全量值池，再让他自己的取舍去裁
            List<SpecTemplateVO.Option> options = ov.applyToValues(dimNo,
                    optionsOf(merchantNo, dim, List.of()),
                    () -> optionsOf(merchantNo, dim, List.of()));
            /*
             * **一个档位都没有也要给出来。**刚自建的规格必然是这个样子
             * （建的时候只填了名字），跳过它的话商家看到的是「我建了个规格，
             * 但它没出现」—— 而他要做的下一步恰恰是进去给它加档位。
             *
             * 平台维度不会走到这里没档位：类目绑定那圈里的空维度仍然跳过
             * （那是运营配错了，不该让商家看见一个点进去空白的规格）。
             */
            /*
             * **scope 照实给**，不要一律写 PLATFORM：他自己建的规格也走这条路进来，
             * 而端上要靠 scope 给它标一个「本店」—— 少了这个标记，
             * 「辣度」和「重量」在他眼里就是一回事，而前者不参与跨店比价。
             */
            out.add(new SpecTemplateVO(dim.getDimNo(), dim.getScope(),
                    categoryService.categoryTypeOf(categoryNo), categoryNo,
                    ov.dimLabel(dim.getDimNo(), dim.getName()), options,
                    PrdSpecDim.MERCHANT.equals(dim.getScope()) ? merchantNo : null, false));
        }

        /*
         * 本店排过序的排前面（按商家给的 sort），没排过的**保持平台顺序跟在后面**。
         * 不给没排过的编一个号：那样运营新加的维度会插到他排好的中间去，
         * 而他不知道自己什么时候动过它。
         */
        out.sort(Comparator.comparingInt(t -> ov.dimSort(t.templateNo())));
        return out;
    }

    /**
     * 一个商家在一个类目下的全部覆盖，按维度/取值索引好。
     *
     * <p>取不到就当没有覆盖（全跟平台）—— 覆盖是偏好，不是数据：
     * 读失败让整个建品页 500 是不成比例的。
     */
    private Overrides overridesOf(String merchantNo, String categoryNo) {
        if (merchantNo == null || merchantNo.isBlank()) {
            return Overrides.EMPTY;
        }
        try {
            List<PrdMerchantSpecOverride> rows = DataScopeContext.executeWithoutScope(() ->
                    overrideMapper.selectList(Wrappers.<PrdMerchantSpecOverride>lambdaQuery()
                            .eq(PrdMerchantSpecOverride::getMerchantNo, merchantNo)
                            .eq(PrdMerchantSpecOverride::getCategoryNo, categoryNo)));
            return rows.isEmpty() ? Overrides.EMPTY : new Overrides(rows);
        } catch (Exception e) {
            log.warn("[规格覆盖] 读失败，本次按无覆盖处理：merchant={} category={}",
                    merchantNo, categoryNo, e);
            return Overrides.EMPTY;
        }
    }

    /** 覆盖的合并规则集中在这里 —— 散在读侧各处的话，四条规则迟早会各走各的 */
    private static final class Overrides {

        static final Overrides EMPTY = new Overrides(List.of());

        /** dimNo → 维度级覆盖 */
        private final Map<String, PrdMerchantSpecOverride> dims = new java.util.HashMap<>();
        /** dimNo + "\u0000" + valueNo → 取值级覆盖 */
        private final Map<String, PrdMerchantSpecOverride> values = new java.util.HashMap<>();

        Overrides(List<PrdMerchantSpecOverride> rows) {
            for (PrdMerchantSpecOverride r : rows) {
                if (PrdMerchantSpecOverride.DIM_LEVEL.equals(r.getValueNo())) {
                    dims.put(r.getDimNo(), r);
                } else {
                    values.put(r.getDimNo() + "\u0000" + r.getValueNo(), r);
                }
            }
        }

        String dimLabel(String dimNo, String fallback) {
            PrdMerchantSpecOverride r = dims.get(dimNo);
            return r != null && r.getLabelOverride() != null && !r.getLabelOverride().isBlank()
                    ? r.getLabelOverride() : fallback;
        }

        /** 他显式声明要用、而类目没绑的那几个规格 —— 读侧要把它们补进来 */
        java.util.Set<String> addedDims() {
            java.util.Set<String> out = new java.util.LinkedHashSet<>();
            dims.forEach((dimNo, r) -> {
                if (!Boolean.FALSE.equals(r.getEnabled())) {
                    out.add(dimNo);
                }
            });
            return out;
        }

        boolean dimEnabled(String dimNo) {
            PrdMerchantSpecOverride r = dims.get(dimNo);
            return r == null || !Boolean.FALSE.equals(r.getEnabled());
        }

        /** 没排过的给一个大数 —— 排前面的是他动过手的那几个，其余保持平台顺序跟在后面 */
        int dimSort(String dimNo) {
            PrdMerchantSpecOverride r = dims.get(dimNo);
            return r != null && r.getSort() != null ? r.getSort() : 10_000;
        }

        /**
         * 去掉本店移除的那几档，再补上他从平台值池挑来的。
         *
         * @param wholePool 懒取的全量值池 —— 没有「加」的覆盖时不查它
         */
        List<SpecTemplateVO.Option> applyToValues(String dimNo, List<SpecTemplateVO.Option> src,
                                                  java.util.function.Supplier<List<SpecTemplateVO.Option>> wholePool) {
            if (values.isEmpty()) {
                return src;
            }
            List<SpecTemplateVO.Option> out = new ArrayList<>();
            java.util.Set<String> have = new java.util.LinkedHashSet<>();
            for (SpecTemplateVO.Option o : src) {
                String code = o.code() == null ? "" : o.code();
                PrdMerchantSpecOverride r = values.get(dimNo + "\u0000" + code);
                if (r != null && Boolean.FALSE.equals(r.getEnabled())) {
                    continue;
                }
                out.add(o);
                have.add(code);
            }
            // 他挑进来的：类目子集里没有，但平台值池里有
            java.util.Set<String> wanted = new java.util.LinkedHashSet<>();
            for (var e : values.entrySet()) {
                if (!e.getKey().startsWith(dimNo + "\u0000") || Boolean.FALSE.equals(e.getValue().getEnabled())) {
                    continue;
                }
                String code = e.getKey().substring(dimNo.length() + 1);
                if (!have.contains(code)) {
                    wanted.add(code);
                }
            }
            if (!wanted.isEmpty()) {
                for (SpecTemplateVO.Option o : wholePool.get()) {
                    if (wanted.contains(o.code() == null ? "" : o.code())) {
                        out.add(o);
                    }
                }
            }
            /*
             * 他排过的按 sort 在前，没排过的（运营后加的）保持原顺序跟在后面。
             * 不给没排过的编号：那样新档位会插进他排好的中间，而他不知道何时动过它。
             */
            out.sort(Comparator.comparingInt(o -> valueSort(dimNo, o.code() == null ? "" : o.code())));
            return out;
        }

        private int valueSort(String dimNo, String code) {
            PrdMerchantSpecOverride r = values.get(dimNo + "\u0000" + code);
            return r != null && r.getSort() != null ? r.getSort() : 10_000;
        }

        /*
         * 键用 **code** 而不是 valueNo：Option 里只有 code + label（契约如此），
         * 而 code 在一个维度内唯一。改 Option 加 valueNo 要连着动三端，不值。
         */
    }

    @Override
    public List<SpecTemplateVO> pickableDims(String merchantNo, String categoryNo) {
        /*
         * 本类目已配的那几条排最前 —— 它们是平台针对这一类目的判断，
         * 比通用维度更该被选中。复用 templatesForCategory，连主维度标记一起带出来。
         */
        List<SpecTemplateVO> out = new ArrayList<>(
                categoryNo == null || categoryNo.isBlank()
                        ? List.<SpecTemplateVO>of()
                        : templatesForCategory(merchantNo, categoryNo));
        Set<String> seen = out.stream().map(SpecTemplateVO::templateNo).collect(Collectors.toSet());

        /*
         * 再给平台维度池里剩下的。**通用的（universal）才给**：
         * 专用维度（房型、体型、衣物类型）是给特定几类用的，摆在别的类目下
         * 只会让他选中一个牛头不对马嘴的维度 —— 而那比没得选更糟。
         */
        List<PrdSpecDim> platform = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectList(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getScope, PrdSpecDim.PLATFORM)
                        .eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE)
                        .eq(PrdSpecDim::getUniversal, true)
                        .orderByAsc(PrdSpecDim::getSort)));
        for (PrdSpecDim dim : platform) {
            if (seen.contains(dim.getDimNo()) || !PrdSpecDim.SALE.equals(dim.getUsageType())) {
                continue;
            }
            List<SpecTemplateVO.Option> options = optionsOf(merchantNo, dim, List.of());
            if (options.isEmpty()) {
                continue;
            }
            // categoryNo 传 null：它不是「这一类目的」，端上靠这个分组
            out.add(new SpecTemplateVO(dim.getDimNo(), PrdSpecDim.PLATFORM, null, null,
                    dim.getName(), options, null, false));
            seen.add(dim.getDimNo());
        }

        /*
         * 最后是这家店自己建过的。**摆出来是为了让他复用而不是再建一个** ——
         * 后端有「与自己重名就复用」的兜底，但那同样要他敲对字；
         * 上次输「辣度」这次输「辣味」，库里就是两个维度。
         */
        List<PrdSpecDim> mine = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectList(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getScope, PrdSpecDim.MERCHANT)
                        .eq(PrdSpecDim::getEntityNo, merchantNo)
                        .eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE)
                        .orderByAsc(PrdSpecDim::getSort)));
        for (PrdSpecDim dim : mine) {
            if (seen.contains(dim.getDimNo())) {
                continue;
            }
            out.add(new SpecTemplateVO(dim.getDimNo(), PrdSpecDim.MERCHANT, null, null,
                    dim.getName(), optionsOf(merchantNo, dim, List.of()), merchantNo, false));
        }
        return out;
    }

    @Override
    public Map<String, String> resolveValueNos(String merchantNo, String dimNo, List<String> labels) {
        if (dimNo == null || dimNo.isBlank() || labels == null || labels.isEmpty()) {
            return Map.of();
        }
        List<PrdSpecValue> values = valuesOf(merchantNo, dimNo);
        Map<String, String> byLabel = new LinkedHashMap<>();
        for (PrdSpecValue v : values) {
            /*
             * **两侧都过 SpecNormalizer**。库里的 label 是写入时规范化过的（见 addValue），
             * 而商家提交的是手打的原文：「500 g」「五百克」「500G」都该落到同一个 500g 上。
             * 此前这里直接 `label.trim()` 比对，于是本类注释里自陈的那个毛病
             * ——「三家店的 500g / 五百克 / 0.5kg 永远聚不到一起」—— 在归一这一步原样复发。
             */
            putValueKey(byLabel, v.getLabel(), v.getValueNo());
            // 码也认：端上有时回传的是 code（走模板那条路），认两种比认一种稳
            putValueKey(byLabel, v.getCode(), v.getValueNo());
        }
        /*
         * 类目级的换名也要认得回来：500g 在蔬菜下显示成「约1斤」，商家提交的就是「约1斤」。
         * 不认的话这一格永远归不了一 —— 而它恰恰是生鲜类目下最常用的那几档。
         */
        for (PrdCategorySpecValue cv : DataScopeContext.executeWithoutScope(() ->
                catValueMapper.selectList(Wrappers.<PrdCategorySpecValue>lambdaQuery()
                        .eq(PrdCategorySpecValue::getDimNo, dimNo)
                        .isNotNull(PrdCategorySpecValue::getLabelOverride)))) {
            putValueKey(byLabel, cv.getLabelOverride(), cv.getValueNo());
        }
        /*
         * **别名走最后一轮**，不能跟正式标签挤在同一个循环里。
         * 表是先到先得，同一轮的话「排在前面那个值的别名」会顶掉
         * 「排在后面那个值的正式标签」—— 比如某值别名写了「单包」，
         * 而另有一个值的正式标签就叫「单包」，谁赢取决于 sort 顺序，纯属碰运气。
         * 分轮之后规则是确定的：**正式标签、码、类目级换名，三者都永远赢别名**。
         */
        for (PrdSpecValue v : values) {
            /*
             * **别名也要认**。这一列不是摆设：C1「单件」挂着「单个 / 单包 / 1件」，
             * C6「6件装」挂着「6只装 / 6卷」，合并值时还会把被并掉那条的标签收进来
             * （见 mergeValues）。而此前这条路径根本不查它 ——
             * 于是运营辛苦维护的别名对「跨店可比」一点贡献都没有，
             * 商家写「单个」就是归不了一。
             *
             * 同一个类里的 ensureValue 早就是按「规范化后的 label + aliases」比对的，
             * 两条路径认的东西不一样，本身就是个要命的不一致：
             * 商家自助加值时认得出重复，系统给 SKU 盖编号时却认不出同一个值。
             */
            for (String alias : readAliases(v.getAliases())) {
                putValueKey(byLabel, alias, v.getValueNo());
            }
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (String label : labels) {
            String norm = SpecNormalizer.label(label);
            String no = norm == null || norm.isBlank() ? null : byLabel.get(norm);
            if (no != null) {
                // 键回原文：调用方拿它跟 optionValues 里的文案对位，换成规范化后的会对不上
                out.put(label, no);
            }
        }
        return out;
    }

    @Override
    public List<CategorySpecVO> catalog() {
        List<PrdCategorySpec> all = DataScopeContext.executeWithoutScope(() ->
                catSpecMapper.selectList(Wrappers.<PrdCategorySpec>lambdaQuery()
                        .eq(PrdCategorySpec::getStatus, PrdSpecDim.ACTIVE)));
        Map<String, List<PrdCategorySpec>> byCat = all.stream()
                .collect(Collectors.groupingBy(PrdCategorySpec::getCategoryNo));
        Map<String, PrdSpecDim> dims = dimsOf(all.stream().map(PrdCategorySpec::getDimNo).toList());
        Map<String, Map<String, List<PrdCategorySpecValue>>> subsetsByCat = new LinkedHashMap<>();

        List<CategorySpecVO> out = new ArrayList<>();
        for (CategoryVO lv1 : categoryService.tree()) {
            for (CategoryVO lv2 : lv1.children()) {
                String catNo = lv2.categoryNo();
                List<PrdCategorySpec> binds = byCat.getOrDefault(catNo, List.of()).stream()
                        .sorted(Comparator.comparing((PrdCategorySpec b) ->
                                        Boolean.TRUE.equals(b.getIsPrimary()) ? 0 : 1)
                                .thenComparingInt(b -> b.getSort() == null ? 100 : b.getSort()))
                        .toList();
                Map<String, List<PrdCategorySpecValue>> subsets =
                        subsetsByCat.computeIfAbsent(catNo, this::subsetsOf);

                List<CategorySpecVO.DimVO> dimVOs = new ArrayList<>();
                for (PrdCategorySpec b : binds) {
                    PrdSpecDim dim = dims.get(b.getDimNo());
                    if (dim == null) {
                        continue;
                    }
                    List<CategorySpecVO.ValueVO> values = valueVOs(dim,
                            subsets.getOrDefault(b.getDimNo(), List.of()));
                    String usage = b.getUsageType() != null && !b.getUsageType().isBlank()
                            ? b.getUsageType() : dim.getUsageType();
                    dimVOs.add(new CategorySpecVO.DimVO(dim.getDimNo(), dim.getCode(), dim.getName(),
                            dim.getValueType(), dim.getUnit(), usage,
                            Boolean.TRUE.equals(dim.getUniversal()),
                            Boolean.TRUE.equals(b.getIsPrimary()),
                            values.size(), values));
                }
                // 一条都没绑的类目**也要出现在表里** —— 缺口不列出来就永远补不上
                out.add(new CategorySpecVO(catNo, lv2.name(), lv1.name(),
                        lv2.template(), dimVOs.size(), dimVOs));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 运营端维护

    /**
     * 维度名的禁用词。**「规格」「型号」「类型」这类万能词不能当维度名** ——
     * 它们什么都不说：商家看到一个叫「规格」的规格组，仍旧不知道该填什么，
     * 而两家店各建一个「规格」，值也永远聚不到一起。
     */
    private static final Set<String> BANNED_DIM_NAMES = Set.of("规格", "型号", "类型", "属性", "参数");

    @Override
    public List<SpecDimVO> listDims(Boolean universal, String keyword, boolean includeArchived) {
        var w = Wrappers.<PrdSpecDim>lambdaQuery().eq(PrdSpecDim::getScope, PrdSpecDim.PLATFORM);
        if (universal != null) {
            w.eq(PrdSpecDim::getUniversal, universal);
        }
        if (!includeArchived) {
            w.eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE);
        }
        if (keyword != null && !keyword.isBlank()) {
            String k = keyword.trim();
            w.and(q -> q.like(PrdSpecDim::getName, k).or().like(PrdSpecDim::getCode, k));
        }
        w.orderByAsc(PrdSpecDim::getSort);
        List<PrdSpecDim> dims = DataScopeContext.executeWithoutScope(() -> dimMapper.selectList(w));
        if (dims.isEmpty()) {
            return List.of();
        }
        List<String> dimNos = dims.stream().map(PrdSpecDim::getDimNo).toList();

        // 值：一次查全，别逐个维度查（27 个维度 = 27 次往返）
        Map<String, List<PrdSpecValue>> valuesByDim = DataScopeContext.executeWithoutScope(() ->
                        valueMapper.selectList(Wrappers.<PrdSpecValue>lambdaQuery()
                                .in(PrdSpecValue::getDimNo, dimNos)
                                .orderByAsc(PrdSpecValue::getSort))).stream()
                .filter(v -> includeArchived || PrdSpecDim.ACTIVE.equals(v.getStatus()))
                .collect(Collectors.groupingBy(PrdSpecValue::getDimNo, LinkedHashMap::new, Collectors.toList()));

        // 被几个类目绑着 —— 归档一个维度前，运营要知道自己在动多大范围
        Map<String, Long> inUse = DataScopeContext.executeWithoutScope(() ->
                        catSpecMapper.selectList(Wrappers.<PrdCategorySpec>lambdaQuery()
                                .in(PrdCategorySpec::getDimNo, dimNos)
                                .eq(PrdCategorySpec::getStatus, PrdSpecDim.ACTIVE))).stream()
                .collect(Collectors.groupingBy(PrdCategorySpec::getDimNo, Collectors.counting()));

        return dims.stream().map(d -> {
            List<PrdSpecValue> vs = valuesByDim.getOrDefault(d.getDimNo(), List.of());
            return new SpecDimVO(d.getDimNo(), d.getCode(), d.getName(), d.getValueType(),
                    d.getUnit(), d.getUsageType(), Boolean.TRUE.equals(d.getUniversal()),
                    d.getScope(), d.getEntityNo(), d.getSort() == null ? 100 : d.getSort(),
                    d.getStatus(), vs.size(), inUse.getOrDefault(d.getDimNo(), 0L).intValue(),
                    vs.stream().map(SpecLibraryServiceImpl::toValueVO).toList());
        }).toList();
    }

    @Override
    @Transactional
    public SpecDimVO saveDim(SaveDimCommand cmd) {
        String name = SpecNormalizer.label(cmd.name());
        if (name == null || name.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 命名规范在这里落地，不只写在文档里：
         *   · 禁用万能词 —— 见 BANNED_DIM_NAMES
         *   · 同名唯一 —— 两个都叫「尺寸」的维度，商家选哪个都对不上聚合
         */
        if (BANNED_DIM_NAMES.contains(name)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PrdSpecDim row = cmd.dimNo() == null || cmd.dimNo().isBlank() ? null
                : DataScopeContext.executeWithoutScope(() ->
                        dimMapper.selectOne(Wrappers.<PrdSpecDim>lambdaQuery()
                                .eq(PrdSpecDim::getDimNo, cmd.dimNo()).last("limit 1")));
        final String selfNo = row == null ? "" : row.getDimNo();
        Long dup = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectCount(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getScope, PrdSpecDim.PLATFORM)
                        .eq(PrdSpecDim::getName, name)
                        .ne(!selfNo.isEmpty(), PrdSpecDim::getDimNo, selfNo)));
        if (dup != null && dup > 0) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        boolean fresh = row == null;
        if (fresh) {
            row = new PrdSpecDim();
            row.setDimNo("SD_" + cmd.code());
            row.setCode(cmd.code());
            row.setScope(PrdSpecDim.PLATFORM);
            row.setStatus(PrdSpecDim.ACTIVE);
        }
        row.setName(name);
        row.setValueType(cmd.valueType() == null ? PrdSpecDim.ENUM : cmd.valueType());
        row.setUnit(cmd.unit() == null || cmd.unit().isBlank() ? null : cmd.unit().trim());
        row.setUsageType(cmd.usageType() == null ? PrdSpecDim.SALE : cmd.usageType());
        row.setUniversal(cmd.universal());
        row.setSort(cmd.sort() == null ? 100 : cmd.sort());
        PrdSpecDim toSave = row;
        DataScopeContext.executeWithoutScope(() ->
                fresh ? dimMapper.insert(toSave) : dimMapper.updateById(toSave));
        return toDimVO(toSave, List.of(), 0);
    }

    @Override
    @Transactional
    public SpecDimVO archiveDim(String dimNo, boolean archived) {
        PrdSpecDim row = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectOne(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getDimNo, dimNo).last("limit 1")));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        row.setStatus(archived ? PrdSpecDim.ARCHIVED : PrdSpecDim.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> dimMapper.updateById(row));
        return toDimVO(row, List.of(), 0);
    }

    @Override
    @Transactional
    public SpecValueVO saveValue(SaveValueCommand cmd) {
        PrdSpecDim dim = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectOne(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getDimNo, cmd.dimNo()).last("limit 1")));
        if (dim == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        String label = SpecNormalizer.label(cmd.label());
        if (label == null || label.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **QUANT 维度的值必须有归一量。**没有它，这个值排不了序也比不了价 ——
         * 等于在一个专门用来归一的库里又造了一个字符串。
         */
        if (PrdSpecDim.QUANT.equals(dim.getValueType())
                && (cmd.numericValue() == null || cmd.numericValue().signum() <= 0)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        PrdSpecValue row = cmd.valueNo() == null || cmd.valueNo().isBlank() ? null
                : DataScopeContext.executeWithoutScope(() ->
                        valueMapper.selectOne(Wrappers.<PrdSpecValue>lambdaQuery()
                                .eq(PrdSpecValue::getValueNo, cmd.valueNo()).last("limit 1")));
        boolean fresh = row == null;
        if (fresh) {
            row = new PrdSpecValue();
            row.setValueNo("SV_" + dim.getCode() + "_" + cmd.code());
            row.setDimNo(dim.getDimNo());
            row.setCode(cmd.code());
            row.setScope(PrdSpecDim.PLATFORM);
            row.setStatus(PrdSpecDim.ACTIVE);
        }
        row.setLabel(label);
        row.setNumericValue(cmd.numericValue());
        row.setNumericUnit(cmd.numericValue() == null ? null
                : (cmd.numericUnit() == null ? dim.getUnit() : cmd.numericUnit()));
        row.setAliases(writeAliases(cmd.aliases()));
        row.setSort(cmd.sort() == null ? 100 : cmd.sort());
        PrdSpecValue toSave = row;
        DataScopeContext.executeWithoutScope(() ->
                fresh ? valueMapper.insert(toSave) : valueMapper.updateById(toSave));
        return toValueVO(toSave);
    }

    @Override
    @Transactional
    public SpecValueVO archiveValue(String valueNo, boolean archived) {
        PrdSpecValue row = mustValue(valueNo);
        row.setStatus(archived ? PrdSpecDim.ARCHIVED : PrdSpecDim.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> valueMapper.updateById(row));
        return toValueVO(row);
    }

    @Override
    @Transactional
    public SpecValueVO promoteValue(String valueNo) {
        PrdSpecValue row = mustValue(valueNo);
        // 编号不变是这一步的全部意义：已经按它建好的商品不用重建
        row.setScope(PrdSpecDim.PLATFORM);
        row.setEntityNo(null);
        DataScopeContext.executeWithoutScope(() -> valueMapper.updateById(row));
        return toValueVO(row);
    }

    @Override
    @Transactional
    public int mergeValues(String intoValueNo, List<String> fromValueNos) {
        PrdSpecValue keep = mustValue(intoValueNo);
        List<String> from = fromValueNos == null ? List.of() : fromValueNos.stream()
                .filter(x -> x != null && !x.equals(intoValueNo)).distinct().toList();
        if (from.isEmpty()) {
            return 0;
        }
        List<PrdSpecValue> losers = DataScopeContext.executeWithoutScope(() ->
                valueMapper.selectList(Wrappers.<PrdSpecValue>lambdaQuery()
                        .in(PrdSpecValue::getValueNo, from)));
        /*
         * **只在同一个维度内合并。**跨维度合并（把一个颜色并进一个重量）没有任何合理解释，
         * 而它造成的错位不会报错：那批商品的重量从此显示成「黑色」。
         */
        if (losers.stream().anyMatch(v -> !keep.getDimNo().equals(v.getDimNo()))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        // ① 别名先并过去：下次有人再输被合并的那个说法，撞车检测认得出来
        java.util.LinkedHashSet<String> aliases = new java.util.LinkedHashSet<>(readAliases(keep.getAliases()));
        for (PrdSpecValue v : losers) {
            aliases.add(v.getLabel());
            aliases.addAll(readAliases(v.getAliases()));
        }
        aliases.remove(keep.getLabel());
        keep.setAliases(writeAliases(new ArrayList<>(aliases)));
        DataScopeContext.executeWithoutScope(() -> valueMapper.updateById(keep));

        // ② 被合并的值退役 —— 不删，历史商品要靠它解释自己的快照
        for (PrdSpecValue v : losers) {
            v.setStatus(PrdSpecValue.MERGED);
            v.setMergedInto(intoValueNo);
            DataScopeContext.executeWithoutScope(() -> valueMapper.updateById(v));
        }

        // ③ 改写 SKU 快照：不改的话那批商品仍指向退役编号，聚合时各算各的
        return rewriteSnapshots(from, intoValueNo);
    }

    /**
     * 把 SKU 快照里的旧值编号换成新的。
     *
     * <p>逐条读改而不是一条 UPDATE ... REPLACE()：{@code option_value_nos} 是 JSON 数组，
     * 字符串替换会在「一个编号是另一个的前缀」时改错（{@code SV_X_C1} 与 {@code SV_X_C12}）——
     * 而那种错不会报，只会让一批规格悄悄指向别的档。
     */
    private int rewriteSnapshots(List<String> from, String into) {
        java.util.Set<String> olds = java.util.Set.copyOf(from);
        List<PrdSku> rows = DataScopeContext.executeWithoutScope(() ->
                skuMapper.selectList(Wrappers.<PrdSku>lambdaQuery()
                        .isNotNull(PrdSku::getOptionValueNos)));
        int n = 0;
        for (PrdSku sku : rows) {
            List<String> nos = readAliases(sku.getOptionValueNos());
            if (nos.stream().noneMatch(olds::contains)) {
                continue;
            }
            List<String> next = nos.stream().map(x -> olds.contains(x) ? into : x).toList();
            sku.setOptionValueNos(writeAliases(next));
            DataScopeContext.executeWithoutScope(() -> skuMapper.updateById(sku));
            n++;
        }
        return n;
    }

    @Override
    @Transactional
    public void saveCategoryBindings(String categoryNo, List<BindingCommand> bindings) {
        if (categoryNo == null || categoryNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<BindingCommand> list = bindings == null ? List.of() : bindings;
        /*
         * **主维度至多一个。**它是「选完类目自动预填哪一组」的判据 ——
         * 两个的话预填哪一个又变回看数据库返回顺序，正是这套模型来消掉的那件事。
         */
        if (list.stream().filter(BindingCommand::primary).count() > 1) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 整份替换：绑定是一组有序的东西，逐条 diff 出增删改没有收益。
         *
         * **真删，不是软删**（purgeByCategory）。软删走 UPDATE deleted=1，而唯一键
         * uk_cat_spec(tenant_no, category_no, dim_no) 不含 deleted —— 下面那圈 INSERT
         * 会撞上刚软删的那几行，报 Duplicate entry。症状是运营改任何一次绑定都 500，
         * 而第一次配置好好的：种子是迁移直接 INSERT 的，从没走过这条路。
         */
        DataScopeContext.executeWithoutScope(() -> catSpecMapper.purgeByCategory(categoryNo));
        DataScopeContext.executeWithoutScope(() -> catValueMapper.purgeByCategory(categoryNo));

        int i = 0;
        for (BindingCommand b : list) {
            i += 10;
            PrdCategorySpec row = new PrdCategorySpec();
            row.setCategoryNo(categoryNo);
            row.setDimNo(b.dimNo());
            row.setUsageType(b.usageType());
            row.setIsPrimary(b.primary());
            row.setRequired(b.required());
            row.setSort(i);
            row.setStatus(PrdSpecDim.ACTIVE);
            DataScopeContext.executeWithoutScope(() -> catSpecMapper.insert(row));

            int j = 0;
            for (String valueNo : b.valueNos() == null ? List.<String>of() : b.valueNos()) {
                j += 10;
                PrdCategorySpecValue cv = new PrdCategorySpecValue();
                cv.setCategoryNo(categoryNo);
                cv.setDimNo(b.dimNo());
                cv.setValueNo(valueNo);
                cv.setLabelOverride(b.labels() == null ? null : b.labels().get(valueNo));
                cv.setSort(j);
                DataScopeContext.executeWithoutScope(() -> catValueMapper.insert(cv));
            }
        }
    }

    // ---------------------------------------------------------------- 商家侧自定义

    @Override
    @Transactional
    public SpecValueVO addMerchantValue(String merchantNo, String dimNo, String label,
                                        java.math.BigDecimal numericValue) {
        PrdSpecDim dim = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectOne(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getDimNo, dimNo)
                        .eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE).last("limit 1")));
        if (dim == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 商家只能往平台维度或自己的维度下加值 —— 别家自建的维度与他无关
        if (PrdSpecDim.MERCHANT.equals(dim.getScope()) && !merchantNo.equals(dim.getEntityNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        String norm = SpecNormalizer.label(label);
        if (norm == null || norm.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **QUANT 维度下必须有数值。**「我这袋 750g」要落成 750 + g，
         * 否则它排不了序也比不了价 —— 在一个专门用来归一的库里又造了一个字符串。
         *
         * <p>端上不必先问一遍数字：多数时候文案里就写着（「750g」「1.5kg」），
         * 这里按维度的基准单位把它抽出来并换算。抽不出来才回一个错，
         * 而那句错要说清该怎么写（见 b-app 的 customValueBad）。
         */
        java.math.BigDecimal num = numericValue;
        if (PrdSpecDim.QUANT.equals(dim.getValueType()) && (num == null || num.signum() <= 0)) {
            num = parseQuantity(norm, dim.getUnit());
            if (num == null) {
                // **不用 BAD_REQUEST**：「请求参数有误」只会让商家以为系统坏了，
                // 而他要做的很具体 —— 把数量写进文案里。见 SPEC_VALUE_NEEDS_QUANTITY
                throw BizException.of(ErrorCode.SPEC_VALUE_NEEDS_QUANTITY);
            }
        }

        /*
         * **撞车就直接给他平台那一档**，不新建。
         *
         * 判据三条任一命中即算同一个：文案相同、别名命中、归一量相同。
         * 不这样做的话「自定义」会变成制造重复值的机器 —— 而重复值正是这套模型要消灭的。
         */
        List<PrdSpecValue> existing = valuesOf(merchantNo, dimNo);
        for (PrdSpecValue v : existing) {
            boolean sameLabel = norm.equals(SpecNormalizer.label(v.getLabel()));
            boolean sameAlias = readAliases(v.getAliases()).stream()
                    .anyMatch(a -> norm.equals(SpecNormalizer.label(a)));
            boolean sameNum = num != null && v.getNumericValue() != null
                    && num.compareTo(v.getNumericValue()) == 0;
            if (sameLabel || sameAlias || sameNum) {
                return toValueVO(v);
            }
        }

        long mine = existing.stream()
                .filter(v -> PrdSpecDim.MERCHANT.equals(v.getScope())).count();
        if (mine >= MERCHANT_VALUE_LIMIT) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        PrdSpecValue row = new PrdSpecValue();
        // 自有值的编号带商家号：与平台值同一维度、同一根轴，但编号不会撞
        row.setValueNo("SV_" + dim.getCode() + "_M" + BizKey.next(BizKey.SPEC_TEMPLATE));
        row.setDimNo(dimNo);
        row.setCode("M" + Math.abs(norm.hashCode() % 100000));
        row.setLabel(norm);
        row.setNumericValue(num);
        row.setNumericUnit(num == null ? null : dim.getUnit());
        row.setScope(PrdSpecDim.MERCHANT);
        row.setEntityNo(merchantNo);
        row.setStatus(PrdSpecDim.ACTIVE);
        // 排在平台值后面：平台那几档是大多数人要选的
        row.setSort(900);
        DataScopeContext.executeWithoutScope(() -> valueMapper.insert(row));
        return toValueVO(row);
    }

    @Override
    public List<SpecTemplateVO.Option> valuesOfDim(String merchantNo, String dimNo) {
        if (dimNo == null || dimNo.isBlank()) {
            return List.of();
        }
        PrdSpecDim dim = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectOne(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getDimNo, dimNo).last("limit 1")));
        // 子集传空 = 不裁剪，拿这个维度的全量（含他自己在该维度下加的那几档）
        return dim == null ? List.of() : optionsOf(merchantNo, dim, List.of());
    }

    @Override
    @Transactional
    public void saveOverrides(String merchantNo, String categoryNo,
                              List<OverrideCommand> dims) {
        if (merchantNo == null || merchantNo.isBlank() || categoryNo == null || categoryNo.isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 整份替换。真删而不是软删 —— 唯一键不含 deleted，软删会挡住重新插入（V195 那个坑）
        DataScopeContext.executeWithoutScope(() -> overrideMapper.purge(merchantNo, categoryNo));
        if (dims == null || dims.isEmpty()) {
            return;   // 清空 = 完全跟平台走，这是个合法状态
        }
        /*
         * 类目给每个维度裁的那份子集 —— 判断「他这一档是加还是减」要靠它：
         * 子集里有而他没勾 = 减；子集里没有而他勾了 = 从平台值池挑来的。
         * 两种都要落行，而**与子集一致的不落** —— 那样运营给类目加了新档位，
         * 没动过手的商家自动获得它。
         */
        Map<String, java.util.Set<String>> subsetCodes = subsetCodesOf(categoryNo);

        /*
         * **提交上来的 dims 是一份完整声明**：「这一类用哪几个规格」。
         * 所以类目绑定里有、而他没提交的，就是他移除掉的 —— 这里补一条 enabled=false。
         *
         * 不这样做的话会出现：他移除了「等级」，接着挪了下别的规格的位置
         * （那次提交里自然没有「等级」），**等级就回来了** ——「没提交」被当成
         * 「跟平台走」。与取值那一层是同一个病，判据同样只能放在后端：
         * 端上手里就没有「平台给了哪几个规格」这份底。
         */
        java.util.Set<String> declaredDims = dims.stream()
                .filter(OverrideCommand::enabled)
                .map(OverrideCommand::dimNo)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        for (PrdCategorySpec b : activeBindings(categoryNo)) {
            if (declaredDims.contains(b.getDimNo())
                    || dims.stream().anyMatch(d -> d.dimNo().equals(b.getDimNo()))) {
                continue;   // 声明用它、或显式说了不用 —— 两种都在下面那圈里处理
            }
            PrdMerchantSpecOverride off = new PrdMerchantSpecOverride();
            off.setMerchantNo(merchantNo);
            off.setCategoryNo(categoryNo);
            off.setDimNo(b.getDimNo());
            off.setValueNo(PrdMerchantSpecOverride.DIM_LEVEL);
            off.setEnabled(false);
            DataScopeContext.executeWithoutScope(() -> overrideMapper.insert(off));
        }

        int i = 0;
        for (OverrideCommand d : dims) {
            i += 10;
            /*
             * **只写与平台不同的那些。**顺序永远写（它整体是一份排列，
             * 少写一条就乱），但「启用且没改名」的维度不落行 ——
             * 那样运营新加的维度才能自动到达没动过手的商家。
             */
            {
                PrdMerchantSpecOverride row = new PrdMerchantSpecOverride();
                row.setMerchantNo(merchantNo);
                row.setCategoryNo(categoryNo);
                row.setDimNo(d.dimNo());
                row.setValueNo(PrdMerchantSpecOverride.DIM_LEVEL);
                row.setEnabled(d.enabled());
                row.setSort(i);
                /*
                 * **与平台原名相同就不算改名。**判据放在这里而不是端上：
                 * 端上手里的 name 已经是合并后的（他改过就是他的叫法），
                 * 要判断「改没改」得另外拿一份平台原名 —— 而那个值只在
                 * 「刚打开编辑」「刚加进来」这两条路上才有，其余情况一律为空，
                 * 于是每个规格都被当成改过，落一堆等于原名的 override。
                 *
                 * 后果不是表变大：运营以后改了规格名，这些商家**永远收不到** ——
                 * 因为他们"覆盖"了一个自己从没动过的东西。
                 */
                String label = notBlank(d.label()) ? d.label().trim() : null;
                PrdSpecDim dim = DataScopeContext.executeWithoutScope(() ->
                        dimMapper.selectOne(Wrappers.<PrdSpecDim>lambdaQuery()
                                .eq(PrdSpecDim::getDimNo, d.dimNo()).last("limit 1")));
                row.setLabelOverride(dim != null && dim.getName().equals(label) ? null : label);
                DataScopeContext.executeWithoutScope(() -> overrideMapper.insert(row));
            }
            /*
             * **提交上来的 values 是一份完整声明**：「这个规格在本店用哪几档」。
             * 所以类目子集里有、而他没提交的，就是他去掉的 —— 这里补一条 enabled=false。
             *
             * 从前要端上显式提交 enabled=false 才算数，于是出现过这样的 bug：
             * 他在 A 规格里删了一档，接着挪了下 B 规格的位置（那次提交里 A 只带了
             * 「在用的」），删掉的那档就悄悄回来了 —— 因为「没提交」被当成了「跟平台走」。
             * 判据放在后端，端上只说「我用哪几档」，说不漏。
             */
            java.util.Set<String> inSubset = subsetCodes.getOrDefault(d.dimNo(), java.util.Set.of());
            List<ValueOverrideCommand> vals = d.values() == null
                    ? List.<ValueOverrideCommand>of() : d.values();
            java.util.Set<String> declared = vals.stream()
                    .filter(ValueOverrideCommand::enabled)
                    .map(ValueOverrideCommand::code)
                    .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
            if (!vals.isEmpty()) {
                for (String code : inSubset) {
                    if (!declared.contains(code)) {
                        PrdMerchantSpecOverride off = new PrdMerchantSpecOverride();
                        off.setMerchantNo(merchantNo);
                        off.setCategoryNo(categoryNo);
                        off.setDimNo(d.dimNo());
                        off.setValueNo(code);
                        off.setEnabled(false);
                        DataScopeContext.executeWithoutScope(() -> overrideMapper.insert(off));
                    }
                }
            }
            /*
             * **在用的档位逐条落行，带 sort。**顺序是一份完整排列 ——
             * 只落「与默认不同的」那几条，剩下的读回来仍按平台顺序，
             * 他拖过的次序就丢了（保存后一切照旧，像是没生效）。
             *
             * 「稀疏」在这一层由**读侧**保证：没有覆盖行的档位（运营后加的）
             * 排在他排过的那些后面，而不是消失。
             */
            int vi = 0;
            for (ValueOverrideCommand v : vals) {
                if (!v.enabled()) {
                    continue;   // 去掉的上面已经按「没声明」补过了，别落两行
                }
                vi += 10;
                PrdMerchantSpecOverride row = new PrdMerchantSpecOverride();
                row.setMerchantNo(merchantNo);
                row.setCategoryNo(categoryNo);
                row.setDimNo(d.dimNo());
                row.setValueNo(v.code());
                row.setEnabled(v.enabled());
                row.setSort(vi);
                DataScopeContext.executeWithoutScope(() -> overrideMapper.insert(row));
            }
        }
    }

    /**
     * 类目给每个维度裁的子集，按 <b>code</b> 索引（覆盖表用的就是 code）。
     * 某个维度没有子集行 = 不裁剪，返回空集合，调用方按「全都默认开」处理。
     */
    private Map<String, java.util.Set<String>> subsetCodesOf(String categoryNo) {
        Map<String, List<PrdCategorySpecValue>> subsets = subsetsOf(categoryNo);
        if (subsets.isEmpty()) {
            return Map.of();
        }
        // valueNo → code 要查值表：子集存的是 valueNo，而端上回传的是 code
        Map<String, String> codeOf = DataScopeContext.executeWithoutScope(() ->
                        valueMapper.selectList(Wrappers.<PrdSpecValue>lambdaQuery()
                                .in(PrdSpecValue::getValueNo, subsets.values().stream()
                                        .flatMap(List::stream)
                                        .map(PrdCategorySpecValue::getValueNo).toList())))
                .stream().collect(Collectors.toMap(PrdSpecValue::getValueNo, PrdSpecValue::getCode,
                        (a, b) -> a));
        Map<String, java.util.Set<String>> out = new LinkedHashMap<>();
        subsets.forEach((dimNo, rows) -> out.put(dimNo, rows.stream()
                .map(r -> codeOf.get(r.getValueNo()))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new))));
        return out;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Override
    public List<StoreCategorySpecVO> dimsByStore(String merchantNo, String storeNo) {
        if (storeNo == null || storeNo.isBlank()) {
            return List.of();
        }
        List<StoreCategorySpecVO> out = new ArrayList<>();
        for (var shelf : storeCategoryPort.shelvesOf(storeNo)) {
            /*
             * 店主改过名的用店主的叫法：这一页是给他看的，而他记得的是自己起的名字
             * （「好菜」而不是「蔬菜」）。categoryNo 不变，所以聚合与比价不受影响。
             */
            String name = shelf.displayName() != null && !shelf.displayName().isBlank()
                    ? shelf.displayName() : categoryNameOf(shelf.categoryNo());
            // 没配规格的类目也留在列表里（dims 空）—— 那是运营侧的缺口，看得见才问得出来
            out.add(new StoreCategorySpecVO(shelf.categoryNo(), name,
                    templatesForCategory(merchantNo, shelf.categoryNo())));
        }
        return out;
    }

    /** 平台类目名。树是缓存友好的读，一次遍历比为一个名字多开一条 mapper 路径干净 */
    private String categoryNameOf(String categoryNo) {
        for (CategoryVO lv1 : categoryService.tree()) {
            if (lv1.categoryNo().equals(categoryNo)) {
                return lv1.name();
            }
            for (CategoryVO lv2 : lv1.children()) {
                if (lv2.categoryNo().equals(categoryNo)) {
                    return lv2.name();
                }
            }
        }
        return categoryNo;   // 归档/已删的类目：回落成编号，总比显示空白强
    }

    @Override
    public List<MerchantDimVO> myDims(String merchantNo) {
        List<PrdSpecDim> mine = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectList(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getScope, PrdSpecDim.MERCHANT)
                        .eq(PrdSpecDim::getEntityNo, merchantNo)
                        .orderByAsc(PrdSpecDim::getId)));
        if (mine.isEmpty()) {
            return List.of();
        }
        int active = (int) mine.stream().filter(d -> PrdSpecDim.ACTIVE.equals(d.getStatus())).count();
        Map<String, Integer> used = usageByDimName(merchantNo);

        List<MerchantDimVO> out = new ArrayList<>();
        for (PrdSpecDim d : mine) {
            List<SpecValueVO> vs = valuesOf(merchantNo, d.getDimNo()).stream()
                    .map(SpecLibraryServiceImpl::toValueVO).toList();
            out.add(new MerchantDimVO(d.getDimNo(), d.getName(), vs.size(),
                    used.getOrDefault(d.getName(), 0), d.getStatus(),
                    active, MERCHANT_DIM_LIMIT, MERCHANT_VALUE_LIMIT, vs));
        }
        return out;
    }

    /**
     * 「这个维度用在几件商品上」，**按规格组名统计**。
     *
     * <p>为什么不用 templateNo（= dimNo）：那个字段是后来才加的，
     * <b>存量商品的 spec_groups 里只有 name 与 options</b>
     * （线上真实数据长这样：{@code [{"name":"规格","options":["10斤装","20斤装"]}]}）。
     * 按 templateNo 统计的话，老商品一件都算不进来，而那正是商家最在意的那批 ——
     * 「我停用它会影响什么」问的就是历史。
     *
     * <p>代价是改名之后对不上：改完「辣度」→「辣味」，老商品仍记着「辣度」。
     * 这与商品存快照的语义一致（历史不该被改名波及），所以是可接受的不精确。
     */
    private Map<String, Integer> usageByDimName(String merchantNo) {
        Map<String, Integer> out = new java.util.HashMap<>();
        List<ai.neargo.shop.product.entity.PrdGoods> goods = DataScopeContext.executeWithoutScope(() ->
                goodsMapper.selectList(Wrappers.<ai.neargo.shop.product.entity.PrdGoods>lambdaQuery()
                        .eq(ai.neargo.shop.product.entity.PrdGoods::getEntityNo, merchantNo)
                        .isNotNull(ai.neargo.shop.product.entity.PrdGoods::getSpecGroups)));
        for (var g : goods) {
            String raw = g.getSpecGroups();
            if (raw == null || raw.length() < 4) {
                continue;
            }
            /*
             * 只找 "name":"X" 这一段，不整份反序列化：这里要的是计数，
             * 而 spec_groups 的历史形状不止一种（早期没有 optionCodes、更早没有 templateNo）。
             * 反序列化会因为某一件老商品的字段对不上而整个抛掉，
             * 那时页面上所有维度的用量都变成 0 —— 一个看起来「就是没人用」的假象。
             */
            java.util.regex.Matcher m = SPEC_GROUP_NAME.matcher(raw);
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            while (m.find()) {
                names.add(m.group(1));
            }
            for (String n : names) {
                out.merge(n, 1, Integer::sum);
            }
        }
        return out;
    }

    private static final java.util.regex.Pattern SPEC_GROUP_NAME =
            java.util.regex.Pattern.compile("\"name\"\\s*:\\s*\"([^\"]+)\"");

    @Override
    @Transactional
    public SpecDimVO renameMerchantDim(String merchantNo, String dimNo, String name) {
        PrdSpecDim dim = requireMine(merchantNo, dimNo);
        String norm = SpecNormalizer.label(name);
        if (norm == null || norm.isBlank() || BANNED_DIM_NAMES.contains(norm)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 改成与平台维度同名时**不给改**，而不是默默改掉：那样他会以为自己的
         * 「口味」从此与平台的「口味」是一回事（能跨店聚合），其实不是 ——
         * 自建维度换个名字仍旧是自建维度。想用平台那个，该在建品页里挑它。
         */
        boolean clash = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectCount(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getScope, PrdSpecDim.PLATFORM)
                        .eq(PrdSpecDim::getName, norm)
                        .eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE))) > 0;
        if (clash) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        dim.setName(norm);
        DataScopeContext.executeWithoutScope(() -> dimMapper.updateById(dim));
        return toDimVO(dim, List.of(), 0);
    }

    @Override
    @Transactional
    public SpecDimVO archiveMerchantDim(String merchantNo, String dimNo, boolean archived) {
        PrdSpecDim dim = requireMine(merchantNo, dimNo);
        dim.setStatus(archived ? PrdSpecDim.ARCHIVED : PrdSpecDim.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> dimMapper.updateById(dim));
        return toDimVO(dim, List.of(), 0);
    }

    /** 只能动自己建的：平台维度与别家自建的都不是他的东西 */
    private PrdSpecDim requireMine(String merchantNo, String dimNo) {
        PrdSpecDim dim = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectOne(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getDimNo, dimNo).last("limit 1")));
        if (dim == null || !PrdSpecDim.MERCHANT.equals(dim.getScope())
                || !merchantNo.equals(dim.getEntityNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return dim;
    }

    @Override
    @Transactional
    public SpecDimVO addMerchantDim(String merchantNo, String name, List<String> labels) {
        String norm = SpecNormalizer.label(name);
        if (norm == null || norm.isBlank() || BANNED_DIM_NAMES.contains(norm)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        Long mine = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectCount(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getScope, PrdSpecDim.MERCHANT)
                        .eq(PrdSpecDim::getEntityNo, merchantNo)
                        .eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE)));
        if (mine != null && mine >= MERCHANT_DIM_LIMIT) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 与平台维度重名时**直接用平台那个**：他想要的是「按这个维度分规格」，
         * 而不是「拥有一个自己的颜色维度」——后者只会让他的货从聚合里掉出去。
         */
        PrdSpecDim platform = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectOne(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getScope, PrdSpecDim.PLATFORM)
                        .eq(PrdSpecDim::getName, norm)
                        .eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE).last("limit 1")));
        if (platform != null) {
            return toDimVO(platform, List.of(), 0);
        }
        /*
         * **与自己已建的重名时也直接复用**，不再造一个。
         *
         * <p>实测踩到：商家点两次「自定义规格」、两次都输「辣度」，库里就有两个同名维度，
         * 而建品页的规格推荐里会并排出现两个「辣度」—— 他分不出该选哪个，
         * 选错了的那批货又与另一个维度对不上。与上面那条「与平台重名用平台的」同一个道理：
         * 他要的是「按这个维度分规格」，不是「再拥有一个」。
         */
        PrdSpecDim self = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectOne(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getScope, PrdSpecDim.MERCHANT)
                        .eq(PrdSpecDim::getEntityNo, merchantNo)
                        .eq(PrdSpecDim::getName, norm)
                        .eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE).last("limit 1")));
        if (self != null) {
            return toDimVO(self, valuesOf(merchantNo, self.getDimNo()).stream()
                    .map(SpecLibraryServiceImpl::toValueVO).toList(), 0);
        }

        PrdSpecDim dim = new PrdSpecDim();
        String suffix = BizKey.next(BizKey.SPEC_TEMPLATE);
        dim.setDimNo("SD_M_" + suffix);
        dim.setCode("M" + suffix);
        dim.setName(norm);
        // 自建维度一律枚举：让商家在建品页里同时定义「这是个量纲」与它的单位，
        // 是把一个建模问题推给了正在录商品的人
        dim.setValueType(PrdSpecDim.ENUM);
        dim.setUsageType(PrdSpecDim.SALE);
        dim.setUniversal(false);
        dim.setScope(PrdSpecDim.MERCHANT);
        dim.setEntityNo(merchantNo);
        dim.setSort(900);
        dim.setStatus(PrdSpecDim.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> dimMapper.insert(dim));

        List<SpecValueVO> vs = new ArrayList<>();
        int i = 0;
        for (String label : labels == null ? List.<String>of() : labels) {
            String vl = SpecNormalizer.label(label);
            if (vl == null || vl.isBlank()) {
                continue;
            }
            i += 10;
            PrdSpecValue v = new PrdSpecValue();
            v.setValueNo("SV_M" + suffix + "_" + i);
            v.setDimNo(dim.getDimNo());
            v.setCode("M" + i);
            v.setLabel(vl);
            v.setScope(PrdSpecDim.MERCHANT);
            v.setEntityNo(merchantNo);
            v.setSort(i);
            v.setStatus(PrdSpecDim.ACTIVE);
            DataScopeContext.executeWithoutScope(() -> valueMapper.insert(v));
            vs.add(toValueVO(v));
        }
        return toDimVO(dim, vs, 0);
    }

    /**
     * 从文案里抽出归一后的数量：「750g」→ 750、「1.5kg」→ 1500（基准单位 g）。
     *
     * <p>只认<b>维度自己那一族</b>的单位：重量维度不该把「750ml」认成 750 克。
     * 认不出来返回 null —— 由调用方决定是报错还是留空，这里不猜。
     */
    static java.math.BigDecimal parseQuantity(String label, String baseUnit) {
        if (label == null || baseUnit == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)\\s*([a-zA-Z\u4e00-\u9fa5]*)")
                .matcher(label);
        if (!m.find()) {
            return null;
        }
        java.math.BigDecimal n = new java.math.BigDecimal(m.group(1));
        String unit = m.group(2) == null ? "" : m.group(2).toLowerCase();
        // 倍率表按基准单位分族：进错族的单位一律不认
        java.util.Map<String, java.util.Map<String, Integer>> family = java.util.Map.of(
                "g", java.util.Map.of("g", 1, "克", 1, "kg", 1000, "千克", 1000, "公斤", 1000,
                        "斤", 500, "两", 50, "", 1),
                "ml", java.util.Map.of("ml", 1, "毫升", 1, "l", 1000, "升", 1000, "", 1),
                "cm", java.util.Map.of("cm", 1, "厘米", 1, "mm", 1, "m", 100, "米", 100, "", 1),
                "m", java.util.Map.of("m", 1, "米", 1, "cm", 1, "", 1),
                "分钟", java.util.Map.of("分钟", 1, "min", 1, "小时", 60, "h", 60, "", 1));
        java.util.Map<String, Integer> mul = family.get(baseUnit);
        if (mul == null) {
            // 件 / 人 / 支这类计数单位：数字本身就是值
            return n;
        }
        Integer k = mul.get(unit);
        if (k == null) {
            return null;
        }
        // 厘米族里 mm 要除以 10，单独一条 —— 放进倍率表就得引入小数倍率
        if ("cm".equals(baseUnit) && "mm".equals(unit)) {
            return n.divide(java.math.BigDecimal.TEN);
        }
        return n.multiply(java.math.BigDecimal.valueOf(k));
    }

    private PrdSpecValue mustValue(String valueNo) {
        PrdSpecValue row = DataScopeContext.executeWithoutScope(() ->
                valueMapper.selectOne(Wrappers.<PrdSpecValue>lambdaQuery()
                        .eq(PrdSpecValue::getValueNo, valueNo).last("limit 1")));
        if (row == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private static SpecDimVO toDimVO(PrdSpecDim d, List<SpecValueVO> values, int inUse) {
        return new SpecDimVO(d.getDimNo(), d.getCode(), d.getName(), d.getValueType(), d.getUnit(),
                d.getUsageType(), Boolean.TRUE.equals(d.getUniversal()), d.getScope(),
                d.getEntityNo(), d.getSort() == null ? 100 : d.getSort(), d.getStatus(),
                values.size(), inUse, values);
    }

    private static SpecValueVO toValueVO(PrdSpecValue v) {
        return new SpecValueVO(v.getValueNo(), v.getDimNo(), v.getCode(), v.getLabel(),
                v.getNumericValue(), v.getNumericUnit(), readAliases(v.getAliases()),
                v.getScope(), v.getEntityNo(), v.getSort() == null ? 100 : v.getSort(),
                v.getStatus(), 0);
    }

    /**
     * 往「文案 → 值编号」表里塞一个键，键一律取 {@link SpecNormalizer#label} 规范化后的形式。
     *
     * <p><b>先到先得</b>：调用方分三轮灌 —— 正式标签与码、类目级换名、别名。
     * 于是别名撞上任何一个值的正式说法时都是正式说法赢，与值的排序无关；
     * 别名之间互撞则先出现的赢（同一个说法指向两个值，本来就得靠运营去重）。
     */
    private static void putValueKey(Map<String, String> byLabel, String raw, String valueNo) {
        String norm = SpecNormalizer.label(raw);
        if (norm != null && !norm.isBlank()) {
            byLabel.putIfAbsent(norm, valueNo);
        }
    }

    private static List<String> readAliases(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(json, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() { });
        } catch (Exception e) {
            // 脏 JSON 不该让整张规格库打不开：这一条没有别名，别的照常
            return List.of();
        }
    }

    private static String writeAliases(List<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .writeValueAsString(aliases.stream().map(SpecNormalizer::label)
                            .filter(x -> x != null && !x.isBlank()).distinct().toList());
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- helpers

    private List<PrdCategorySpec> activeBindings(String categoryNo) {
        return DataScopeContext.executeWithoutScope(() ->
                        catSpecMapper.selectList(Wrappers.<PrdCategorySpec>lambdaQuery()
                                .eq(PrdCategorySpec::getCategoryNo, categoryNo)
                                .eq(PrdCategorySpec::getStatus, PrdSpecDim.ACTIVE)))
                .stream()
                /*
                 * **主维度排第一**，其余按 sort。此前「自动预填哪一条」取决于数据库返回顺序，
                 * 也就是插入顺序 —— 一个不该被依赖的巧合。现在它是一个显式的值。
                 */
                .sorted(Comparator.comparing((PrdCategorySpec b) ->
                                Boolean.TRUE.equals(b.getIsPrimary()) ? 0 : 1)
                        .thenComparingInt(b -> b.getSort() == null ? 100 : b.getSort()))
                .toList();
    }

    private Map<String, PrdSpecDim> dimsOf(List<String> dimNos) {
        if (dimNos.isEmpty()) {
            return Map.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        dimMapper.selectList(Wrappers.<PrdSpecDim>lambdaQuery()
                                .in(PrdSpecDim::getDimNo, Set.copyOf(dimNos))
                                .eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE)))
                .stream().collect(Collectors.toMap(PrdSpecDim::getDimNo, d -> d, (a, b) -> a));
    }

    /** 这一类目对每个维度裁的取值子集。没有行 = 不裁，全量可选 */
    private Map<String, List<PrdCategorySpecValue>> subsetsOf(String categoryNo) {
        return DataScopeContext.executeWithoutScope(() ->
                        catValueMapper.selectList(Wrappers.<PrdCategorySpecValue>lambdaQuery()
                                .eq(PrdCategorySpecValue::getCategoryNo, categoryNo)))
                .stream()
                .sorted(Comparator.comparingInt(v -> v.getSort() == null ? 100 : v.getSort()))
                .collect(Collectors.groupingBy(PrdCategorySpecValue::getDimNo,
                        LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * 维度下这家店能看到的值：平台的 + 他自己在这个维度下加的。
     *
     * <p>商家自建值挂在<b>平台维度</b>上，所以它与平台值天然同轴 —— 这正是
     * 「复制一整份模板」做不到的事：那样加出来的值与平台的「重量」毫无关系。
     */
    private List<PrdSpecValue> valuesOf(String merchantNo, String dimNo) {
        return DataScopeContext.executeWithoutScope(() ->
                valueMapper.selectList(Wrappers.<PrdSpecValue>lambdaQuery()
                        .eq(PrdSpecValue::getDimNo, dimNo)
                        .eq(PrdSpecValue::getStatus, PrdSpecDim.ACTIVE)
                        .and(q -> q.eq(PrdSpecValue::getScope, PrdSpecDim.PLATFORM)
                                .or(o -> o.eq(PrdSpecValue::getScope, PrdSpecDim.MERCHANT)
                                        .eq(PrdSpecValue::getEntityNo, merchantNo)))
                        .orderByAsc(PrdSpecValue::getSort)));
    }

    private List<SpecTemplateVO.Option> optionsOf(String merchantNo, PrdSpecDim dim,
                                                  List<PrdCategorySpecValue> subset) {
        List<PrdSpecValue> values = valuesOf(merchantNo, dim.getDimNo());
        if (subset.isEmpty()) {
            return values.stream()
                    .map(v -> new SpecTemplateVO.Option(v.getCode(), v.getLabel()))
                    .toList();
        }
        Map<String, PrdSpecValue> byNo = values.stream()
                .collect(Collectors.toMap(PrdSpecValue::getValueNo, v -> v, (a, b) -> a));
        List<SpecTemplateVO.Option> out = new ArrayList<>();
        for (PrdCategorySpecValue cv : subset) {
            PrdSpecValue v = byNo.get(cv.getValueNo());
            if (v == null) {
                continue;
            }
            // 类目内换名优先：同一个 500g，在蔬菜下叫「约1斤」，而码与归一量都不变
            String label = cv.getLabelOverride() != null && !cv.getLabelOverride().isBlank()
                    ? cv.getLabelOverride() : v.getLabel();
            out.add(new SpecTemplateVO.Option(v.getCode(), label));
        }
        /*
         * 商家在这个维度下自建的值**跟在子集后面**：类目裁剪的是平台值池，
         * 裁不到他自己加的那几档（那是他的货真实存在的规格，不该被平台的子集挡住）。
         */
        Set<String> picked = subset.stream().map(PrdCategorySpecValue::getValueNo)
                .collect(Collectors.toSet());
        for (PrdSpecValue v : values) {
            if (PrdSpecDim.MERCHANT.equals(v.getScope()) && !picked.contains(v.getValueNo())) {
                out.add(new SpecTemplateVO.Option(v.getCode(), v.getLabel()));
            }
        }
        return out;
    }

    private List<CategorySpecVO.ValueVO> valueVOs(PrdSpecDim dim, List<PrdCategorySpecValue> subset) {
        // 运营总览看的是平台值池，不掺任何一家商家的自建值
        List<PrdSpecValue> values = DataScopeContext.executeWithoutScope(() ->
                valueMapper.selectList(Wrappers.<PrdSpecValue>lambdaQuery()
                        .eq(PrdSpecValue::getDimNo, dim.getDimNo())
                        .eq(PrdSpecValue::getScope, PrdSpecDim.PLATFORM)
                        .eq(PrdSpecValue::getStatus, PrdSpecDim.ACTIVE)
                        .orderByAsc(PrdSpecValue::getSort)));
        if (subset.isEmpty()) {
            return values.stream()
                    .map(v -> new CategorySpecVO.ValueVO(v.getValueNo(), v.getCode(), v.getLabel(),
                            v.getNumericValue(), v.getNumericUnit()))
                    .toList();
        }
        Map<String, PrdSpecValue> byNo = values.stream()
                .collect(Collectors.toMap(PrdSpecValue::getValueNo, v -> v, (a, b) -> a));
        List<CategorySpecVO.ValueVO> out = new ArrayList<>();
        for (PrdCategorySpecValue cv : subset) {
            PrdSpecValue v = byNo.get(cv.getValueNo());
            if (v == null) {
                continue;
            }
            String label = cv.getLabelOverride() != null && !cv.getLabelOverride().isBlank()
                    ? cv.getLabelOverride() : v.getLabel();
            out.add(new CategorySpecVO.ValueVO(v.getValueNo(), v.getCode(), label,
                    v.getNumericValue(), v.getNumericUnit()));
        }
        return out;
    }
}
