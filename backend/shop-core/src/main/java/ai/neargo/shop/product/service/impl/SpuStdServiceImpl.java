package ai.neargo.shop.product.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.product.dto.GoodsVO;
import ai.neargo.shop.product.dto.OpsCategoryVO;
import ai.neargo.shop.product.dto.SpuStdVO;
import ai.neargo.shop.product.entity.PrdSpuStd;
import ai.neargo.shop.product.mapper.ProductMappers.SpuStdMapper;
import ai.neargo.shop.product.service.CategoryService;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.product.service.SpuStdService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** {@link SpuStdService} 实现。 */
@Service
public class SpuStdServiceImpl implements SpuStdService {

    /** 商家侧搜索的兜底条数。给太多在手机上翻不完，给太少常见品搜不全 */
    private static final int DEFAULT_LIMIT = 20;

    private final SpuStdMapper mapper;
    private final CategoryService categoryService;
    private final ObjectMapper json;

    public SpuStdServiceImpl(SpuStdMapper mapper, CategoryService categoryService, ObjectMapper json) {
        this.mapper = mapper;
        this.categoryService = categoryService;
        this.json = json;
    }

    @Override
    public List<SpuStdVO> search(String keyword, String categoryNo, int limit) {
        int size = limit <= 0 || limit > 50 ? DEFAULT_LIMIT : limit;
        LambdaQueryWrapper<PrdSpuStd> w = Wrappers.<PrdSpuStd>lambdaQuery()
                .eq(PrdSpuStd::getStatus, PrdSpuStd.ACTIVE)
                .eq(notBlank(categoryNo), PrdSpuStd::getCategoryNo, categoryNo);
        if (notBlank(keyword)) {
            /*
             * 标题与别名一起搜：商家嘴里的「洋芋」「马铃薯」与标准品标题「土豆」
             * 对不上，而对不上的结果不是报错，是他以为标准库里没有 ——
             * 然后自建一个，跨店可比在这一次就丢了。keywords 那一列就是为这个存在的。
             */
            w.and(q -> q.like(PrdSpuStd::getTitle, keyword)
                    .or().like(PrdSpuStd::getKeywords, keyword));
        }
        // 被引用得多的排前面：那是「别的店都在用这一条」，对搜的人是有效信号
        w.orderByDesc(PrdSpuStd::getRefCount).orderByAsc(PrdSpuStd::getId).last("limit " + size);
        return toVOs(query(w));
    }

    @Override
    public PageData<SpuStdVO> list(String keyword, String categoryNo, String source,
                                   boolean showArchived, long page, long size) {
        LambdaQueryWrapper<PrdSpuStd> w = Wrappers.<PrdSpuStd>lambdaQuery()
                .eq(!showArchived, PrdSpuStd::getStatus, PrdSpuStd.ACTIVE)
                .eq(notBlank(categoryNo), PrdSpuStd::getCategoryNo, categoryNo)
                /*
                 * **按出处筛**。导进来的那 297 条众包数据（source=OFF）全是 ARCHIVED，
                 * 要逐条过目才能放出去；混在运营自己录的那些里面翻，第一步就没法做。
                 */
                .eq(notBlank(source), PrdSpuStd::getSource, source);
        if (notBlank(keyword)) {
            w.and(q -> q.like(PrdSpuStd::getTitle, keyword)
                    .or().like(PrdSpuStd::getKeywords, keyword)
                    .or().like(PrdSpuStd::getStdNo, keyword));
        }
        w.orderByDesc(PrdSpuStd::getId);
        Page<PrdSpuStd> p = DataScopeContext.executeWithoutScope(() ->
                mapper.selectPage(Page.of(page, size), w));
        return PageData.of(toVOs(p.getRecords()), p.getTotal(), page, size);
    }

    @Override
    public SpuStdVO find(String stdNo) {
        PrdSpuStd row = row(stdNo);
        return row == null ? null : toVOs(List.of(row)).get(0);
    }

    @Override
    @Transactional
    public SpuStdVO save(SaveCommand cmd) {
        if (cmd.title() == null || cmd.title().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 类目必填，且必须真实存在 —— 标准品的形态由它派生，而商家取用时
         * 类目是**不可改**的。一个挂着查无此类目的标准品，会让每个引用它的商家
         * 在保存那一刻撞上 CATEGORY_NOT_FOUND，而错在运营录的这一行上。
         */
        if (cmd.categoryNo() == null || cmd.categoryNo().isBlank()
                || categoryService.categoryTypeOf(cmd.categoryNo()) == null) {
            throw BizException.of(ErrorCode.CATEGORY_NOT_FOUND);
        }
        requireCodes(cmd.specGroups());

        boolean isNew = !notBlank(cmd.stdNo());
        PrdSpuStd t = isNew ? newStd() : requireRow(cmd.stdNo());
        t.setCategoryNo(cmd.categoryNo());
        t.setTitle(cmd.title().trim());
        t.setTitleI18n(writeMap(cmd.titleI18n()));
        t.setSubtitle(cmd.subtitle());
        t.setCover(cmd.cover());
        t.setImages(writeJson(cmd.images()));
        t.setSpecGroups(writeSpecGroups(cmd.specGroups()));
        t.setKeywords(cmd.keywords());
        PrdSpuStd row = t;
        DataScopeContext.executeWithoutScope(() ->
                isNew ? mapper.insert(row) : mapper.updateById(row));
        return toVOs(List.of(t)).get(0);
    }

    @Override
    @Transactional
    public SpuStdVO archive(String stdNo) {
        PrdSpuStd t = requireRow(stdNo);
        /*
         * **不检查有没有商品在引用它**，与类目归档那条规矩相反 ——
         * 因为 std_no 是溯源不是外键：归档只是「以后别再从这条建品了」，
         * 已经建出来的商品照常在售、照常可编辑。拦住反而会让一条录错的标准品
         * 因为被引用过就永远撤不下来。
         */
        t.setStatus(PrdSpuStd.ARCHIVED);
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(t));
        return toVOs(List.of(t)).get(0);
    }

    @Override
    @Transactional
    public int bulkStatus(List<String> stdNos, String status) {
        if (stdNos == null || stdNos.isEmpty()) {
            return 0;
        }
        if (!PrdSpuStd.ACTIVE.equals(status) && !PrdSpuStd.ARCHIVED.equals(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * **一次上限 200 条**。上面是按页多选来的，一页最多 50，200 已经很宽；
         * 不设上限的话，一个手搓的请求能把整库状态翻过去，而这张表是几百家店共用的主数据。
         */
        List<String> ids = stdNos.stream().filter(SpuStdServiceImpl::notBlank).distinct().toList();
        if (ids.size() > 200) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        List<PrdSpuStd> rows = DataScopeContext.executeWithoutScope(() ->
                mapper.selectList(Wrappers.<PrdSpuStd>lambdaQuery()
                        .in(PrdSpuStd::getStdNo, ids)
                        .ne(PrdSpuStd::getStatus, status)));   // 已经是目标状态的不动，也不计数
        for (PrdSpuStd row : rows) {
            row.setStatus(status);
            DataScopeContext.executeWithoutScope(() -> mapper.updateById(row));
        }
        return rows.size();
    }

    @Override
    @Transactional
    public SpuStdVO unarchive(String stdNo) {
        PrdSpuStd t = requireRow(stdNo);
        t.setStatus(PrdSpuStd.ACTIVE);
        DataScopeContext.executeWithoutScope(() -> mapper.updateById(t));
        return toVOs(List.of(t)).get(0);
    }

    // ---------------------------------------------------------------- helpers

    /**
     * 每个规格选项都必须带 code —— 与平台规格模板同一条校验。
     *
     * <p><b>这是标准品存在的唯一理由</b>：没有 code，三家店的「500g」「五百克」「0.5kg」
     * 永远聚合不到一起，标准品与商家手输没有任何区别，它唯一的作用是让人
     * <b>以为</b>规格统一了。
     */
    private void requireCodes(List<MerchantGoodsService.SpecGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            // 单规格标准品是合法的（一瓶水就一种）：那时没有规格组要校验
            return;
        }
        for (var g : groups) {
            List<String> options = g.options() == null ? List.of() : g.options();
            List<String> codes = g.optionCodes() == null ? List.of() : g.optionCodes();
            if (options.isEmpty() || codes.size() != options.size()) {
                throw BizException.of(ErrorCode.SPEC_TEMPLATE_CODE_REQUIRED);
            }
            Set<String> seen = new LinkedHashSet<>();
            for (String c : codes) {
                if (c == null || c.isBlank()) {
                    throw BizException.of(ErrorCode.SPEC_TEMPLATE_CODE_REQUIRED);
                }
                // 组内 code 重复会让两个规格在聚合时并成同一个 —— 正是 code 要防的事
                if (!seen.add(c.trim())) {
                    throw BizException.of(ErrorCode.SPEC_TEMPLATE_DUPLICATE);
                }
            }
        }
    }

    private List<PrdSpuStd> query(LambdaQueryWrapper<PrdSpuStd> w) {
        return DataScopeContext.executeWithoutScope(() -> mapper.selectList(w));
    }

    private PrdSpuStd newStd() {
        PrdSpuStd t = new PrdSpuStd();
        t.setStdNo(BizKey.next(BizKey.SPU_STD));
        t.setStatus(PrdSpuStd.ACTIVE);
        t.setRefCount(0);
        return t;
    }

    private PrdSpuStd row(String stdNo) {
        if (!notBlank(stdNo)) {
            return null;
        }
        return DataScopeContext.executeWithoutScope(() -> mapper.selectOne(
                Wrappers.<PrdSpuStd>lambdaQuery().eq(PrdSpuStd::getStdNo, stdNo).last("limit 1")));
    }

    private PrdSpuStd requireRow(String stdNo) {
        PrdSpuStd t = row(stdNo);
        if (t == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return t;
    }

    private List<SpuStdVO> toVOs(List<PrdSpuStd> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        // 类目名批量拼：表上只存 categoryNo。总量有限，一次取全表比按需查 N 次划算
        Map<String, String> names = categoryService.list(null, null, true).stream()
                .collect(Collectors.toMap(OpsCategoryVO::categoryNo, OpsCategoryVO::name, (a, b) -> a));
        return rows.stream().map(t -> new SpuStdVO(
                t.getStdNo(), t.getCategoryNo(), names.get(t.getCategoryNo()),
                t.getTitle(), readMap(t.getTitleI18n()), t.getSubtitle(),
                t.getCover(), readList(t.getImages()), readSpecGroups(t.getSpecGroups()),
                t.getKeywords(), t.getStatus(),
                t.getRefCount() == null ? 0 : t.getRefCount(),
                t.getBarcode(), t.getSource())).toList();
    }

    private String writeSpecGroups(List<MerchantGoodsService.SpecGroup> groups) {
        if (groups == null || groups.isEmpty()) {
            return "[]";
        }
        return writeJson(groups.stream().map(g -> {
            Map<String, Object> m = new java.util.LinkedHashMap<String, Object>();
            m.put("name", g.name());
            m.put("options", g.options() == null ? List.of() : g.options());
            m.put("optionCodes", g.optionCodes() == null ? List.of() : g.optionCodes());
            if (notBlank(g.templateNo())) {
                m.put("templateNo", g.templateNo());
            }
            return m;
        }).toList());
    }

    private List<GoodsVO.SpecGroupVO> readSpecGroups(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<GoodsVO.SpecGroupVO>>() {
            });
        } catch (RuntimeException e) {
            // 脏数据不该让整个搜索 500：搜的人看到的是「这条没有规格」，而不是一个打不开的页面
            return List.of();
        }
    }

    private List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (RuntimeException e) {
            return List.of();
        }
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

    private String writeMap(Map<String, String> m) {
        return m == null || m.isEmpty() ? null : writeJson(m);
    }

    private String writeJson(Object v) {
        try {
            return json.writeValueAsString(v == null ? List.of() : v);
        } catch (RuntimeException e) {
            return "[]";
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
