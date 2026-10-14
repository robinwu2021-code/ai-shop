package ai.neargo.shop.product.api.ops;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.product.dto.CategoryVO;
import ai.neargo.shop.product.entity.PrdCategoryPayMode;
import ai.neargo.shop.product.entity.PrdCategoryPoints;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryPayModeMapper;
import ai.neargo.shop.product.mapper.ProductMappers.CategoryPointsMapper;
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
 * 平台端 · <b>类目策略</b>：这个类目能不能当面付、发多少积分。
 *
 * <p>两张表放一个控制器，因为它们回答的是同一个问题 ——
 * 「这个类目怎么配」。运营也是在同一屏里做这两件事。
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
    private final CategoryPayModeMapper payModeMapper;
    private final CategoryPointsMapper pointsMapper;
    private final AuditLogPort auditLogPort;

    public OpsCategoryPolicyController(CategoryService categoryService,
                                       CategoryPayModeMapper payModeMapper,
                                       CategoryPointsMapper pointsMapper,
                                       AuditLogPort auditLogPort) {
        this.categoryService = categoryService;
        this.payModeMapper = payModeMapper;
        this.pointsMapper = pointsMapper;
        this.auditLogPort = auditLogPort;
    }

    // ---------------------------------------------------------------- 类目 × 支付方式

    /**
     * 类目 × 支付方式。
     *
     * <p><b>{@code offlineAllowed} 的默认是「允许」</b>：这张表的语义是
     * 「没有行即放行，插 allowed=0 才是禁止」。设计成白名单的话，
     * 上线当天得先把 57 个类目全配一遍才有人下得了单 ——
     * 而一期只想用「主体资质」那一层做主力。
     */
    @GetMapping("/ops/category-pay-modes")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_READ + "')")
    public List<CategoryPayModeVO> categoryPayModes() {
        Map<String, PrdCategoryPayMode> rows = new LinkedHashMap<>();
        for (PrdCategoryPayMode r : DataScopeContext.executeWithoutScope(() ->
                payModeMapper.selectList(Wrappers.<PrdCategoryPayMode>lambdaQuery()
                        .eq(PrdCategoryPayMode::getPayMode, PayModes.OFFLINE)))) {
            rows.put(r.getCategoryNo(), r);
        }
        List<CategoryPayModeVO> out = new ArrayList<>();
        forEachLeaf((lv1, lv2) -> {
            PrdCategoryPayMode row = rows.get(lv2.categoryNo());
            out.add(new CategoryPayModeVO(lv2.categoryNo(), lv2.name(), lv1.name(),
                    row == null || !Integer.valueOf(0).equals(row.getAllowed()),
                    row != null));
        });
        return out;
    }

    /**
     * 开关一个类目的线下支付。
     *
     * <p><b>禁止时插行、允许时删行</b>，不是留一行 allowed=1 ——
     * 这样「有几个类目被禁了」永远等于表里的行数，一眼可查。
     * 留 allowed=1 的话，那张表会慢慢长成一份「配过但不禁」的噪声清单，
     * 而真正被禁的那几条埋在里面。
     */
    @PostMapping("/ops/category-pay-modes/{categoryNo}")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_UPDATE + "')")
    @Transactional
    public List<CategoryPayModeVO> saveCategoryPayMode(@PathVariable String categoryNo,
                                                       @RequestBody PayModeReq req) {
        boolean allow = Boolean.TRUE.equals(req.offlineAllowed());
        DataScopeContext.executeWithoutScope(() -> {
            payModeMapper.delete(Wrappers.<PrdCategoryPayMode>lambdaQuery()
                    .eq(PrdCategoryPayMode::getCategoryNo, categoryNo)
                    .eq(PrdCategoryPayMode::getPayMode, PayModes.OFFLINE));
            if (!allow) {
                PrdCategoryPayMode row = new PrdCategoryPayMode();
                row.setCategoryNo(categoryNo);
                row.setPayMode(PayModes.OFFLINE);
                row.setAllowed(0);
                payModeMapper.insert(row);
            }
            return null;
        });
        // 关掉一个类目的当面付，等于让那一类商家的一批订单换一条收款路径 —— 必须留痕
        auditLogPort.record("CATEGORY_PAY_MODE", categoryNo, allow ? "允许当面付" : "禁止当面付");
        return categoryPayModes();
    }

    // ---------------------------------------------------------------- 类目 × 积分

    /**
     * 类目 × 积分发放规则。
     *
     * <p><b>平台按类目统一管理，商家不参与配置</b>（依据是实测：线上 199 件商品里，
     * 用商品级 points_config 配了积分的是 <b>0</b> 件）。而运营配 30 个类目是做得到的 ——
     * 规格那套现在 30/30 全配齐。
     */
    @GetMapping("/ops/category-points")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_READ + "')")
    public List<CategoryPointsVO> categoryPoints() {
        Map<String, PrdCategoryPoints> rows = new LinkedHashMap<>();
        for (PrdCategoryPoints r : DataScopeContext.executeWithoutScope(() ->
                pointsMapper.selectList(Wrappers.query()))) {
            rows.put(r.getCategoryNo(), r);
        }
        List<CategoryPointsVO> out = new ArrayList<>();
        forEachLeaf((lv1, lv2) -> {
            PrdCategoryPoints row = rows.get(lv2.categoryNo());
            out.add(new CategoryPointsVO(lv2.categoryNo(), lv2.name(), lv1.name(),
                    row == null ? null : row.getEarnMode(),
                    row == null ? null : row.getEarnValue()));
        });
        return out;
    }

    /**
     * 存一个类目的积分规则。{@code earnMode} 为空 = 删掉这条规则（回到平台兜底）。
     *
     * <p><b>取值域当场校验</b>：写进去一个拼错的 mode，它不会报错，
     * 只会安安静静地不发分 —— 而运营以为已经配好了。
     * {@code earnValue} 是<b>整数</b>：FIXED 存分、RATIO 存万分比，
     * 不用浮点 —— 金额与比例一旦用 double，对账时的分位差没人说得清。
     */
    @PostMapping("/ops/category-points/{categoryNo}")
    @PreAuthorize("@perm.can('" + Perms.PRODUCT_CATEGORY_UPDATE + "')")
    @Transactional
    public List<CategoryPointsVO> saveCategoryPoints(@PathVariable String categoryNo,
                                                     @RequestBody PointsReq req) {
        String mode = req.earnMode();
        boolean clear = mode == null || mode.isBlank();
        if (!clear && !PrdCategoryPoints.FIXED.equals(mode) && !PrdCategoryPoints.RATIO.equals(mode)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!clear && (req.earnValue() == null || req.earnValue() < 0)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        DataScopeContext.executeWithoutScope(() -> {
            pointsMapper.delete(Wrappers.<PrdCategoryPoints>lambdaQuery()
                    .eq(PrdCategoryPoints::getCategoryNo, categoryNo));
            if (!clear) {
                PrdCategoryPoints row = new PrdCategoryPoints();
                row.setCategoryNo(categoryNo);
                row.setEarnMode(mode);
                row.setEarnValue(req.earnValue());
                pointsMapper.insert(row);
            }
            return null;
        });
        // 积分是平台对用户的负债，改发放规则就是改负债的产生速度
        auditLogPort.record("CATEGORY_POINTS", categoryNo,
                clear ? "清除规则" : mode + " " + req.earnValue());
        return categoryPoints();
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

    /** @param configured 是否**显式配过**。与 offlineAllowed 分开：没配过也是允许，但两者含义不同 */
    public record CategoryPayModeVO(String categoryNo, String categoryName, String parentName,
                                    boolean offlineAllowed, boolean configured) {
    }

    public record CategoryPointsVO(String categoryNo, String categoryName, String parentName,
                                   String earnMode, Long earnValue) {
    }

    public record PayModeReq(Boolean offlineAllowed) {
    }

    public record PointsReq(String earnMode, Long earnValue) {
    }
}
