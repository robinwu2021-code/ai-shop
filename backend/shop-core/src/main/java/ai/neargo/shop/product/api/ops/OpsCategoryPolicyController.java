package ai.neargo.shop.product.api.ops;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.common.config.ModuleDataSources;
import ai.neargo.shop.product.dto.CategoryVO;
import ai.neargo.shop.product.entity.PrdCategory;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryMapper;
import ai.neargo.shop.product.policy.CategoryPayModePolicy;
import ai.neargo.shop.product.service.CategoryService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 平台端 · <b>类目支付策略</b>：控制类目能否使用当面付。
 *
 * <p>运营在此维护类目的线下支付策略。
 *
 * <p><b>形态照搬「类目 × 规格」那一页</b>：<b>没配的类目也要返回</b>。
 * 只返回配过的，运营看到的是一张短表，而看不出「还有 40 个类目没配」——
 * 那正是这一页要回答的问题。
 */
@Profile("ops")
@RestController
@Validated
public class OpsCategoryPolicyController {

    private final CategoryService categoryService;
    private final CategoryMapper categoryMapper;
    private final AuditLogPort auditLogPort;

    public OpsCategoryPolicyController(CategoryService categoryService,
                                       CategoryMapper categoryMapper,
                                       AuditLogPort auditLogPort) {
        this.categoryService = categoryService;
        this.categoryMapper = categoryMapper;
        this.auditLogPort = auditLogPort;
    }

    // ---------------------------------------------------------------- 类目 × 支付方式

    /**
     * 类目 × 支付方式。
     *
     * <p><b>{@code offlineAllowed} 的默认是「允许」</b>：{@code prd_category.disallowed_pay_modes}
     * 只记被禁的方式，空即放行。设计成白名单的话，
     * 上线当天得先把 57 个类目全配一遍才有人下得了单 ——
     * 而一期只想用「主体资质」那一层做主力。
     */
    @GetMapping("/ops/category-pay-modes")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_READ + "')")
    public List<CategoryPayModeVO> categoryPayModes() {
        Map<String, String> disallowed = new LinkedHashMap<>();
        for (PrdCategory c : DataScopeContext.executeWithoutScope(() ->
                categoryMapper.selectList(Wrappers.<PrdCategory>lambdaQuery()
                        .select(PrdCategory::getCategoryNo, PrdCategory::getDisallowedPayModes)
                        .isNotNull(PrdCategory::getDisallowedPayModes)))) {
            disallowed.put(c.getCategoryNo(), c.getDisallowedPayModes());
        }
        List<CategoryPayModeVO> out = new ArrayList<>();
        forEachLeaf((lv1, lv2) -> {
            boolean offlineAllowed = CategoryPayModePolicy.allows(disallowed.get(lv2.categoryNo()), PayModes.OFFLINE);
            out.add(new CategoryPayModeVO(lv2.categoryNo(), lv2.name(), lv1.name(), offlineAllowed, !offlineAllowed));
        });
        return out;
    }

    /**
     * 开关一个类目的线下支付。
     *
     * <p><b>禁止时把 OFFLINE 加进列、允许时移出，移空即存 NULL</b> ——
     * 「有几个类目被禁了」永远等于该列非空的类目数，一眼可查，不会长出「配过但不禁」的噪声。
     */
    @PostMapping("/ops/category-pay-modes/{categoryNo}")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_UPDATE + "')")
    @Transactional(transactionManager = ModuleDataSources.PRODUCT_TX_MANAGER)
    public List<CategoryPayModeVO> saveCategoryPayMode(@PathVariable String categoryNo,
                                                       @RequestBody PayModeReq req) {
        boolean allow = Boolean.TRUE.equals(req.offlineAllowed());
        DataScopeContext.executeWithoutScope(() -> {
            PrdCategory category = categoryMapper.selectOne(Wrappers.<PrdCategory>lambdaQuery()
                    .eq(PrdCategory::getCategoryNo, categoryNo).last("LIMIT 1"));
            if (category == null) {
                throw BizException.of(ErrorCode.CATEGORY_NOT_FOUND);
            }
            categoryMapper.update(null, Wrappers.<PrdCategory>lambdaUpdate()
                    .eq(PrdCategory::getCategoryNo, categoryNo)
                    .set(PrdCategory::getDisallowedPayModes,
                            CategoryPayModePolicy.with(category.getDisallowedPayModes(), PayModes.OFFLINE, !allow)));
            return null;
        });
        // 关掉一个类目的当面付，等于让那一类商家的一批订单换一条收款路径 —— 必须留痕
        auditLogPort.record("CATEGORY_PAY_MODE", categoryNo, allow ? "允许当面付" : "禁止当面付");
        return categoryPayModes();
    }

    /**
     * 遍历所有二级类目。<b>与「类目 × 规格」同一套骨架</b> ——
     * 两页并排放着，行不一样多的话，运营会以为哪一页漏了类目。
     */
    private void forEachLeaf(java.util.function.BiConsumer<CategoryVO, CategoryVO> fn) {
        for (CategoryVO lv1 : categoryService.tree()) {
            for (CategoryVO lv2 : lv1.children()) {
                fn.accept(lv1, lv2);
            }
        }
    }

    /** @param configured 是否显式禁过（列里有 OFFLINE）。保留字段以兼容运营端页面，取值恒为 !offlineAllowed */
    public record CategoryPayModeVO(String categoryNo, String categoryName, String parentName,
                                    boolean offlineAllowed, boolean configured) {
    }

    public record PayModeReq(Boolean offlineAllowed) {
    }

}
