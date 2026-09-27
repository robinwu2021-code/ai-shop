package ai.neargo.shop.portal.common;

import ai.neargo.shop.auth.LoginAuditor;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.spi.user.UserQueryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 密钥票据换会话（ADR-027，TDD-密钥票据免登录）。给自动化测试用：模拟器上的 App、本机脚本。
 *
 * <p><b>为什么挂在 {@code /common}</b>：它要在「还没有任何会话」时被调用（与登录接口同一处境），
 * 而 {@code /common/**} 本来就是匿名链；一个入口同时签店主（{@code btk_}）与运营（{@code otk_}），
 * 不为运营另开一个没有页面入口的 {@code /ops} 端点。
 *
 * <p>签出的会话与正常登录<b>同构</b>：店主走与 {@code /biz/auth/login} 相同的 {@code merchantByUser}，
 * 运营走 {@link OpsService#issueSessionFor}（与密码登录共用签发那一段）。权限不多一分。
 */
@RestController
/*
 * 关着时<b>整个控制器不注册</b>，接口就是不存在 —— 与任何不存在的路径一样回 404。
 * 在方法里抛 404 不行：全局兜底会把它包成「200 + 服务器错误」，反而告诉探测者「这里有东西」。
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "shop.auth.automation.enabled", havingValue = "true")
public class AutomationLoginController {

    private static final Logger log = LoggerFactory.getLogger(AutomationLoginController.class);

    private final AutomationTicketVerifier verifier;
    private final TokenStore tokenStore;
    private final UserQueryPort userQuery;
    private final OpsService opsService;
    private final LoginAuditor auditor;

    public AutomationLoginController(AutomationTicketVerifier verifier, TokenStore tokenStore,
                                     UserQueryPort userQuery, OpsService opsService, LoginAuditor auditor) {
        this.verifier = verifier;
        this.tokenStore = tokenStore;
        this.userQuery = userQuery;
        this.opsService = opsService;
        this.auditor = auditor;
    }

    public record TicketReq(String ticket) {
    }

    /** {@code realm}：B 或 OPS；{@code token}：与对应端正常登录返回的令牌同一种 */
    public record SessionResp(String realm, String subject, String token) {
    }

    @PostMapping("/common/auth/automation")
    public SessionResp exchange(@RequestBody TicketReq req) {
        AutomationTicketVerifier.Verified v;
        try {
            v = verifier.verify(req == null ? null : req.ticket());
        } catch (AutomationTicketVerifier.Rejected r) {
            auditor.failed(Realm.MERCHANT, "automation", r.code());
            log.warn("[automation-login] 拒绝 · {}", r.code());
            throw BizException.of(ErrorCode.UNAUTHORIZED);
        }

        String token;
        if (v.realm() == AutomationTicketVerifier.TicketRealm.B) {
            UserQueryPort.UserBrief user = userQuery.find(v.subject()).orElse(null);
            if (user == null) {
                // 白名单里写了一个不存在的账号：配置错，要在日志里说清楚，别让人对着「登录失败」猜
                auditor.failed(Realm.MERCHANT, "automation", "AUTOMATION_NO_SUCH_USER");
                log.warn("[automation-login] 拒绝 · 白名单里的账号不存在：{}", v.subject());
                throw BizException.of(ErrorCode.UNAUTHORIZED);
            }
            token = tokenStore.issue(TokenStore.SessionData.of(
                    LoginUser.merchantByUser(user.userNo(), user.nickname())));
            auditor.succeeded(Realm.MERCHANT, user.userNo());
        } else {
            token = opsService.issueSessionFor(v.subject()).token();
            auditor.succeeded(Realm.OPERATOR, v.subject());
        }
        // WARN 级：这条路每用一次都该在日志里显眼 —— 它不是真人登录
        log.warn("[automation-login] 签发 realm={} subject={}", v.realm(), v.subject());
        return new SessionResp(v.realm().name(), v.subject(), token);
    }
}
