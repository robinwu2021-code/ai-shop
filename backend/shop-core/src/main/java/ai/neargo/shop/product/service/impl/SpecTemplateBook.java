package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.common.SpecNormalizer;
import ai.neargo.shop.product.dto.OpsSpecTemplateVO;
import ai.neargo.shop.product.dto.SpecTemplateVO;
import ai.neargo.shop.product.entity.PrdSpecDim;
import ai.neargo.shop.product.entity.PrdSpecUsage;
import ai.neargo.shop.product.entity.PrdSpecValue;
import ai.neargo.shop.product.mapper.ProductMappers.SpecDimMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecUsageMapper;
import ai.neargo.shop.product.mapper.ProductMappers.SpecValueMapper;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.product.service.SpecLibraryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 规格模板 = 规格库里的维度 + 推荐范围（TDD-商品领域迁到门店交易库 批次 2-e）。
 *
 * <p>旧库的 {@code prd_spec_template} 是规格库（V195）之前的做法：每条模板自带一份选项 JSON。
 * 规格库上线后同样的东西有了身份（维度、取值、归一量），模板只剩两件事还在用，都并进
 * {@code prd_spec_usage}，<b>不再新建一根平行的轴</b>：
 * <ul>
 *   <li><b>平台模板 → 品类推荐</b>（{@link PrdSpecUsage#CATEGORY_TYPE}）：还没选类目时按品类推荐的几组规格。
 *       维度级行指向规格库的维度，取值级行是推荐的那几档，叫法不同记在 {@code label_override}
 *       （生鲜的「约1斤」就是规格库的 500g）。运营按名字建模板：规格库已有同名平台维度就用它，
 *       没有就在规格库里建一个；选项按 code 找取值，没有就建成平台取值。</li>
 *   <li><b>商家自存模板 → 商家常用</b>（{@link PrdSpecUsage#MERCHANT}，{@code favorite=1}）：
 *       存成他的自建维度（与「我的规格」同一条路），再记一条常用。</li>
 * </ul>
 *
 * <p><b>编号</b>：运营端的模板号是「品类-维度号」（同一维度可推荐给多个品类，各自归档）；
 * 下发给商家的 {@code templateNo} 是维度号 —— 与规格库下发的一致，商品保存时据它反查取值编号。
 */
@Component
public class SpecTemplateBook {

    private final SpecUsageMapper usageMapper;
    private final SpecDimMapper dimMapper;
    private final SpecValueMapper valueMapper;
    private final SpecLibraryService library;

    public SpecTemplateBook(SpecUsageMapper usageMapper, SpecDimMapper dimMapper,
                            SpecValueMapper valueMapper, SpecLibraryService library) {
        this.usageMapper = usageMapper;
        this.dimMapper = dimMapper;
        this.valueMapper = valueMapper;
        this.library = library;
    }

    // ---------------------------------------------------------------- 运营端：品类推荐

    public PageData<OpsSpecTemplateVO> list(String categoryType, String keyword,
                                            boolean showArchived, long page, long size) {
        var w = Wrappers.<PrdSpecUsage>lambdaQuery()
                .eq(PrdSpecUsage::getScopeType, PrdSpecUsage.CATEGORY_TYPE)
                .eq(PrdSpecUsage::getValueNo, PrdSpecUsage.DIM_LEVEL)
                .eq(notBlank(categoryType), PrdSpecUsage::getScopeRef, categoryType)
                .eq(!showArchived, PrdSpecUsage::getStatus, PrdSpecUsage.ACTIVE)
                .orderByDesc(PrdSpecUsage::getId);
        List<PrdSpecUsage> heads = DataScopeContext.executeWithoutScope(() -> usageMapper.selectList(w));
        Map<String, PrdSpecDim> dims = dimsOf(heads.stream().map(PrdSpecUsage::getDimNo).toList());
        List<OpsSpecTemplateVO> all = new ArrayList<>();
        for (PrdSpecUsage h : heads) {
            PrdSpecDim d = dims.get(h.getDimNo());
            if (d == null) {
                continue;
            }
            // 名字是一张表一张表拼出来的，关键词只能在内存里筛；推荐总共几十行，筛得起
            if (notBlank(keyword) && !nameOf(h, d).contains(keyword.trim())) {
                continue;
            }
            all.add(toOpsVO(h, d));
        }
        long p = Math.max(page, 1);
        List<OpsSpecTemplateVO> slice = all.stream().skip((p - 1) * size).limit(size).toList();
        return PageData.of(slice, all.size(), page, size);
    }

    public OpsSpecTemplateVO save(String templateNo, String categoryType, String name,
                                  List<MerchantGoodsService.SpecOption> options) {
        if (name == null || name.isBlank() || options == null || options.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **每个选项都必须带 code** —— 平台推荐存在的唯一理由（B-4.5）：
         * 自由文本下三家店会把同一件事写成「5 斤」「五斤」「2.5kg」，聚合、比价、搜索全部对不上。
         */
        Set<String> codes = new LinkedHashSet<>();
        for (MerchantGoodsService.SpecOption o : options) {
            if (o == null || o.code() == null || o.code().isBlank()
                    || o.label() == null || o.label().isBlank()) {
                throw BizException.of(ErrorCode.SPEC_TEMPLATE_CODE_REQUIRED);
            }
            // 组内 code 重复会让「500g」和「1kg」在聚合时并成同一个规格
            if (!codes.add(o.code().trim())) {
                throw BizException.of(ErrorCode.SPEC_TEMPLATE_DUPLICATE);
            }
        }
        PrdSpecUsage existing = notBlank(templateNo) ? head(templateNo) : null;
        String scopeRef = notBlank(categoryType) ? categoryType.trim() : PrdSpecUsage.ALL_TYPES;
        PrdSpecDim dim = platformDim(name.trim());

        // 同一品类下同一维度只推荐一次：商家的下拉里出现两个「重量」，选哪个都对不上
        PrdSpecUsage clash = headOf(scopeRef, dim.getDimNo());
        if (clash != null && (existing == null || !clash.getId().equals(existing.getId()))) {
            throw BizException.of(ErrorCode.SPEC_TEMPLATE_DUPLICATE);
        }

        PrdSpecUsage head;
        if (existing != null && existing.getScopeRef().equals(scopeRef)
                && existing.getDimNo().equals(dim.getDimNo())) {
            head = existing;
            DataScopeContext.executeWithoutScope(() -> usageMapper.purgeDimValues(
                    PrdSpecUsage.CATEGORY_TYPE, scopeRef, dim.getDimNo()));
        } else {
            if (existing != null) {
                // 改了品类或名字 = 换了推荐对象：旧的那组整个拿掉，状态沿用
                DataScopeContext.executeWithoutScope(() -> usageMapper.purgeDim(
                        PrdSpecUsage.CATEGORY_TYPE, existing.getScopeRef(), existing.getDimNo()));
            }
            head = new PrdSpecUsage();
            head.setScopeType(PrdSpecUsage.CATEGORY_TYPE);
            head.setScopeRef(scopeRef);
            head.setDimNo(dim.getDimNo());
            head.setValueNo(PrdSpecUsage.DIM_LEVEL);
            head.setStatus(existing == null ? PrdSpecUsage.ACTIVE : existing.getStatus());
            PrdSpecUsage toInsert = head;
            DataScopeContext.executeWithoutScope(() -> usageMapper.insert(toInsert));
        }

        Map<String, PrdSpecValue> byCode = platformValues(dim.getDimNo()).stream()
                .filter(v -> v.getCode() != null)
                .collect(Collectors.toMap(PrdSpecValue::getCode, v -> v, (a, b) -> a));
        int sort = 0;
        for (MerchantGoodsService.SpecOption o : options) {
            String code = o.code().trim();
            String label = o.label().trim();
            PrdSpecValue v = byCode.get(code);
            String valueNo;
            if (v == null) {
                valueNo = library.saveValue(new SpecLibraryService.SaveValueCommand(
                        null, dim.getDimNo(), code, label, null, null, null, null)).valueNo();
            } else {
                valueNo = v.getValueNo();
            }
            sort += 10;
            PrdSpecUsage row = new PrdSpecUsage();
            row.setScopeType(PrdSpecUsage.CATEGORY_TYPE);
            row.setScopeRef(scopeRef);
            row.setDimNo(dim.getDimNo());
            row.setValueNo(valueNo);
            // 叫法与规格库不同才记：生鲜的「约1斤」就是规格库的 500g，码与归一量不变
            row.setLabelOverride(v == null || label.equals(v.getLabel()) ? null : label);
            row.setSort(sort);
            row.setStatus(PrdSpecUsage.ACTIVE);
            DataScopeContext.executeWithoutScope(() -> usageMapper.insert(row));
        }
        return toOpsVO(headOf(scopeRef, dim.getDimNo()), dim);
    }

    public OpsSpecTemplateVO setArchived(String templateNo, boolean archived) {
        PrdSpecUsage h = head(templateNo);
        h.setStatus(archived ? PrdSpecUsage.DISABLED : PrdSpecUsage.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> usageMapper.updateById(h));
        PrdSpecUsage fresh = headOf(h.getScopeRef(), h.getDimNo());
        return toOpsVO(fresh, dimsOf(List.of(h.getDimNo())).get(h.getDimNo()));
    }

    // ---------------------------------------------------------------- 商家端

    /**
     * 品类推荐：这个品类的，加上不限品类的；品类为空给全部。只给启用的推荐、启用的维度。
     */
    public List<SpecTemplateVO> recommended(String categoryType) {
        var w = Wrappers.<PrdSpecUsage>lambdaQuery()
                .eq(PrdSpecUsage::getScopeType, PrdSpecUsage.CATEGORY_TYPE)
                .eq(PrdSpecUsage::getStatus, PrdSpecUsage.ACTIVE);
        if (notBlank(categoryType)) {
            w.in(PrdSpecUsage::getScopeRef, categoryType, PrdSpecUsage.ALL_TYPES);
        }
        List<PrdSpecUsage> rows = DataScopeContext.executeWithoutScope(() -> usageMapper.selectList(w));
        List<PrdSpecUsage> heads = rows.stream().filter(PrdSpecUsage::dimLevel)
                .sorted(Comparator.comparing(PrdSpecUsage::getId)).toList();
        Map<String, PrdSpecDim> dims = dimsOf(heads.stream().map(PrdSpecUsage::getDimNo).toList());
        Map<String, PrdSpecValue> values = valuesByNo(rows.stream()
                .filter(r -> !r.dimLevel()).map(PrdSpecUsage::getValueNo).toList());
        Map<String, SpecTemplateVO> out = new LinkedHashMap<>();
        for (PrdSpecUsage h : heads) {
            PrdSpecDim d = dims.get(h.getDimNo());
            // 同一维度既推荐给本品类又推荐给全部时只给一组：先到的那组（id 小）
            if (d == null || out.containsKey(d.getDimNo())) {
                continue;
            }
            List<SpecTemplateVO.Option> options = rows.stream()
                    .filter(r -> !r.dimLevel() && r.getScopeRef().equals(h.getScopeRef())
                            && r.getDimNo().equals(h.getDimNo()))
                    .sorted(Comparator.comparingInt(r -> r.getSort() == null ? 100 : r.getSort()))
                    .map(r -> option(r, values.get(r.getValueNo())))
                    .filter(java.util.Objects::nonNull)
                    .toList();
            String type = PrdSpecUsage.ALL_TYPES.equals(h.getScopeRef()) ? null : h.getScopeRef();
            out.put(d.getDimNo(), new SpecTemplateVO(d.getDimNo(), PrdSpecDim.PLATFORM, type, null,
                    nameOf(h, d), options, null, false, d.getValueType()));
        }
        return new ArrayList<>(out.values());
    }

    /** 商家存过的常用规格（只有他自己看得到） */
    public List<SpecTemplateVO> favorites(String merchantNo) {
        if (!notBlank(merchantNo)) {
            return List.of();
        }
        List<PrdSpecUsage> rows = DataScopeContext.executeWithoutScope(() ->
                usageMapper.selectList(Wrappers.<PrdSpecUsage>lambdaQuery()
                        .eq(PrdSpecUsage::getScopeType, PrdSpecUsage.MERCHANT)
                        .eq(PrdSpecUsage::getScopeRef, merchantNo)
                        .eq(PrdSpecUsage::getValueNo, PrdSpecUsage.DIM_LEVEL)
                        .eq(PrdSpecUsage::getFavorite, true)
                        .eq(PrdSpecUsage::getStatus, PrdSpecUsage.ACTIVE)
                        .orderByAsc(PrdSpecUsage::getId)));
        Map<String, PrdSpecDim> dims = dimsOf(rows.stream().map(PrdSpecUsage::getDimNo).toList());
        List<SpecTemplateVO> out = new ArrayList<>();
        for (PrdSpecUsage r : rows) {
            PrdSpecDim d = dims.get(r.getDimNo());
            if (d != null) {
                out.add(favoriteVO(merchantNo, d));
            }
        }
        return out;
    }

    /**
     * 存为常用规格：存成他的自建维度（规格库已有同名平台维度就用平台的），再记一条常用。
     * 选项只按文字收：商家手输的没有 code。
     */
    public SpecTemplateVO saveFavorite(String merchantNo, String name,
                                       List<MerchantGoodsService.SpecOption> options) {
        if (name == null || name.isBlank() || options == null || options.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<String> labels = options.stream()
                .map(o -> o == null ? null : o.label())
                .filter(SpecTemplateBook::notBlank)
                .toList();
        SpecLibraryService.SpecDimVO dim = library.addMerchantDim(merchantNo, name, labels, PrdSpecDim.SALE);
        PrdSpecUsage mine = DataScopeContext.executeWithoutScope(() ->
                usageMapper.selectOne(Wrappers.<PrdSpecUsage>lambdaQuery()
                        .eq(PrdSpecUsage::getScopeType, PrdSpecUsage.MERCHANT)
                        .eq(PrdSpecUsage::getScopeRef, merchantNo)
                        .eq(PrdSpecUsage::getDimNo, dim.dimNo())
                        .eq(PrdSpecUsage::getValueNo, PrdSpecUsage.DIM_LEVEL)
                        .last("limit 1")));
        if (mine == null) {
            PrdSpecUsage row = new PrdSpecUsage();
            row.setScopeType(PrdSpecUsage.MERCHANT);
            row.setScopeRef(merchantNo);
            row.setEntityNo(merchantNo);
            row.setDimNo(dim.dimNo());
            row.setValueNo(PrdSpecUsage.DIM_LEVEL);
            row.setFavorite(true);
            row.setStatus(PrdSpecUsage.ACTIVE);
            DataScopeContext.executeWithoutScope(() -> usageMapper.insert(row));
        } else if (!Boolean.TRUE.equals(mine.getFavorite()) || !mine.enabled()) {
            mine.setFavorite(true);
            mine.setStatus(PrdSpecUsage.ACTIVE);
            DataScopeContext.executeWithoutScope(() -> usageMapper.updateById(mine));
        }
        return favoriteVO(merchantNo, dimsOf(List.of(dim.dimNo())).get(dim.dimNo()));
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 运营按名字建推荐：规格库有同名平台维度就用它，没有就建一个。
     * 建的规则与规格库运营入口相同（禁用词、重名），所以「规格」这种万能词在这里同样建不出来。
     */
    private PrdSpecDim platformDim(String name) {
        String norm = SpecNormalizer.label(name);
        PrdSpecDim d = DataScopeContext.executeWithoutScope(() ->
                dimMapper.selectOne(Wrappers.<PrdSpecDim>lambdaQuery()
                        .eq(PrdSpecDim::getScope, PrdSpecDim.PLATFORM)
                        .eq(PrdSpecDim::getName, norm)
                        .last("limit 1")));
        if (d != null) {
            // 已归档的维度商家选不到：推荐给他等于没推荐。要用先去规格库恢复
            if (!PrdSpecDim.ACTIVE.equals(d.getStatus())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            return d;
        }
        String code = "T" + BizKey.next(BizKey.SPEC_TEMPLATE);
        String dimNo = library.saveDim(new SpecLibraryService.SaveDimCommand(
                null, code, norm, PrdSpecDim.ENUM, null, PrdSpecDim.SALE, false, null)).dimNo();
        return dimsOf(List.of(dimNo)).get(dimNo);
    }

    private PrdSpecUsage head(String templateNo) {
        int cut = templateNo.indexOf('-');
        PrdSpecUsage h = cut <= 0 ? null : headOf(templateNo.substring(0, cut), templateNo.substring(cut + 1));
        if (h == null) {
            // 商家的常用规格在这里一律 404：别家存了什么不该被平台端顺手改掉，更不该被探知存在与否
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return h;
    }

    private PrdSpecUsage headOf(String scopeRef, String dimNo) {
        return DataScopeContext.executeWithoutScope(() ->
                usageMapper.selectOne(Wrappers.<PrdSpecUsage>lambdaQuery()
                        .eq(PrdSpecUsage::getScopeType, PrdSpecUsage.CATEGORY_TYPE)
                        .eq(PrdSpecUsage::getScopeRef, scopeRef)
                        .eq(PrdSpecUsage::getDimNo, dimNo)
                        .eq(PrdSpecUsage::getValueNo, PrdSpecUsage.DIM_LEVEL)
                        .last("limit 1")));
    }

    private OpsSpecTemplateVO toOpsVO(PrdSpecUsage h, PrdSpecDim d) {
        List<PrdSpecUsage> rows = DataScopeContext.executeWithoutScope(() ->
                usageMapper.selectList(Wrappers.<PrdSpecUsage>lambdaQuery()
                        .eq(PrdSpecUsage::getScopeType, PrdSpecUsage.CATEGORY_TYPE)
                        .eq(PrdSpecUsage::getScopeRef, h.getScopeRef())
                        .eq(PrdSpecUsage::getDimNo, h.getDimNo())
                        .ne(PrdSpecUsage::getValueNo, PrdSpecUsage.DIM_LEVEL)
                        .orderByAsc(PrdSpecUsage::getSort)));
        Map<String, PrdSpecValue> values = valuesByNo(rows.stream().map(PrdSpecUsage::getValueNo).toList());
        List<OpsSpecTemplateVO.Option> options = rows.stream()
                .map(r -> option(r, values.get(r.getValueNo())))
                .filter(java.util.Objects::nonNull)
                .map(o -> new OpsSpecTemplateVO.Option(o.code(), o.label()))
                .toList();
        boolean archived = PrdSpecUsage.DISABLED.equals(h.getStatus());
        String type = PrdSpecUsage.ALL_TYPES.equals(h.getScopeRef()) ? null : h.getScopeRef();
        return new OpsSpecTemplateVO(h.getScopeRef() + "-" + h.getDimNo(), PrdSpecDim.PLATFORM, type,
                nameOf(h, d), options,
                // 归档时间没有单独一列：DISABLED 的最后一次更新就是它被归档的时刻
                archived ? iso(h.getUpdatedAt()) : null,
                iso(h.getCreatedAt()));
    }

    private SpecTemplateVO favoriteVO(String merchantNo, PrdSpecDim d) {
        return new SpecTemplateVO(d.getDimNo(), PrdSpecDim.MERCHANT, null, null, d.getName(),
                library.valuesOfDim(merchantNo, d.getDimNo()), merchantNo, false, d.getValueType());
    }

    private static SpecTemplateVO.Option option(PrdSpecUsage r, PrdSpecValue v) {
        if (v == null || !PrdSpecDim.ACTIVE.equals(v.getStatus())) {
            return null;
        }
        return new SpecTemplateVO.Option(v.getCode(),
                notBlank(r.getLabelOverride()) ? r.getLabelOverride() : v.getLabel());
    }

    private static String nameOf(PrdSpecUsage h, PrdSpecDim d) {
        return notBlank(h.getLabelOverride()) ? h.getLabelOverride() : d.getName();
    }

    private Map<String, PrdSpecDim> dimsOf(List<String> dimNos) {
        if (dimNos.isEmpty()) {
            return Map.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        dimMapper.selectList(Wrappers.<PrdSpecDim>lambdaQuery()
                                .in(PrdSpecDim::getDimNo, Set.copyOf(dimNos))
                                .eq(PrdSpecDim::getStatus, PrdSpecDim.ACTIVE)))
                .stream().collect(Collectors.toMap(PrdSpecDim::getDimNo, Function.identity(), (a, b) -> a));
    }

    private Map<String, PrdSpecValue> valuesByNo(List<String> valueNos) {
        if (valueNos.isEmpty()) {
            return Map.of();
        }
        return DataScopeContext.executeWithoutScope(() ->
                        valueMapper.selectList(Wrappers.<PrdSpecValue>lambdaQuery()
                                .in(PrdSpecValue::getValueNo, Set.copyOf(valueNos))))
                .stream().collect(Collectors.toMap(PrdSpecValue::getValueNo, Function.identity(), (a, b) -> a));
    }

    private List<PrdSpecValue> platformValues(String dimNo) {
        return DataScopeContext.executeWithoutScope(() ->
                valueMapper.selectList(Wrappers.<PrdSpecValue>lambdaQuery()
                        .eq(PrdSpecValue::getDimNo, dimNo)
                        .eq(PrdSpecValue::getScope, PrdSpecDim.PLATFORM)));
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String iso(LocalDateTime t) {
        return t == null ? null : t.toInstant(ZoneOffset.UTC).toString();
    }
}
