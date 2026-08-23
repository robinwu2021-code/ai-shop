package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.dto.CategorySpecVO;
import ai.neargo.shop.product.dto.CategoryVO;
import ai.neargo.shop.product.dto.SpecTemplateVO;
import ai.neargo.shop.product.entity.PrdCategorySpec;
import ai.neargo.shop.product.entity.PrdCategorySpecValue;
import ai.neargo.shop.product.entity.PrdSpecDim;
import ai.neargo.shop.product.entity.PrdSpecValue;
import ai.neargo.shop.product.mapper.ProductMappers.CategorySpecMapper;
import ai.neargo.shop.product.mapper.ProductMappers.CategorySpecValueMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecDimMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecValueMapper;
import ai.neargo.shop.product.service.CategoryService;
import ai.neargo.shop.product.service.SpecLibraryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;

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

    private final SpecDimMapper dimMapper;
    private final SpecValueMapper valueMapper;
    private final CategorySpecMapper catSpecMapper;
    private final CategorySpecValueMapper catValueMapper;
    private final CategoryService categoryService;

    public SpecLibraryServiceImpl(SpecDimMapper dimMapper, SpecValueMapper valueMapper,
                                  CategorySpecMapper catSpecMapper,
                                  CategorySpecValueMapper catValueMapper,
                                  CategoryService categoryService) {
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
            List<SpecTemplateVO.Option> options = optionsOf(merchantNo, dim,
                    subsets.getOrDefault(b.getDimNo(), List.of()));
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
                    dim.getName(), options, null));
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
            byLabel.putIfAbsent(v.getLabel(), v.getValueNo());
            // 码也认：端上有时回传的是 code（走模板那条路），认两种比认一种稳
            byLabel.putIfAbsent(v.getCode(), v.getValueNo());
        }
        /*
         * 类目级的换名也要认得回来：500g 在蔬菜下显示成「约1斤」，商家提交的就是「约1斤」。
         * 不认的话这一格永远归不了一 —— 而它恰恰是生鲜类目下最常用的那几档。
         */
        for (PrdCategorySpecValue cv : DataScopeContext.executeWithoutScope(() ->
                catValueMapper.selectList(Wrappers.<PrdCategorySpecValue>lambdaQuery()
                        .eq(PrdCategorySpecValue::getDimNo, dimNo)
                        .isNotNull(PrdCategorySpecValue::getLabelOverride)))) {
            byLabel.putIfAbsent(cv.getLabelOverride(), cv.getValueNo());
        }

        Map<String, String> out = new LinkedHashMap<>();
        for (String label : labels) {
            String no = label == null ? null : byLabel.get(label.trim());
            if (no != null) {
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
