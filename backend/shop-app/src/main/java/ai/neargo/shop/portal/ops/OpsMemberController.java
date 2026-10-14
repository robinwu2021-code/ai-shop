package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.member.dto.MemberVOs.OpsMemberVO;
import ai.neargo.shop.member.dto.MemberVOs.OpsPersonVO;
import ai.neargo.shop.member.dto.MemberVOs.ReachStatVO;
import ai.neargo.shop.member.service.OpsMemberService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 运营侧的会员与人档（P8，O1–O4）。
 *
 * <p><b>跨商家可见，但手机号仍然只有后四位</b> —— 这两句话是并列的，不是前者的例外。
 * 唯一能看到完整号的是申诉处置那条路：单独的权限码、必须填理由、每次写审计。
 */
@Profile("ops")
@RestController
public class OpsMemberController {

    private final OpsMemberService opsMemberService;

    public OpsMemberController(OpsMemberService opsMemberService) {
        this.opsMemberService = opsMemberService;
    }

    /** O1 跨商家会员。`phoneTail` 只接受四位 —— 前缀查询会把这一页变成通讯录 */
    @PreAuthorize("@perm.can('" + Perms.MEMBER_MEMBER_READ + "')")
    @GetMapping("/ops/members")
    public PageData<OpsMemberVO> members(@RequestParam(required = false) String entityNo,
                                         @RequestParam(required = false) String phoneTail,
                                         @RequestParam(defaultValue = "1") long page,
                                         @RequestParam(defaultValue = "20") long size) {
        return opsMemberService.members(entityNo, phoneTail, page, size);
    }

    /** O2 人档：名下会员关系、绑没绑账号、合并历史 */
    @PreAuthorize("@perm.can('" + Perms.MEMBER_PERSON_READ + "')")
    @GetMapping("/ops/persons/{personNo}")
    public OpsPersonVO person(@PathVariable String personNo) {
        return opsMemberService.person(personNo);
    }

    /**
     * 看完整手机号（申诉处置）。
     *
     * <p><b>POST 而不是 GET</b>：它有副作用（写一条审计），而且理由要放在 body 里 ——
     * 放查询串上会被日志、代理、浏览器历史各留一份。
     */
    @PreAuthorize("@perm.can('" + Perms.MEMBER_PHONE_REVEAL + "')")
    @PostMapping("/ops/persons/{personNo}/reveal-phone")
    public RevealResp revealPhone(@PathVariable String personNo, @RequestBody RevealReq req) {
        return new RevealResp(opsMemberService.revealPhone(personNo, req.reason(),
                SecurityUtils.currentUserNo()));
    }

    /** O4 触达量与退订率。**按退订率倒序** —— 发得多不是成绩，发到有人关掉才是问题 */
    @PreAuthorize("@perm.can('" + Perms.MEMBER_MEMBER_READ + "')")
    @GetMapping("/ops/members/reach-stats")
    public List<ReachStatVO> reachStats(@RequestParam(defaultValue = "30") int days) {
        return opsMemberService.reachStats(days);
    }

    /** @param reason 必填且不少于四个字。「查一下」这种理由等于没有理由 */
    public record RevealReq(String reason) {
    }

    public record RevealResp(String phone) {
    }
}
