package ai.neargo.shop.portal.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.member.service.MemberReachService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 买家点了商家发来的推送、进了店（C 端，会员标签与定向营销 批 C）。
 *
 * <p><b>为什么不挂在 {@code /mp/store/{no}/enter} 上</b>（TDD 原稿如此）：
 * enter 会顺带上报一次店铺归因（{@code attributionService.report}），
 * 而「点了老店的推送回来」不是被谁带来的新客 —— 挂在那儿会把商家自己的会员
 * 记成一次渠道归因。两件事分开，各记各的。
 */
@Profile("api")
@RestController
public class MpMemberReachController {

    private final MemberReachService reachService;

    public MpMemberReachController(MemberReachService reachService) {
        this.reachService = reachService;
    }

    /**
     * @return 这一下有没有计入。<b>对不上本人、过了窗口、已记过都是 false</b>，
     *         不区分是哪一种 —— 端上也不该据此提示任何东西
     */
    @PostMapping("/mp/member-reach/{reachNo}/opened")
    public ReachOpenedVO opened(@PathVariable String reachNo) {
        return new ReachOpenedVO(reachService.opened(reachNo, SecurityUtils.currentUserNo()));
    }

    public record ReachOpenedVO(boolean counted) {
    }
}
