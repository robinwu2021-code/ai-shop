package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.product.dto.CategorySpecVO;
import ai.neargo.shop.product.dto.CategoryVO;
import ai.neargo.shop.product.dto.SpecTemplateVO;
import ai.neargo.shop.product.entity.PrdCategorySpec;
import ai.neargo.shop.product.entity.PrdCategorySpecValue;
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

    private final SpecDimMapper dimMapper;
    private final SpecValueMapper valueMapper;
    private final CategorySpecMapper catSpecMapper;
    private final CategorySpecValueMapper catValueMapper;
    private final CategoryService categoryService;
    /** 合并值时要改写 SKU 快照 —— 那是「跨店可比」真正的落点 */
    private final SkuMapper skuMapper;

    public SpecLibraryServiceImpl(SpecDimMapper dimMapper, SpecValueMapper valueMapper,
                                  CategorySpecMapper catSpecMapper,
                                  CategorySpecValueMapper catValueMapper,
                                  CategoryService categoryService,
                                  SkuMapper skuMapper) {
        this.skuMapper = skuMapper;
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
        // 整份替换：绑定是一组有序的东西，逐条 diff 出增删改没有收益
        DataScopeContext.executeWithoutScope(() -> catSpecMapper.delete(
                Wrappers.<PrdCategorySpec>lambdaQuery().eq(PrdCategorySpec::getCategoryNo, categoryNo)));
        DataScopeContext.executeWithoutScope(() -> catValueMapper.delete(
                Wrappers.<PrdCategorySpecValue>lambdaQuery().eq(PrdCategorySpecValue::getCategoryNo, categoryNo)));

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
                throw BizException.of(ErrorCode.BAD_REQUEST);
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
