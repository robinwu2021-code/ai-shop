package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.member.dto.MemberVOs.MemberDetailVO;
import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.dto.MemberVOs.MemberStatsVO;
import ai.neargo.shop.member.dto.MemberVOs.MemberVO;
import ai.neargo.shop.member.service.MemberService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家侧会员（P1：名单 / 统计 / 详情）。
 *
 * <p><b>权限沿用 {@code biz:customer}</b>（客户资产）：会员就是「我的客户」那一页的升级版，
 * 新造一个码只会让每个角色都要重新配一遍。店员没有这个码 —— 那台共用手机就在柜台上。
 *
 * <p>控制器只做「取参数、判权、调 service、拼 VO」。会员、标签、人群、口径都在同一屏里操作，
 * 所以它们在同一个控制器（按权限与使用者切，不按资源切）。
 */
@RestController
public class BizMemberController {

    private final MemberService memberService;

    public BizMemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    /**
     * 会员列表。
     *
     * <p><b>按手机号查必须给完整号</b> —— 前缀模糊会把会员库变成一本通讯录。
     * 门店维度：主体开了「按门店经营」时端上必须传 {@code storeNo}，
     * 否则四个数字与列表按主体算，而他看的是一家店。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/members")
    public PageData<MemberVO> members(@RequestParam(required = false) String storeNo,
                                      @RequestParam(required = false) String level,
                                      @RequestParam(required = false) String source,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) String phone,
                                      @RequestParam(required = false) Long lastOrderBefore,
                                      @RequestParam(required = false) Long lastOrderAfter,
                                      @RequestParam(required = false) Long spentMin,
                                      @RequestParam(required = false) Long spentMax,
                                      @RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "20") long size) {
        return memberService.list(BizContext.requireMerchantNo(),
                new MemberQuery(storeNo, level, source, status, phone,
                        lastOrderBefore, lastOrderAfter, spentMin, spentMax, page, size));
    }

    /** 四层人数 + 可触达 + 本月新增。数字即入口，端上点一个就按那一层筛 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/members/stats")
    public MemberStatsVO stats(@RequestParam(required = false) String storeNo) {
        return memberService.stats(BizContext.requireMerchantNo(), storeNo);
    }

    /** 详情：各店往来 + 来源轨迹（谁发的链接、哪个员工录的）+ 标签与备注 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/members/{memberNo}")
    public MemberDetailVO detail(@PathVariable String memberNo) {
        return memberService.detail(BizContext.requireMerchantNo(), memberNo)
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }
}
