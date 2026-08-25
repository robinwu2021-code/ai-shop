package ai.neargo.shop.product.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.dto.CategoryVO;
import ai.neargo.shop.product.dto.OpsCategoryVO;
import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdCategorySpec;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
import ai.neargo.shop.product.mapper.ProductMappers.CategorySpecMapper;
import ai.neargo.shop.product.mapper.ProductMappers.GoodsMapper;
import ai.neargo.shop.product.service.CategoryService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CategoryServiceImpl implements CategoryService {

    /**
     * <b>两级封顶</b>（2026-08-21，TDD-分类模型重构 §1.2）。此前是三级。
     *
     * <p>降级的实际收益是**让「商家选自己卖哪几类」这一步成为可能** ——
     * 三级树把它变成一个要展开两层的操作，而商家心里的答案就是「蔬菜、水果」这一层。
     * 叶菜 / 根茎菜的粒度服务的是搜索导购与比价，一个社区几十家店用不上它，
     * 带来的只有录入负担。
     *
     * <p>代价是资质粒度变粗：`required_code` 从三级上移到二级（V168）。
     * 判据没变，只是范围从「叶菜」扩到「蔬菜」—— 两者要的本来就是同一张食品经营许可证。
     */
    private static final int MAX_LEVEL = 2;

    private static final String ACTIVE = "ACTIVE";
    private static final String ARCHIVED = "ARCHIVED";

    private final CategoryMapper categoryMapper;
    private final GoodsMapper goodsMapper;
    /** 只用于启用类目时那道「有没有配规格」的闸门 */
    private final CategorySpecMapper categorySpecMapper;
    private final ObjectMapper json;

    public CategoryServiceImpl(CategoryMapper categoryMapper, GoodsMapper goodsMapper,
                               CategorySpecMapper categorySpecMapper, ObjectMapper json) {
        this.categorySpecMapper = categorySpecMapper;
        this.categoryMapper = categoryMapper;
        this.goodsMapper = goodsMapper;
        this.json = json;
    }

    @Override
    public List<CategoryVO> tree() {
        // 一次查全量再在内存里建树：类目通常几十条，三次分层查询反而更慢
        List<PrdCategory> all = categoryMapper.selectList(Wrappers.<PrdCategory>lambdaQuery()
                .eq(PrdCategory::getStatus, ACTIVE)
                .orderByAsc(PrdCategory::getSort));

        Map<String, List<PrdCategory>> byParent = all.stream()
                .filter(c -> c.getParentNo() != null && !c.getParentNo().isBlank())
                .collect(Collectors.groupingBy(PrdCategory::getParentNo));

        return all.stream()
                .filter(c -> c.getParentNo() == null || c.getParentNo().isBlank())
                .sorted(Comparator.comparingInt(c -> nz(c.getSort())))
                .map(c -> toVO(c, byParent))
                .toList();
    }

    @Override
    public String requiredCodeOf(String categoryNo) {
        if (categoryNo == null || categoryNo.isBlank()) {
            return null;
        }
        PrdCategory c = categoryMapper.selectOne(Wrappers.<PrdCategory>lambdaQuery()
                .select(PrdCategory::getRequiredCode)
                .eq(PrdCategory::getCategoryNo, categoryNo).last("limit 1"));
        return c == null ? null : c.getRequiredCode();
    }

    @Override
    public String categoryTypeOf(String categoryNo) {
        if (categoryNo == null || categoryNo.isBlank()) {
            return null;
        }
        PrdCategory c = categoryMapper.selectOne(Wrappers.<PrdCategory>lambdaQuery()
                .select(PrdCategory::getTemplate)
                .eq(PrdCategory::getCategoryNo, categoryNo).last("limit 1"));
        /*
         * 查无此类目返回 null 而不是兜底成 NORMAL：兜底会把「端上传了一个不存在的
         * 类目号」变成「这是一件日用品」—— 一条错误数据被静默转成了一条合法数据。
         * 调用方拿到 null 才有机会拒掉。
         */
        return c == null ? null : TEMPLATE_TO_TYPE.get(c.getTemplate());
    }

    @Override
    public boolean isActive(String categoryNo) {
        if (categoryNo == null || categoryNo.isBlank()) {
            return false;
        }
        PrdCategory c = categoryMapper.selectOne(Wrappers.<PrdCategory>lambdaQuery()
                .select(PrdCategory::getStatus)
                .eq(PrdCategory::getCategoryNo, categoryNo).last("limit 1"));
        return c != null && ACTIVE.equals(c.getStatus());
    }

    @Override
    public List<OpsCategoryVO> list(String keyword, String template, boolean showArchived) {
        var q = Wrappers.<PrdCategory>lambdaQuery();
        if (!showArchived) {
            q.eq(PrdCategory::getStatus, ACTIVE);
        }
        if (template != null && !template.isBlank()) {
            q.eq(PrdCategory::getTemplate, template);
        }
        if (keyword != null && !keyword.isBlank()) {
            q.and(w -> w.like(PrdCategory::getName, keyword)
                    .or().like(PrdCategory::getNameEn, keyword)
                    .or().like(PrdCategory::getCategoryNo, keyword));
        }
        List<PrdCategory> rows = categoryMapper.selectList(q.orderByAsc(PrdCategory::getSort));

        /*
         * skuCount 一次算完，不在循环里逐条 count —— 类目几十条时那是几十次往返，
         * 而这个列表是运营每天开的第一个页面。
         */
        Map<String, Integer> counts = countByCategory();
        return rows.stream().map(c -> toOpsVO(c, counts.getOrDefault(c.getCategoryNo(), 0))).toList();
    }

    @Override
    @Transactional
    public OpsCategoryVO save(SaveCategoryCommand cmd) {
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        /*
         * level 由 parentNo 推出，**不采信端上传的值**：端上算错一次，
         * 这个类目就永远挂在错误的层级上，而树渲染出来看不出任何异常 ——
         * 只是它再也不出现在该出现的地方。
         */
        int level = 1;
        /*
         * **子节点的 template 一律继承父节点，端上传什么都忽略。**
         *
         * 品类改成由类目派生之后，这棵树就承载了履约与合规判定 —— 父节点是 FRESH、
         * 子节点被填成 STANDARD 的话，同一支上的商品会走两套履约，而树渲染出来
         * 看不出任何异常。此前没有任何一处拦这个。
         *
         * 只有一级类目（无父）才由运营指定 template —— 也就是说
         * <b>形态实际上锁在根这一层</b>，这是「靠层级治理而不是靠字段互相制衡」的落点。
         */
        String template = cmd.template() == null || cmd.template().isBlank()
                ? "STANDARD" : cmd.template();
        if (cmd.parentNo() != null && !cmd.parentNo().isBlank()) {
            PrdCategory parent = byNo(cmd.parentNo());
            level = nz(parent.getLevel()) + 1;
            if (level > MAX_LEVEL) {
                throw BizException.of(ErrorCode.CATEGORY_TOO_DEEP);
            }
            template = parent.getTemplate() == null || parent.getTemplate().isBlank()
                    ? "STANDARD" : parent.getTemplate();
        }

        boolean isNew = cmd.categoryNo() == null || cmd.categoryNo().isBlank();
        PrdCategory c = isNew ? newCategory() : byNo(cmd.categoryNo());

        c.setName(cmd.name());
        c.setNameEn(blankToNull(cmd.nameEn()));
        c.setParentNo(blankToNull(cmd.parentNo()));
        c.setLevel(level);
        c.setTemplate(template);
        c.setQualificationRequired(writeJson(cmd.qualifications()));
        c.setRequiredCode(blankToNull(cmd.requiredCode()));
        c.setIcon(cmd.icon() == null ? "" : cmd.icon());
        if (cmd.sort() != null) {
            c.setSort(cmd.sort());
        }

        if (isNew) {
            categoryMapper.insert(c);
        } else {
            categoryMapper.updateById(c);
        }
        return toOpsVO(c, counts(c.getCategoryNo()));
    }

    @Override
    @Transactional
    public OpsCategoryVO archive(String categoryNo) {
        PrdCategory c = byNo(categoryNo);

        /*
         * **有商品不再是拦截，是提示。**
         *
         * <p>运营停一个类目通常是政策要求（这一类我们这期不做了、资质链路没接上）——
         * 拦住他并不能让那批商品消失，只会让他去别处想办法，而「别处」多半是直接改库。
         * 现在的做法是把后果说清楚（{@link #archiveImpact}：还有几件在售、几家店摆着货架），
         * 由他决定是先下架、改类目，还是照样停用。
         *
         * <p>停用后那批商品会怎样：商家仍看得到、仍能编辑，但**上架会被类目校验挡下**，
         * C 端导购与类目筛选也不再命中它们 —— 这正是运营停用一个类目时想要的效果。
         *
         * <p>子类目那道判据留着：停一个还有在售子类目的一级，会冒出挂在已归档父节点下的
         * 孤儿，它在树里根本渲染不出来。而这一条端上有现成的「连子级一起关」的流程。
         */
        Long children = categoryMapper.selectCount(Wrappers.<PrdCategory>lambdaQuery()
                .eq(PrdCategory::getParentNo, categoryNo)
                .eq(PrdCategory::getStatus, ACTIVE));
        if (children != null && children > 0) {
            throw BizException.of(ErrorCode.CATEGORY_IN_USE);
        }

        c.setStatus(ARCHIVED);
        categoryMapper.updateById(c);
        // 商品数照实返回：停用不改变「这一类下面还挂着多少货」这个事实
        return toOpsVO(c, counts(categoryNo));
    }

    /**
     * 停用这个类目会影响什么。**停用前给运营看的那句话。**
     *
     * <p>只回答两个数：这一类下面还有几件商品（其中几件在售）、几家店的货架上摆着这一类。
     * 不回答「要不要停」—— 那是运营的判断，界面把后果说清楚就够了。
     */
    @Override
    public ArchiveImpact archiveImpact(String categoryNo) {
        int total = counts(categoryNo);
        Long onSale = goodsMapper.selectCount(Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getCategoryNo, categoryNo)
                .eq(PrdGoods::getOnSale, true));
        Long kids = categoryMapper.selectCount(Wrappers.<PrdCategory>lambdaQuery()
                .eq(PrdCategory::getParentNo, categoryNo)
                .eq(PrdCategory::getStatus, ACTIVE));
        return new ArchiveImpact(total, onSale == null ? 0 : onSale.intValue(),
                kids == null ? 0 : kids.intValue());
    }

    @Override
    @Transactional
    public OpsCategoryVO unarchive(String categoryNo) {
        PrdCategory c = byNo(categoryNo);
        if (c.getParentNo() != null && !c.getParentNo().isBlank()) {
            PrdCategory parent = byNo(c.getParentNo());
            // 否则会冒出一个挂在已归档父节点下的孤儿：它在树里根本渲染不出来
            if (!ACTIVE.equals(parent.getStatus())) {
                throw BizException.of(ErrorCode.CATEGORY_PARENT_ARCHIVED);
            }
        }
        /*
         * **二级类目没配规格就不让启用。**
         *
         * <p>规格绑定挂在二级（线上 175 条全在这一层），而商品挂在类目上。
         * 启用一个没配规格的二级类目，商家一往里放货，建品页因为一个维度都取不到
         * 而掉回老模板的品类兜底 —— 组名叫「规格」、存进去没有 templateNo，
         * 那批货的值编号永远盖不上，跨店比价与聚合对它们全部失效。
         * 而这一切**没有任何报错**：建品成功、页面正常，只有那一列 code 从来没存在过。
         *
         * <p>线上 198 件历史商品里 197 件就是这么来的，V229 用一整支迁移回填，
         * 至今还有 92 件填不了。所以这里当场拒绝，而不是给个可以点掉的提醒 ——
         * 提醒挡不住「先启用、回头再配」，而回头往往就是几个月。
         *
         * <p>只管二级：一级不放商品；三级从父类目继承规格
         * （见 SpecLibraryServiceImpl#specCategoryOf），父类目这道闸门已经守住了。
         */
        if (Integer.valueOf(2).equals(c.getLevel()) && !hasActiveSpec(categoryNo)) {
            throw BizException.of(ErrorCode.CATEGORY_HAS_NO_SPEC);
        }
        c.setStatus(ACTIVE);
        categoryMapper.updateById(c);
        return toOpsVO(c, counts(categoryNo));
    }

    /** 这个类目有没有至少一条启用中的规格绑定。 */
    private boolean hasActiveSpec(String categoryNo) {
        Long n = categorySpecMapper.selectCount(Wrappers.<PrdCategorySpec>lambdaQuery()
                .eq(PrdCategorySpec::getCategoryNo, categoryNo)
                .eq(PrdCategorySpec::getStatus, ACTIVE));
        return n != null && n > 0;
    }

    // ───────────────────────────────────────────────────────────────────

    private PrdCategory newCategory() {
        PrdCategory c = new PrdCategory();
        c.setCategoryNo(BizKey.next(BizKey.CATEGORY));
        c.setStatus(ACTIVE);
        c.setSort(0);
        return c;
    }

    private PrdCategory byNo(String categoryNo) {
        PrdCategory c = categoryMapper.selectOne(Wrappers.<PrdCategory>lambdaQuery()
                .eq(PrdCategory::getCategoryNo, categoryNo).last("limit 1"));
        if (c == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return c;
    }

    private Map<String, Integer> countByCategory() {
        return goodsMapper.selectList(Wrappers.<PrdGoods>lambdaQuery()
                        .select(PrdGoods::getCategoryNo)
                        .isNotNull(PrdGoods::getCategoryNo)).stream()
                .collect(Collectors.groupingBy(PrdGoods::getCategoryNo,
                        Collectors.summingInt(g -> 1)));
    }

    private int counts(String categoryNo) {
        Long n = goodsMapper.selectCount(Wrappers.<PrdGoods>lambdaQuery()
                .eq(PrdGoods::getCategoryNo, categoryNo));
        return n == null ? 0 : n.intValue();
    }

    private CategoryVO toVO(PrdCategory c, Map<String, List<PrdCategory>> byParent) {
        List<CategoryVO> children = byParent.getOrDefault(c.getCategoryNo(), List.of()).stream()
                .sorted(Comparator.comparingInt(x -> nz(x.getSort())))
                .map(x -> toVO(x, byParent))
                .toList();
        return new CategoryVO(c.getCategoryNo(), c.getParentNo(), nz(c.getLevel()),
                c.getName(), c.getIcon(), nz(c.getSort()), c.getTemplate(),
                blankToNull(c.getRequiredCode()), readList(c.getQualificationRequired()), children);
    }

    private OpsCategoryVO toOpsVO(PrdCategory c, int skuCount) {
        return new OpsCategoryVO(
                c.getCategoryNo(), c.getName(),
                // 一级类目下发空串而不是 null：端上用 `parentNo ? ... : ...` 判顶层，
                // null 与 undefined 在严格相等下不是一回事，曾让整棵树一个根都建不出来
                blankToNull(c.getParentNo()), nz(c.getLevel()),
                c.getTemplate() == null ? "STANDARD" : c.getTemplate(),
                readList(c.getQualificationRequired()),
                c.getRequiredCode(),
                new OpsCategoryVO.I18nText(c.getName(), c.getNameEn()),
                nz(c.getSort()),
                skuCount,
                // 归档时间用 updatedAt 顶替：status 才是权威，这里只是给运营看个时间
                ARCHIVED.equals(c.getStatus()) && c.getUpdatedAt() != null
                        ? c.getUpdatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
    }

    private List<String> readList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (RuntimeException e) {
            // 脏数据不该让整个列表页 500：运营看到的是空资质，而不是一个打不开的页面
            return List.of();
        }
    }

    private String writeJson(List<String> v) {
        return v == null || v.isEmpty() ? null : json.writeValueAsString(v);
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v;
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }
}
