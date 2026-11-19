package ai.neargo.shop.gateway.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.gateway.core.security.AbstractAccessDecider;
import ai.neargo.shop.gateway.core.security.Decision;
import ai.neargo.shop.spi.merchant.ManagementAuthorityPort;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;
import org.springframework.web.util.pattern.PathPatternParser;
import ai.neargo.shop.gateway.core.resource.ApiResource;
import ai.neargo.shop.gateway.core.resource.PermRule;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.ManagementScopeRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.PathContainer;

/**
 * B 端：按<b>当前门店</b>上持有的角色判（TDD-网关层与统一权限 §2.14；开发计划 D3）。
 *
 * <p>与方法注解里的 {@code @perm.canBiz} 问的是同一份上下文：{@code BizContextFilter} 排在本判定之前，
 * 已经按 {@code X-Store-No} 把这个人在这家店的角色解析好了。这里只回答「他能不能做这个动作」；
 * 「他能碰哪家店的数据」仍由各 Service 裁，网关不碰。
 *
 * <p>被拒时给的错误也与 {@code PermChecker#canBiz} 相同：不是商家 → 通用的「没有操作权限」；
 * 是商家但角色不够 → 70006，并说出缺的是哪一样（权限式里按书写顺序第一个缺的）。
 */
public class BizAccessDecider extends AbstractAccessDecider {

    private static final Logger log = LoggerFactory.getLogger(BizAccessDecider.class);
    private static final PathPatternParser PATTERNS =
            new PathPatternParser();

    private final Supplier<ManagementAuthorityPort> management;

    public BizAccessDecider() {
        this(() -> null);
    }

    /** @param management 管理范围的授权出口；商户模块没装时给出 null，带范围的码一律不成立 */
    public BizAccessDecider(Supplier<ManagementAuthorityPort> management) {
        this.management = management;
    }

    /**
     * 带范围的码：问管理范围的授权（TDD-管理范围权限搬到网关-权限码方案）。取法与入口上的
     * {@code @perm.canManage} 完全相同 —— 同一个请求，网关与注解不会得出不同的结论。
     */
    @Override
    protected boolean hasScoped(ApiResource resource,
                                PermRule node) {
        var user = SecurityUtils.currentUser().orElse(null);
        String actorKey = ManagementScopeRequest.actorKey(user);
        if (actorKey == null) {
            return false;
        }
        var request = ManagementScopeRequest.current();
        String managementNo = ManagementScopeRequest.managementNo(request);
        if (managementNo == null) {
            // 过渡规则：管理空间号在请求体里，这一层读不到 —— 不判，留给入口上的注解与服务
            log.info("带范围的码未判：请求上没有管理空间号 {} {} · {}", request == null ? "?" : request.getMethod(),
                    resource.pattern(), node.code());
            return true;
        }
        var port = management.get();
        if (port == null) {
            return false;
        }
        if (node.scopeParam() == null) {
            return port.canAny(user.tenantNo(), actorKey, managementNo, node.scopeType(), node.code());
        }
        String resourceNo = scopeValue(request, resource.pattern(), node.scopeParam());
        return resourceNo != null
                && port.can(user.tenantNo(), actorKey, managementNo, node.scopeType(), resourceNo, node.code());
    }

    /** 资源号：先按地址模式取同名的路径变量，没有再取同名的查询参数。不读请求体。 */
    static String scopeValue(HttpServletRequest request, String pattern, String name) {
        if (request == null) {
            return null;
        }
        String path = request.getRequestURI().substring(request.getContextPath().length());
        var matched = PATTERNS.parse(pattern)
                .matchAndExtract(PathContainer.parsePath(path));
        String value = matched == null ? null : matched.getUriVariables().get(name);
        if (value == null || value.isBlank()) {
            value = request.getParameter(name);
        }
        return value == null || value.isBlank() ? null : value;
    }

    @Override
    protected boolean has(String code) {
        return merchant() && BizContext.current().can(code);
    }

    @Override
    public BizException denial(Decision decision) {
        if (!merchant() || decision.missing().isEmpty()) {
            return BizException.of(ErrorCode.FORBIDDEN);
        }
        return BizException.of(ErrorCode.BIZ_ROLE_FORBIDDEN, BizPerms.labelOf(decision.missing().get(0)));
    }

    private static boolean merchant() {
        String merchantNo = BizContext.current().merchantNo();
        return merchantNo != null && !merchantNo.isBlank();
    }
}
