package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.merchant.dto.EntityStoresVO;
import ai.neargo.shop.merchant.dto.EntityVO;
import ai.neargo.shop.merchant.service.MerchantEntityService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 证照（多证照 · 线 B · B2）：<b>一个老板名下不止一张营业执照</b>时，
 * 「我一共有哪几张」「每张下面有哪几家店」只能从这里问。
 *
 * <p><b>本控制器故意不吃 {@code BizContext.merchantNo}</b> —— 那是「当前这一张证照」，
 * 而这里问的正是「当前之外还有哪几张」。范围一律由 {@code SecurityUtils.currentUserNo()}
 * 的成员行划定，见 {@link MerchantEntityService}。
 *
 * <p><b>为什么不并进 {@code BizMerchantController}</b>：那个已经十七个依赖，
 * 且它的每个方法都在当前证照范围内查；把「跨证照」混进去，
 * 下一个人很难看出哪些方法吃 BizContext、哪些不吃 —— 而这正是会漏数据的那个区别。
 */
@Profile("api")
@RestController
public class BizEntityController {

    private final MerchantEntityService entityService;

    public BizEntityController(MerchantEntityService entityService) {
        this.entityService = entityService;
    }

    /**
     * 我能进的所有门店，按证照分组（01 屏门店选择器）。
     *
     * <p><b>刻意不要 {@code biz:store:admin}</b>，与 {@code /biz/store/list} 同一个理由：
     * 这是「我能进哪几家店」的自查，不是门店管理。要了管理权限的后果是店员一家店都切不了，
     * 而「A 店店长 + B 店店员」这种人恰恰是多门店授权的主要用途。
     *
     * <p>（技术方案 §3.3 原写「§3.1/3.2 所有新增接口统一挂 STORE_ADMIN」，
     * 对这一个不成立 —— 它是切换器不是管理页。另两个接口按原方案挂。）
     */
    @GetMapping("/biz/stores/mine")
    public List<EntityStoresVO> myStores() {
        return entityService.myStores(SecurityUtils.currentUserNo());
    }

    /**
     * 我名下的证照列表（03 屏）。<b>只有老板能看</b> —— 列出的每一张他都能改资料、交执照。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @GetMapping("/biz/entities")
    public List<EntityVO> myEntities() {
        return entityService.myEntities(SecurityUtils.currentUserNo());
    }

    /**
     * 一张证照的详情 + 它的门店（04 屏）。
     *
     * <p>不是我名下的证照 → 403（不是 404）：它确实存在，只是不属于他。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @GetMapping("/biz/entity/{entityNo}")
    public EntityStoresVO detail(@PathVariable String entityNo) {
        return entityService.detail(SecurityUtils.currentUserNo(), entityNo);
    }
}
