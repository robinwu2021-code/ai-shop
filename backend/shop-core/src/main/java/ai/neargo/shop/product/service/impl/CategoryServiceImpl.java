package ai.neargo.shop.product.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.dto.CategoryVO;
import ai.neargo.shop.product.dto.OpsCategoryVO;
import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.entity.PrdGoods;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
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

    /** 三级封顶：再深一层，C 端的类目导航就没法展示了（也没有第四层的产品定义）。 */
    private static final int MAX_LEVEL = 3;

    private static final String ACTIVE = "ACTIVE";
    private static final String ARCHIVED = "ARCHIVED";

    private final CategoryMapper categoryMapper;
    private final GoodsMapper goodsMapper;
    private final ObjectMapper json;

    public CategoryServiceImpl(CategoryMapper categoryMapper, GoodsMapper goodsMapper, ObjectMapper json) {
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
        if (cmd.parentNo() != null && !cmd.parentNo().isBlank()) {
            PrdCategory parent = byNo(cmd.parentNo());
            level = nz(parent.getLevel()) + 1;
            if (level > MAX_LEVEL) {
                throw BizException.of(ErrorCode.CATEGORY_TOO_DEEP);
            }
        }

        boolean isNew = cmd.categoryNo() == null || cmd.categoryNo().isBlank();
        PrdCategory c = isNew ? newCategory() : byNo(cmd.categoryNo());

        c.setName(cmd.name());
        c.setNameEn(blankToNull(cmd.nameEn()));
        c.setParentNo(blankToNull(cmd.parentNo()));
        c.setLevel(level);
        c.setTemplate(cmd.template() == null || cmd.template().isBlank() ? "STANDARD" : cmd.template());
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
         * 挡两件事：下面还有商品、下面还有未归档的子类目。
         * 不挡的话，那些商品会挂在一个已经不存在的类目上 —— 商家看不出异常
         * （商品还在架上卖），但类目筛选、资质校验、C 端导购全部漏掉它们。
         */
        if (counts(categoryNo) > 0) {
            throw BizException.of(ErrorCode.CATEGORY_IN_USE);
        }
        Long children = categoryMapper.selectCount(Wrappers.<PrdCategory>lambdaQuery()
                .eq(PrdCategory::getParentNo, categoryNo)
                .eq(PrdCategory::getStatus, ACTIVE));
        if (children != null && children > 0) {
            throw BizException.of(ErrorCode.CATEGORY_IN_USE);
        }

        c.setStatus(ARCHIVED);
        categoryMapper.updateById(c);
        return toOpsVO(c, 0);
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
        c.setStatus(ACTIVE);
        categoryMapper.updateById(c);
        return toOpsVO(c, counts(categoryNo));
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
                c.getName(), c.getIcon(), nz(c.getSort()), children);
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
