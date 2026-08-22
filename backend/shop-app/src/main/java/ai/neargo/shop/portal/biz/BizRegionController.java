package ai.neargo.shop.portal.biz;

import ai.neargo.shop.platform.RegionService;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * B 端 · 行政区划浏览（ADR-013 阶段二）。
 *
 * <p>商家框经营范围时要能逐级挑到街道。与运营端那套是同一个 Service，
 * <b>只差一个口径</b>：这里恒定 {@code enabledOnly=true} ——
 * 停用的区划是运营的维护对象，不该出现在商家的选择器里。
 * 让商家看见一个自己选不了（或选了会被驳回）的选项，只会让他反复来问。
 *
 * <p>放在 {@code api} profile 而不是 {@code ops}：这是商家侧的路由，
 * 以 ops 起服务时它就该 404 —— 隔离靠路由不存在，不靠安全链。
 */
@Profile("api")
@RestController
public class BizRegionController {

    private final RegionService regionService;

    public BizRegionController(RegionService regionService) {
        this.regionService = regionService;
    }

    /**
     * 某区划的直接下级。{@code parent} 为空取省级。
     *
     * <p>带上当前商家：他补录过、运营还没确认的村只对他自己可见。
     * 不带的话他刚录完就在选择器里找不到，看起来像没保存上。
     */
    @GetMapping("/biz/regions")
    public List<RegionService.RegionVO> children(@RequestParam(required = false) String parent) {
        return regionService.children(parent, true, BizContext.requireMerchantNo());
    }

    /**
     * 补录一个平台还没有的村/社区。
     *
     * <p>官方村级数据停在 2023-06-30（统计局已停发），之后新增的村没有任何官方渠道。
     * 缺一个村就等于那一片做不了生意，而「等平台更新」在源头停发之后不会到来。
     *
     * <p>录完立刻能用，但只对他自己可见；运营确认后才转为全网共享。
     */
    /*
     * **写用独立路径，不复用 GET 那个。**
     *
     * 权限表按**路径**判权，同一路径的两个方法只能同进同退 ——
     * 而 `GET /biz/regions` 必须留在免权限那批：还没建店的入驻申请人
     * 也要挑经营范围，要 biz:store 的话他一个区划都选不了。
     * 规格模板当初就是踩了这条才从 PUBLIC 挪出来的（2026-08-21）。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/regions/village")
    public RegionService.RegionVO create(@RequestBody CreateReq req) {
        return regionService.createVillage(req.parent(), req.name(), BizContext.requireMerchantNo());
    }

    /**
     * 被驳回的补录**改了再提**。
     *
     * <p>驳回理由多半是「名字应该叫 XX」—— 让他换个名字重录一条的话，
     * 被驳回的那条会一直留着，同一个村在运营队列里攒下几条一样的驳回记录。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/regions/village/resubmit")
    public RegionService.RegionVO resubmit(@RequestBody ResubmitReq req) {
        return regionService.resubmitVillage(req.regionCode(), req.name(),
                BizContext.requireMerchantNo());
    }

    /** @param parent 上级街道码（9 位）。只能挂街道下，见 createVillage 的说明 */
    public record CreateReq(String parent, String name) {
    }

    public record ResubmitReq(String regionCode, String name) {
    }
}
