package ai.neargo.shop.marketing.api.biz;

import ai.neargo.shop.auth.BizPerms;
import org.springframework.security.access.prepost.PreAuthorize;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.marketing.campaign.CampaignService;
import ai.neargo.shop.marketing.campaign.dto.CampaignVO;
import ai.neargo.shop.marketing.group.GroupService;
import ai.neargo.shop.marketing.group.dto.GroupVOs.GroupBuyVO;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端 · 营销活动与商家团（B-11.8 / B-11.9）。
 *
 * <p>两块放同一个 Controller：它们都是 marketing 域的商家面，而且在 b-app 上是同一屏
 * （「营销」页里的两个 tab）。拆成两个类只会让人多找一次。
 */
@Profile("api")
@RestController
@Validated
public class BizCampaignController {

    private final CampaignService campaignService;
    private final GroupService groupService;

    public BizCampaignController(CampaignService campaignService, GroupService groupService) {
        this.campaignService = campaignService;
        this.groupService = groupService;
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/campaign")
    public List<CampaignVO> list() {
        return campaignService.list(BizContext.requireMerchantNo());
    }

    /** 新建或编辑（{@code campaignNo} 为空即新建）。类型创建后不可改，时间区间必须成立。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/campaign")
    public CampaignVO save(@RequestBody SaveReq req) {
        return campaignService.save(BizContext.requireMerchantNo(), new CampaignService.SaveCommand(
                req.campaignNo(), req.type(), req.name(), req.startAt(), req.endAt(),
                req.thresholdMinor(), req.discountMinor(), req.flashPriceMinor(),
                req.buyN(), req.giftM(), req.goodsNos(), req.totalCount(), req.storeNo()));
    }

    /** 启停。只允许 RUNNING ↔ PAUSED —— 已结束的活动不可复活。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/campaign/{campaignNo}/toggle")
    public CampaignVO toggle(@PathVariable String campaignNo, @RequestBody ToggleReq req) {
        return campaignService.toggle(BizContext.requireMerchantNo(), campaignNo,
                Boolean.TRUE.equals(req.running()));
    }

    // ---------------------------------------------------------------- 商家团

    /**
     * 我的团（s09）。
     *
     * @param status 为空给全部；OPEN（进行中，含待审）/ FORMED（已成团）/ FAILED（已散）
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/groups")
    public List<GroupBuyVO> groups(@RequestParam(required = false) String status) {
        List<GroupBuyVO> all = groupService.merchantGroups(BizContext.requireMerchantNo());
        if (status == null || status.isBlank()) {
            return all;
        }
        // 「进行中」一栏把待审的也放进来：对商家来说它们都是「还没结果的团」
        java.util.Set<String> want = "OPEN".equals(status)
                ? java.util.Set.of("OPEN", "PENDING") : java.util.Set.of(status);
        return all.stream().filter(g -> want.contains(g.status())).toList();
    }

    /** 团详情（s10）：倒计时、成员、商品 / 成团价 / 自提点 / 活动 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/group/{groupNo}")
    public GroupBuyVO group(@PathVariable String groupNo) {
        return groupService.merchantGroup(BizContext.requireMerchantNo(), groupNo);
    }

    /** 散团（s10）：还在拼的团置为已散，已付款的参团单逐张全额退款 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/group/{groupNo}/dissolve")
    public GroupBuyVO dissolve(@PathVariable String groupNo, @RequestBody(required = false) DissolveReq req) {
        return groupService.dissolve(BizContext.requireMerchantNo(), groupNo,
                req == null ? null : req.reason());
    }

    /**
     * 开团（s34）：选活动、商品、自提点三样。人数、成团价、时限从活动带出来，这一步不能临时定价。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/groups")
    public GroupBuyVO createGroup(@RequestBody CreateGroupReq req) {
        return groupService.createMerchantGroup(BizContext.requireMerchantNo(), req.goodsNo(),
                req.activityNo(), req.pickupNo());
    }

    /** @param campaignNo 空 = 新建 */
    public record SaveReq(String campaignNo, String type, String name, long startAt, long endAt,
                          Long thresholdMinor, Long discountMinor, Long flashPriceMinor,
                          Integer buyN, Integer giftM, List<String> goodsNos, Integer totalCount,
                          /** 只对这家门店生效；为空 = 全主体。**只有满减接受它** */
                          String storeNo) {
    }

    public record ToggleReq(Boolean running) {
    }

    /**
     * @param activityNo 页面上选的拼团活动；与这件货此刻所在的活动对不上时拒。可空（老版本不传）
     * @param pickupNo   成团范围（自提点）；为空 = 不限点
     */
    public record CreateGroupReq(String goodsNo, String activityNo, String pickupNo) {
    }

    /** @param reason 散团原因，写进参团买家的退款记录；可空 */
    public record DissolveReq(String reason) {
    }
}
