package ai.neargo.inventory.guard;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.domain.service.ServicePlatformService;
import ai.neargo.inventory.domain.service.ServicePlatformService.Authenticated;
import ai.neargo.inventory.domain.support.InvEnums;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/**
 * 服务入口守卫（TDD §2.4、执行方案 §一）。对 {@code /api/inventory/v1/**} 依次：
 *
 * <ol>
 *   <li>认凭证 {@code Authorization: Bearer <appKey>:<appSecret>} → 系统 + 业主（不信请求体）；
 *       <b>系统凭证</b>（多商家宿主，如商城）不绑业主，由请求头 {@code X-Inventory-Owner-Ref} 指定本系统的业主，
 *       且这个业主的写入方必须已翻到独立服务 —— 读也一样，过渡期里没翻的业主由宿主内嵌负责；</li>
 *   <li>本套部署放不放行这个系统（AC-28：生产两套各连各的库，配错地址不能写进别人的账）；</li>
 *   <li>写请求：这本账的写入方必须是独立服务（AC-19：过渡期内嵌与独立二选一）；</li>
 *   <li>透传操作人 {@code X-Inventory-Actor}、幂等键 {@code Idempotency-Key}、链路号 {@code X-Trace-Id}。</li>
 * </ol>
 *
 * <p>同机内网直连、不经 nginx（执行方案 §一），但内网不等于可信：每一个请求都认凭证。
 */
public class InventoryAuthFilter extends OncePerRequestFilter {

    public static final String PATH_PREFIX = "/api/inventory/v1/";
    /** 系统凭证指定业主用的请求头：值是业主在宿主里的编号（商城 = 商家号） */
    public static final String OWNER_REF = "X-Inventory-Owner-Ref";
    /** 系统级接口：宿主只有单号、没有业主时用（商城按订单号释放 / 确认），只认系统凭证 */
    public static final String SYSTEM_PREFIX = PATH_PREFIX + "system/";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS");
    private static final int MAX_HEADER = 64;

    private final ServicePlatformService platform;
    private final InstanceGuard instanceGuard;

    public InventoryAuthFilter(ServicePlatformService platform, InstanceGuard instanceGuard) {
        this.platform = platform;
        this.instanceGuard = instanceGuard;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            InventoryPrincipal principal = authenticate(request);
            instanceGuard.check(principal);
            if (!SAFE.contains(request.getMethod()) && principal.ownerId() != null) {
                InvOwner owner = platform.owner(principal.ownerId());
                if (!InvEnums.WriteAuthority.SVC.equals(owner.getWriteAuthority())) {
                    throw InventoryException.of(InventoryError.NOT_WRITER, owner.getWriteAuthority());
                }
            }
            String trace = header(request, "X-Trace-Id");
            InventoryRequestContext.set(principal, header(request, "Idempotency-Key"),
                    trace == null ? UUID.randomUUID().toString() : trace);
        } catch (InventoryException e) {
            ErrorBodies.write(response, e);
            return;
        }
        try {
            chain.doFilter(request, response);
        } finally {
            InventoryRequestContext.clear();
        }
    }

    private InventoryPrincipal authenticate(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.startsWith("Bearer ")) {
            throw InventoryException.of(InventoryError.UNAUTHORIZED);
        }
        String token = auth.substring("Bearer ".length()).strip();
        int colon = token.indexOf(':');
        if (colon <= 0 || colon == token.length() - 1) {
            throw InventoryException.of(InventoryError.UNAUTHORIZED);
        }
        Authenticated who = platform.authenticate(token.substring(0, colon), token.substring(colon + 1));
        String ownerId = who.ownerId();
        boolean systemPath = request.getRequestURI().startsWith(SYSTEM_PREFIX);
        if (systemPath) {
            // 系统级接口（按单号跨业主）：只认系统凭证，且不指定业主；动作只碰本系统、写入方为 SVC 的业主
            if (ownerId != null) {
                throw InventoryException.of(InventoryError.FORBIDDEN, "system_credential_required");
            }
            return new InventoryPrincipal(who.systemCode(), null, header(request, "X-Inventory-Actor"));
        }
        if (ownerId == null) {
            // 系统凭证：业主由请求头指定，限本系统、限写入方已是独立服务的业主
            InvOwner owner = platform.ownerByRef(who.systemCode(), header(request, OWNER_REF));
            if (owner == null) {
                throw InventoryException.of(InventoryError.NOT_FOUND, "owner");
            }
            if (!InvEnums.WriteAuthority.SVC.equals(owner.getWriteAuthority())) {
                throw InventoryException.of(InventoryError.NOT_WRITER, owner.getWriteAuthority());
            }
            ownerId = owner.getOwnerId();
        }
        return new InventoryPrincipal(who.systemCode(), ownerId, header(request, "X-Inventory-Actor"));
    }

    /** 头超长一律当作没给：审计列宽 64，截断的操作人比没有操作人更误导。 */
    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            return null;
        }
        String v = value.strip();
        if (v.length() > MAX_HEADER) {
            throw InventoryException.of(InventoryError.BAD_REQUEST, name);
        }
        return v;
    }
}
