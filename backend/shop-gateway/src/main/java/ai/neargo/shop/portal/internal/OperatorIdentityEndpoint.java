package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.LiveIdentityResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.dto.OpsVOs.StaffVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 统一认证接口（TDD-支付运营端经网关转发与权限下沉 §2.2）：微服务拿终端的运营端令牌来换「这个人是谁、有哪些权限码」，权限由微服务自己判。
 *
 * <p>与页面接口 {@code /ops/auth/me} 同源：同样按 {@link LiveIdentityResolver} 取实时角色与范围（与运营端认证链一致），
 * 再调同一个 {@link OpsService#me()}，所以两个接口给同一个人的结论逐字段一致；这里只多两件事 ——
 * 校验调用方的进程凭据、按 {@code X-Auth-Namespace} 只回该前缀的权限码（支付只要 {@code pay:}，控制体积）。
 *
 * <p>放在 {@code portal.internal}：{@code /internal} 端点只许在这个包（CommonPackageIsolationTest）。{@code /internal/**} 不进安全链、不套统一包体（{@code ApiResponseWrapper} 按路径排除），nginx 对外一律 deny；
 * 所以凭据在这里自己比：{@code shop.gateway.ops.identity.caller-token}，常数时间比对，<b>没配 = 一律 403</b>（不会裸奔）。
 * 返回体沿用 near-pay 已在解析的 {@code {code,data}}，字段是 {@code /ops/auth/me} 的超集。
 */
@RestController
@RequestMapping("/internal/auth/v1")
public class OperatorIdentityEndpoint {

    private final String callerToken;
    private final TokenStore tokenStore;
    private final ObjectProvider<LiveIdentityResolver> identityResolver;
    private final ObjectProvider<OpsService> opsService;

    public OperatorIdentityEndpoint(@Value("${shop.gateway.ops.identity.caller-token:}") String callerToken,
                                    TokenStore tokenStore, ObjectProvider<LiveIdentityResolver> identityResolver,
                                    ObjectProvider<OpsService> opsService) {
        this.callerToken = callerToken;
        this.tokenStore = tokenStore;
        this.identityResolver = identityResolver;
        this.opsService = opsService;
    }

    @GetMapping("/operator")
    public ResponseEntity<Map<String, Object>> operator(
            @RequestHeader(value = "X-Internal-Token", required = false) String given,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Auth-Namespace", required = false) String namespace) {
        if (!callerAllowed(given)) {
            return fail(HttpStatus.FORBIDDEN, 40300, "CALLER_NOT_ALLOWED");
        }
        OpsService ops = opsService.getIfAvailable();
        if (ops == null) {
            return fail(HttpStatus.SERVICE_UNAVAILABLE, 50300, "OPS_END_NOT_ASSEMBLED");
        }
        Optional<LoginUser> session = bearer(authorization).flatMap(tokenStore::get).map(TokenStore.SessionData::user)
                .filter(u -> u.realm() == Realm.OPERATOR);
        if (session.isEmpty()) {
            return fail(HttpStatus.UNAUTHORIZED, 40100, "NO_OPERATOR_SESSION");
        }
        LoginUser user = live(session.get());
        StaffVO staff;
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
            SecurityContextHolder.setContext(context);
            staff = ops.me();
        } catch (RuntimeException e) {
            // 员工被删、被停用：等同会话无效
            return fail(HttpStatus.UNAUTHORIZED, 40100, "NO_OPERATOR_SESSION");
        } finally {
            SecurityContextHolder.setContext(previous);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("staffNo", staff.staffNo());
        data.put("username", staff.username());
        data.put("realName", staff.realName());
        data.put("status", staff.status());
        data.put("roles", staff.roles());
        data.put("perms", filter(staff.perms(), namespace));
        data.put("merchantNo", staff.merchantNo());
        data.put("communityNo", staff.communityNo());
        data.put("pickupNo", staff.pickupNo());
        data.put("mustChangePassword", staff.mustChangePassword());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("message", "OK");
        body.put("data", data);
        return ResponseEntity.ok(body);
    }

    /** 与运营端认证链同一口径：角色与范围按实时值，取不到时用会话里的 */
    private LoginUser live(LoginUser sessionUser) {
        LiveIdentityResolver.Identity live = identityResolver.getIfAvailable(() -> LiveIdentityResolver.NONE)
                .resolve(sessionUser.userNo());
        if (live == null) {
            return sessionUser;
        }
        return sessionUser.withRolesAndScope(live.roles() != null ? live.roles() : sessionUser.roles(),
                live.scope() != null ? live.scope() : sessionUser.dataScope());
    }

    /** 只回该前缀的码；超管的 {@code *} 原样保留。不带前缀头时回全部 */
    public static List<String> filter(List<String> perms, String namespace) {
        if (perms == null) {
            return List.of();
        }
        if (namespace == null || namespace.isBlank()) {
            return List.copyOf(perms);
        }
        return perms.stream().filter(p -> "*".equals(p) || p.startsWith(namespace)).toList();
    }

    private boolean callerAllowed(String given) {
        if (callerToken == null || callerToken.isBlank() || given == null) {
            return false;
        }
        return MessageDigest.isEqual(callerToken.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<String> bearer(String authorization) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = authorization.substring(7).trim();
        return token.isEmpty() ? Optional.empty() : Optional.of(token);
    }

    private static ResponseEntity<Map<String, Object>> fail(HttpStatus status, int code, String reasonCode) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("message", reasonCode);
        body.put("reasonCode", reasonCode);
        body.put("data", null);
        return ResponseEntity.status(status).body(body);
    }
}
