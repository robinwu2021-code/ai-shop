package ai.neargo.shop.portal.internal;

import ai.neargo.shop.auth.LiveIdentityResolver;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.dto.OpsVOs.StaffVO;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * OpsKit 令牌内省（RFC 7662；TDD-OpsKit审批对接 §1.2，契约 §1）：{@code POST /internal/opskit/introspect}，
 * 表单 {@code token=…}，Basic 认证（{@code shop.opskit.introspect.client-id/secret}，没配 = 一律 401）。
 *
 * <p>与 {@link OperatorIdentityEndpoint} 同源：同一会话、同一实时身份（{@link LiveIdentityResolver}）、同一 {@link OpsService#me()}，
 * 只是换成 OpsKit 认的形状 —— {@code active · sub · name · tenant · roles · perms · scopes · exp}。
 * <ul>
 *   <li>{@code otk_}（运营）：{@code tenant = PLATFORM}；{@code perms} 全量（通配原样，OpsKit 认分段通配）；
 *       {@code scopes}：全量角色或未限定 → {@code ["*"]}，否则 {@code merchant: / community: / pickup:}（契约 I2）</li>
 *   <li>{@code btk_}（商户员工）：S3 再做（一个会话跨多个商户，见 TDD §3 M1 / 契约 C12），现在一律 {@code active:false}</li>
 *   <li>失效、未知前缀、员工停用：{@code 200 {"active":false}}，不区分原因</li>
 * </ul>
 * {@code exp} 给「现在 + 5 分钟」而不是会话的 30 天滑动过期：它只是 OpsKit 缓存的上限（OpsKit 另有 60 秒上限），
 * 撤权、登出因此最多 60 秒生效；会话真实过期由下一次内省回答。
 */
@RestController
@RequestMapping("/internal/opskit")
public class OpsKitIntrospectEndpoint {

    static final long EXP_SECONDS = 300;

    private final String clientId;
    private final String clientSecret;
    private final TokenStore tokenStore;
    private final ObjectProvider<LiveIdentityResolver> identityResolver;
    private final ObjectProvider<OpsService> opsService;
    private final Clock clock;

    @Autowired
    public OpsKitIntrospectEndpoint(@Value("${shop.opskit.introspect.client-id:}") String clientId,
                                    @Value("${shop.opskit.introspect.client-secret:}") String clientSecret,
                                    TokenStore tokenStore, ObjectProvider<LiveIdentityResolver> identityResolver,
                                    ObjectProvider<OpsService> opsService) {
        this(clientId, clientSecret, tokenStore, identityResolver, opsService, Clock.systemUTC());
    }

    OpsKitIntrospectEndpoint(String clientId, String clientSecret, TokenStore tokenStore,
                             ObjectProvider<LiveIdentityResolver> identityResolver, ObjectProvider<OpsService> opsService, Clock clock) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tokenStore = tokenStore;
        this.identityResolver = identityResolver;
        this.opsService = opsService;
        this.clock = clock;
    }

    @PostMapping(value = "/introspect", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> introspect(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(value = "token", required = false) String token) {
        if (!callerAllowed(authorization)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header("WWW-Authenticate", "Basic realm=\"opskit-introspect\"").body(Map.of("error", "invalid_client"));
        }
        return ResponseEntity.ok(resolve(token).orElseGet(() -> Map.of("active", false)));
    }

    private Optional<Map<String, Object>> resolve(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        OpsService ops = opsService.getIfAvailable();
        Optional<LoginUser> session = tokenStore.get(token).map(TokenStore.SessionData::user)
                .filter(u -> u.realm() == Realm.OPERATOR);
        if (ops == null || session.isEmpty()) {
            return Optional.empty();
        }
        StaffVO staff;
        SecurityContext previous = SecurityContextHolder.getContext();
        try {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(new UsernamePasswordAuthenticationToken(live(session.get()), null, List.of()));
            SecurityContextHolder.setContext(context);
            staff = ops.me();
        } catch (RuntimeException e) {
            return Optional.empty(); // 员工被删、被停用：等同令牌无效
        } finally {
            SecurityContextHolder.setContext(previous);
        }
        if (staff.status() != null && !"ACTIVE".equals(staff.status())) {
            return Optional.empty();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("active", true);
        body.put("sub", staff.staffNo());
        body.put("name", staff.realName() != null ? staff.realName() : staff.username());
        body.put("tenant", "PLATFORM");
        body.put("roles", staff.roles() == null ? List.of() : staff.roles());
        body.put("perms", staff.perms() == null ? List.of() : staff.perms());
        body.put("scopes", scopes(staff));
        body.put("exp", clock.millis() / 1000 + EXP_SECONDS);
        return Optional.of(body);
    }

    public static List<String> scopes(StaffVO staff) {
        if (staff.perms() != null && staff.perms().contains("*")) {
            return List.of("*");
        }
        List<String> tags = new ArrayList<>();
        if (staff.merchantNo() != null && !staff.merchantNo().isBlank()) {
            tags.add("merchant:" + staff.merchantNo());
        }
        if (staff.communityNo() != null && !staff.communityNo().isBlank()) {
            tags.add("community:" + staff.communityNo());
        }
        if (staff.pickupNo() != null && !staff.pickupNo().isBlank()) {
            tags.add("pickup:" + staff.pickupNo());
        }
        return tags.isEmpty() ? List.of("*") : List.copyOf(tags);
    }

    /** 与运营端认证链同一口径：角色与范围按实时值，取不到时用会话里的。 */
    private LoginUser live(LoginUser sessionUser) {
        LiveIdentityResolver.Identity live = identityResolver.getIfAvailable(() -> LiveIdentityResolver.NONE)
                .resolve(sessionUser.userNo());
        if (live == null) {
            return sessionUser;
        }
        return sessionUser.withRolesAndScope(live.roles() != null ? live.roles() : sessionUser.roles(),
                live.scope() != null ? live.scope() : sessionUser.dataScope());
    }

    private boolean callerAllowed(String authorization) {
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()
                || authorization == null || !authorization.regionMatches(true, 0, "Basic ", 0, 6)) {
            return false;
        }
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(authorization.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return false;
        }
        String expected = clientId + ":" + clientSecret;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), decoded.getBytes(StandardCharsets.UTF_8));
    }
}
