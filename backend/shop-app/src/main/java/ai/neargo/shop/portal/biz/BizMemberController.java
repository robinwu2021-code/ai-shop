package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.SecurityUtils;
import org.springframework.context.annotation.Profile;
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
import ai.neargo.shop.member.dto.MemberVOs.MergePreviewVO;
import ai.neargo.shop.member.dto.MemberVOs.TagVO;
import ai.neargo.shop.member.service.MemberTagService;
import ai.neargo.shop.member.dto.MemberVOs;
import ai.neargo.shop.member.service.MemberReachService;
import ai.neargo.shop.member.dto.MemberVOs.MemberSettingVO;
import ai.neargo.shop.member.dto.MemberVOs.SegmentVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 商家侧会员（P1：名单 / 统计 / 详情）。
 *
 * <p><b>权限沿用 {@code biz:customer}</b>（客户资产）：会员就是「我的客户」那一页的升级版，
 * 新造一个码只会让每个角色都要重新配一遍。店员没有这个码 —— 那台共用手机就在柜台上。
 *
 * <p>控制器只做「取参数、判权、调 service、拼 VO」。会员、标签、人群、口径都在同一屏里操作，
 * 所以它们在同一个控制器（按权限与使用者切，不按资源切）。
 */
@Profile("api")
@RestController
public class BizMemberController {

    private final MemberService memberService;
    private final MemberTagService tagService;

    private final ai.neargo.shop.member.service.MemberSegmentService segmentService;
    private final MemberReachService reachService;

    public BizMemberController(MemberService memberService, MemberTagService tagService,
                               ai.neargo.shop.member.service.MemberSegmentService segmentService,
                               MemberReachService reachService) {
        this.segmentService = segmentService;
        this.reachService = reachService;
        this.memberService = memberService;
        this.tagService = tagService;
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
                                      @RequestParam(required = false) String tagNos,
                                      @RequestParam(required = false) Long lastOrderBefore,
                                      @RequestParam(required = false) Long lastOrderAfter,
                                      @RequestParam(required = false) Long spentMin,
                                      @RequestParam(required = false) Long spentMax,
                                      @RequestParam(defaultValue = "1") long page,
                                      @RequestParam(defaultValue = "20") long size) {
        return memberService.list(BizContext.requireMerchantNo(),
                new MemberQuery(storeNo, level, source, status, phone, split(tagNos),
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

    // ---------------------------------------------------------------- 录入与标签（P2）

    /**
     * 手工录入一个手机号。
     *
     * <p>本人还没在平台出现过时记为**线索**：<b>不可触达、不进任何受众</b> ——
     * 录入手机号不等于拿到推送许可。端上要在**保存之前**把这句说清楚，
     * 否则他会以为发不出去是功能坏了。
     *
     * <p>已存在就返回那一条并把备注并进去，不报错 —— 店员重复录入是常态。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @PostMapping("/biz/members")
    public MemberVO enroll(@RequestBody EnrollReq req) {
        BizContext ctx = BizContext.current();
        var m = memberService.enroll(BizContext.requireMerchantNo(), req.phone(), req.remark(),
                req.tagNos(), req.storeNo() != null ? req.storeNo() : ctx.currentStoreNo(),
                SecurityUtils.currentUserNo());
        return memberService.detail(BizContext.requireMerchantNo(), m.getMemberNo())
                .map(d -> d.member())
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    /**
     * 改备注 / 拉黑与恢复。**线索不能被商家点一下转正** —— 那只能由本人绑定账号触发。
     *
     * <p><b>用 PUT 不用 PATCH</b>：微信小程序的 {@code wx.request} 不支持 PATCH
     * （uni 的类型里也没有），端上根本发不出去。语义上这里确实是局部更新，
     * 但可移植性优先 —— 一个发不出去的动词没有意义。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @PutMapping("/biz/members/{memberNo}")
    public MemberVO patch(@PathVariable String memberNo, @RequestBody PatchReq req) {
        memberService.patch(BizContext.requireMerchantNo(), memberNo, req.remark(), req.status());
        return memberService.detail(BizContext.requireMerchantNo(), memberNo)
                .map(d -> d.member())
                .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    /** 批量打标 / 去标。先筛出人，再一次性打 —— 一个一个点是这一页最没必要的重复劳动 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @PostMapping("/biz/members/tags")
    public void tag(@RequestBody TagReq req) {
        tagService.tag(BizContext.requireMerchantNo(), req.memberNos(), req.add(), req.remove(),
                SecurityUtils.currentUserNo());
    }

    /** 标签字典 + 每个标签多少人（COUNT 出来的，不存冗余列） */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/member-tags")
    public List<TagVO> tags() {
        return tagService.tags(BizContext.requireMerchantNo());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @PostMapping("/biz/member-tags")
    public TagVO createTag(@RequestBody TagEditReq req) {
        return tagService.create(BizContext.requireMerchantNo(), req.name(),
                SecurityUtils.currentUserNo());
    }

    /** 改名或停用。**系统标签两样都不许** —— 它的名字就是口径 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @PutMapping("/biz/member-tags/{tagNo}")
    public TagVO editTag(@PathVariable String tagNo, @RequestBody TagEditReq req) {
        String entityNo = BizContext.requireMerchantNo();
        if (req.enabled() != null) {
            return tagService.setEnabled(entityNo, tagNo, req.enabled());
        }
        return tagService.rename(entityNo, tagNo, req.name());
    }

    /**
     * 合并两个标签。
     *
     * <p><b>{@code confirm=false} 是试算</b>：返回影响面不落库。
     * 界面必须先把「多少人会改、其中多少人两个都有」摆出来再让他按 —— 合并不可逆。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @PostMapping("/biz/member-tags/{tagNo}/merge")
    public MergePreviewVO mergeTag(@PathVariable String tagNo, @RequestBody MergeReq req) {
        return tagService.merge(BizContext.requireMerchantNo(), tagNo, req.intoTagNo(),
                Boolean.TRUE.equals(req.confirm()), SecurityUtils.currentUserNo());
    }

    /** @param tagNos 录入时顺手打上的标签 */
    /**
     * 逗号分隔 → 列表。<b>GET 上不用重复参数</b>（{@code tagNos=a&tagNos=b}）：
     * 端上那个 http 客户端把 `data` 直接拼查询串，数组会被拼成 `[object Object]`。
     */
    private static List<String> split(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(csv.split(",")).map(String::trim)
                .filter(x -> !x.isEmpty()).toList();
    }

    // ------------------------------------------------------------------ 触达（P7）

    /**
     * 试算：这一次能发给多少人、多少人被拦下、分别为什么。<b>不发送</b>。
     *
     * <p>发消息是唯一会打扰真实用户的动作，所以它必须先算后发 ——
     * 只报一个「发送成功」，商家会以为人群里每个人都收到了。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @PostMapping("/biz/member-reach/plan")
    public MemberReachService.ReachPlan planReach(@RequestBody ReachReq req) {
        return reachService.plan(BizContext.requireMerchantNo(), req.segmentNo(), req.scene());
    }

    /**
     * 真发。<b>要 {@code biz:campaign} 而不是 {@code biz:customer}</b>：
     * 看会员的店员都有 customer 码，而给几百个人推消息不是店员该按的按钮。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CAMPAIGN + "')")
    @PostMapping("/biz/member-reach/send")
    public MemberReachService.ReachResult sendReach(@RequestBody ReachReq req) {
        return reachService.send(BizContext.requireMerchantNo(), req.segmentNo(), req.scene(),
                req.title(), req.body(), SecurityUtils.currentUserNo());
    }

    public record ReachReq(String segmentNo, String scene, String title, String body) {
    }

    // ------------------------------------------------------------------ 人群（P3）

    /**
     * 会员经营口径。<b>改它会改变「新客」的含义</b> ——
     * 按门店时，在别的店买过的人在这家店仍算新客。端上必须把这句话写出来。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/member-settings")
    public MemberSettingVO settings() {
        return memberService.settings(BizContext.requireMerchantNo());
    }

    /**
     * 改口径要 {@code biz:store:admin} 而不是 {@code biz:customer}：
     * 它一改，全主体的分层与所有活动受众跟着变 —— 那是主体结构，不是店员该按的开关。
     * 看会员的人（店员）都有 customer 码，挂在那个码上等于人人可改。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE_ADMIN + "')")
    @PutMapping("/biz/member-settings")
    public MemberSettingVO saveSettings(@RequestBody SettingReq req) {
        return memberService.saveSettings(BizContext.requireMerchantNo(),
                req.memberScope(), req.autoJoinOnOrder());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/member-segments")
    public List<SegmentVO> segments() {
        return segmentService.list(BizContext.requireMerchantNo());
    }

    /**
     * 存人群。<b>存的是条件不是名单</b> —— 名单每天都在变，
     * 发券那一刻会重算（那一刻命中了谁记在发放记录里）。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @PostMapping("/biz/member-segments")
    public SegmentVO saveSegment(@RequestBody SegmentReq req) {
        return segmentService.save(BizContext.requireMerchantNo(), req.segmentNo(), req.name(),
                req.scopeStoreNo(), req.rule() == null ? emptyRule() : req.rule());
    }

    /*
     * 用 POST /remove 而不是 DELETE：端上那个 http 客户端只有 GET/POST/PUT
     * （小程序的 RequestOptions 不认 PATCH，为它退过一次改动）。
     * 为一个端点给传输层加一种方法，代价大过它省下的那点语义。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @PostMapping("/biz/member-segments/{segmentNo}/remove")
    public void removeSegment(@PathVariable String segmentNo) {
        segmentService.remove(BizContext.requireMerchantNo(), segmentNo);
    }

    /** 试算：这组条件此刻命中多少人。发券前那句「命中 N 人」就是它 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @PostMapping("/biz/member-segments/preview")
    public MemberVOs.SegmentPreviewVO previewSegment(@RequestBody SegmentReq req) {
        return segmentService.preview(BizContext.requireMerchantNo(), req.scopeStoreNo(),
                req.rule() == null ? emptyRule() : req.rule());
    }

    private static MemberQuery emptyRule() {
        return new MemberQuery(null, null, null, null, null, List.of(),
                null, null, null, null, 1, 0);
    }

    public record SettingReq(String memberScope, Boolean autoJoinOnOrder) {
    }

    public record SegmentReq(String segmentNo, String name, String scopeStoreNo, MemberQuery rule) {
    }

    public record EnrollReq(String phone, String remark, List<String> tagNos, String storeNo) {
    }

    public record PatchReq(String remark, String status) {
    }

    public record TagReq(List<String> memberNos, List<String> add, List<String> remove) {
    }

    /** {@code enabled} 非空 = 停用/恢复；否则按 {@code name} 改名 */
    public record TagEditReq(String name, Boolean enabled) {
    }

    /** @param confirm 空或 false = 只试算，返回影响面不落库 */
    public record MergeReq(String intoTagNo, Boolean confirm) {
    }
}
