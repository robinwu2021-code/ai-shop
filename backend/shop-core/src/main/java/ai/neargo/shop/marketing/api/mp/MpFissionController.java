package ai.neargo.shop.marketing.api.mp;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.marketing.attribution.FissionService;
import ai.neargo.shop.marketing.attribution.FissionService.MyFissionVO;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C 端「邀请有礼」（TDD-C 端裂变与商家招募 §3.1）。
 *
 * <p><b>这是买家侧此前完全缺失的那一半。</b>后端这条链早就通了 ——
 * 运营端能配活动（{@code /ops/fission-campaigns}）、分享路径带着 {@code inviterNo}、
 * 新用户注册时 {@code fissionPort.onRegister} 落台账、{@code FissionOutboxConsumer}
 * 吃 {@code ORDER_CREATED} 回填首单。唯独<b>买家没有任何地方看得到「分享能得券」</b>，
 * 于是线上 {@code mkt_fission_invite} 一行都没有。
 *
 * <p><b>要登录</b>：这条端点回答的是「<b>我</b>邀到了几个」，没有「我」就没有答案。
 * 匿名时由 {@code SecurityUtils.currentUserNo()} 自己抛认证异常 ——
 * 那才是真正的 HTTP 401，而 BizException 会被统一信封裹成 200。
 */
@Profile("api")
@RestController
public class MpFissionController {

    private final FissionService fissionService;

    public MpFissionController(FissionService fissionService) {
        this.fissionService = fissionService;
    }

    /**
     * 当前在跑的活动 + 我的邀请战绩。
     *
     * <p><b>没有在跑的活动时返回 null</b>，端上据此整条入口不显示 ——
     * 不给一个点进去说「暂无活动」的入口，那比没有入口更糟。
     */
    @GetMapping("/mp/fission")
    public MyFissionVO mine() {
        return fissionService.myFission(SecurityUtils.currentUserNo()).orElse(null);
    }
}
