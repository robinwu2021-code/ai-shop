package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.community.dto.CommunityVO;
import ai.neargo.shop.community.service.CommunityAdminService;
import ai.neargo.shop.community.service.CommunityService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家端 · <b>「我要做的地方平台还没有」</b>：提报新社区、从地图直接开通、看提报进度。
 *
 * <p>从 {@code BizMerchantController} 抽出来的第三块（架构评审 §5.2）。
 * 抽完那个类降到 3 个资源，<b>从内聚欠账名单上出榜</b>。
 *
 * <p>为什么它和「商家主体」不是一回事：这三个端点写的是 <b>community 域</b>的数据，
 * 商家在这里的身份只是提报人。它当初长在 BizMerchantController 里，
 * 唯一的原因是提报入口做在了商家端 —— 那是**入口在哪**，不是**它属于谁**。
 *
 * <p>权限是 {@code biz:store} 而不是 {@code biz:store:admin}：
 * 它与设经营范围是同一件事的两半 —— 能决定「我做哪儿」的人，才该能提「这儿还没开」。
 *
 * <p>纯搬家：方法体、注解、路径、权限码<b>逐字未动</b>。
 */
@Profile("api")
@RestController
public class BizCommunityApplyController {

    private final CommunityAdminService communityAdminService;
    private final CommunityService communityService;

    public BizCommunityApplyController(CommunityAdminService communityAdminService,
                                       CommunityService communityService) {
        this.communityAdminService = communityAdminService;
        this.communityService = communityService;
    }

    // ---------------------------------------------------------------- 提报新社区（ADR-013 阶段三）

    /**
     * 提报一个平台还没有的小区。
     *
     * <p>在这之前商家<b>无路可走</b>：覆盖项只能从已有社区里勾，而「让平台加一个小区」
     * 没有入口 —— 只能找 BD 口头说，说完没人知道进展。
     *
     * <p>要 {@code biz:store} 权限：它与设经营范围是同一件事的两半 ——
     * 能决定「我做哪儿」的人，才该能提「这儿还没开」。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/communities/apply")
    public CommunityAdminService.ApplyVO applyCommunity(@RequestBody CommunityApplyReq req) {
        return communityAdminService.submitApply(BizContext.requireMerchantNo(),
                req.name(), req.address(), req.regionCode(), req.note(),
                req.kind(), req.originCode(), req.latE6(), req.lngE6());
    }

    /**
     * 从地图上选中一个点，**直接开通聚落并返回**（不再提报、不用等）。
     *
     * <p>与 {@code /biz/communities/apply} 的关系：那条留给「地图上查无此地」的手动补录；
     * 正常路径是搜到即用 —— 数据来自高德（名字、门牌、坐标都是它给的），
     * 落哪个街道由逆地理定夺，重复由三道闸挡住（见 openFromMap 的注释）。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/communities/from-map")
    public CommunityVO openCommunityFromMap(@RequestBody FromMapReq req) {
        var opened = communityAdminService.openFromMap(BizContext.requireMerchantNo(),
                req.name(), req.address(), req.latE6(), req.lngE6(), req.streetCode());
        /*
         * **回的是与 `/biz/communities` 同一个形状**，不是运营端那个 VO。
         * 端上拿到它就直接塞进「可选聚落」那份列表 —— 两个形状不一样的话，
         * 新开的这条会缺 address/pickups，在列表里长得与其它条目不同，
         * 而这种差异只在「刚加完那一瞬间」出现，最难复现。
         */
        return communityService.detail(opened.communityNo());
    }

    /**
     * @param streetCode 端上已知的街道码（9 位），只在服务端逆地理不可用时兜底
     */
    public record FromMapReq(String name, String address, int latE6, int lngE6, String streetCode) {
    }

    /**
     * 我提报过的。
     *
     * <p>没有这个列表，提报出去等于石沉大海：商家不知道批没批、被驳回的理由是什么，
     * 只会隔几天再提一次同样的 —— 而那正是运营队列里出现重复条目的来源。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/communities/applies")
    public List<CommunityAdminService.ApplyVO> myCommunityApplies() {
        return communityAdminService.appliesOf(BizContext.requireMerchantNo());
    }

    /** @param regionCode 商家选的区划，**只是建议** —— 最终以运营裁决时填的为准 */
    public record CommunityApplyReq(String name, String address, String regionCode, String note,
                                    /** ESTATE 小区 / VILLAGE 村。不传按 ESTATE */
                                    String kind,
                                    /** 提报村时从词典选中的官方村码 */
                                    String originCode,
                                    /** 提报时的定位，可空 —— H5 拿不到权限时照样能提 */
                                    Integer latE6, Integer lngE6) {
    }

}
