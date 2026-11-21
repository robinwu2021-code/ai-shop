package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.member.dto.MemberVOs.MyMembershipVO;
import ai.neargo.shop.member.service.MemberService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 「我是哪几家店的会员」与消息开关（C 端，P7）。
 *
 * <p><b>这一页是发消息功能的前提</b>：顾客要能看到谁在给他发消息、并且能关掉。
 * 没有这个入口就上线群发，等于给了一个没有关闭按钮的喇叭。
 */
@Profile("api")
@RestController
public class MpMyMemberController {

    private final MemberService memberService;

    public MpMyMemberController(MemberService memberService) {
        this.memberService = memberService;
    }

    @GetMapping("/mp/my-memberships")
    public List<MyMembershipVO> mine() {
        return memberService.myMemberships(SecurityUtils.currentUserNo());
    }

    /**
     * 关掉/打开某一家店的消息。
     *
     * <p><b>按当前登录人 + 主体号定位</b>，不接受端上传来的会员号 ——
     * 会员号可猜，收下就等于「谁都能替别人退订」。
     */
    @PutMapping("/mp/my-memberships/{entityNo}/reach")
    public void setReach(@PathVariable String entityNo, @RequestBody ReachSwitchReq req) {
        memberService.setReachOptOutByUser(SecurityUtils.currentUserNo(), entityNo,
                req.optOut());
    }

    public record ReachSwitchReq(boolean optOut) {
    }
}
