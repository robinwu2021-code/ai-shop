package ai.neargo.shop.marketing.api.biz;

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

    @GetMapping("/biz/campaign")
    public List<CampaignVO> list() {
        return campaignService.list(BizContext.requireMerchantNo());
    }

    /** 新建或编辑（{@code campaignNo} 为空即新建）。类型创建后不可改，时间区间必须成立。 */
    @PostMapping("/biz/campaign")
    public CampaignVO save(@RequestBody SaveReq req) {
        return campaignService.save(BizContext.requireMerchantNo(), new CampaignService.SaveCommand(
                req.campaignNo(), req.type(), req.name(), req.startAt(), req.endAt(),
                req.thresholdMinor(), req.discountMinor(), req.flashPriceMinor(),
                req.buyN(), req.giftM(), req.goodsNos(), req.totalCount(), req.storeNo()));
    }

    /** 启停。只允许 RUNNING ↔ PAUSED —— 已结束的活动不可复活。 */
    @PostMapping("/biz/campaign/{campaignNo}/toggle")
    public CampaignVO toggle(@PathVariable String campaignNo, @RequestBody ToggleReq req) {
        return campaignService.toggle(BizContext.requireMerchantNo(), campaignNo,
                Boolean.TRUE.equals(req.running()));
    }

    // ---------------------------------------------------------------- 商家团

    @GetMapping("/biz/groups")
    public List<GroupBuyVO> groups() {
        return groupService.merchantGroups(BizContext.requireMerchantNo());
    }

    /** 开团。团购价与起团人数取自商品上已配好的拼团设置，这一步不能临时定价。 */
    @PostMapping("/biz/groups")
    public GroupBuyVO createGroup(@RequestBody CreateGroupReq req) {
        return groupService.createMerchantGroup(BizContext.requireMerchantNo(), req.goodsNo());
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

    public record CreateGroupReq(String goodsNo) {
    }
}
