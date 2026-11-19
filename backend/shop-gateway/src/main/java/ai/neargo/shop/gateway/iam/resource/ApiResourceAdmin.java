package ai.neargo.shop.gateway.iam.resource;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.commercebridge.StoreAdmissionRoutes;
import ai.neargo.shop.gateway.core.config.GatewayProperties;
import ai.neargo.shop.gateway.core.resource.PermRuleText;
import ai.neargo.shop.gateway.core.resource.ResourceIndexes;
import ai.neargo.shop.gateway.core.resource.ResourceStatusCache;
import ai.neargo.shop.gateway.core.resource.ResourceStore;
import ai.neargo.shop.gateway.core.resource.ResourceStore.AdminQuery;
import ai.neargo.shop.gateway.core.resource.ResourceStore.AdminRow;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 「接口资源」页背后的读与改（TDD-网关层与统一权限 §2.23）。
 *
 * <p>能改的只有停用与恢复。两类行不让停：本进程里不带权限码的入口（登录、当前用户、菜单这一类，停了整端用不了）、
 * 本页自己的接口（停了没法在页面上恢复）。
 */
@Component
public class ApiResourceAdmin {

    /** 本页自己的接口都在这个前缀下。 */
    public static final String SELF_PREFIX = "/ops/api-resources";
    private static final Set<String> STATUSES = Set.of("ENABLED", "DISABLED");
    private static final Pattern CONSUMER_LOGIN = Pattern.compile("/(login|logout|otp|token|captcha|auth)(/|$)");
    /**
     * 会话外壳：登录、登出、验证码、令牌、当前用户、菜单、消息推送、免登录票据。它们不能在库里调规则 ——
     * 调成要权限码，整端就进不来了。按地址认，不按「有没有注解」认：没注解的也可能是权限写在方法体里的业务入口。
     */
    private static final Pattern SESSION_SHELL =
            Pattern.compile("/(auth|login|logout|captcha|otp|token|menu|session|stream)(/|$)|^/common/");

    /**
     * 给页面的一行；{@code locked} 为 true 表示这一行不能停用，{@code adjustable} 为 true 表示可以在库里调规则。
     * 两者不同：没有注解的入口一律不让停（扫描分不出哪条是登录），但只要不是会话外壳就可以调规则。
     */
    public record ApiResourceVO(long id, String endCode, String method, String pattern, String source,
                                String location, String targetPath, String access, String ruleText, String handler,
                                String status, boolean stale, boolean locked, String appVersion, String updatedBy,
                                LocalDateTime updatedAt, String admission, String adjustedAccess,
                                String adjustedRule, String adjustedBy, LocalDateTime adjustedAt, boolean adjustable) {
    }

    /** 条数与当前部署的两个开关；页面据此说明停用现在生不生效。 */
    public record SummaryVO(long total, long disabled, long stale, long manual, String precheck, boolean proxy) {
    }

    private final ResourceStore store;
    private final ResourceStatusCache statuses;
    private final ResourceIndexes indexes;
    private final GatewayProperties properties;
    private final AuditLogPort auditLog;

    public ApiResourceAdmin(ResourceStore store, ResourceStatusCache statuses, ResourceIndexes indexes,
                            ObjectProvider<GatewayProperties> properties, AuditLogPort auditLog) {
        this.store = store;
        this.statuses = statuses;
        this.indexes = indexes;
        // 没有 Web 层的进程（任务执行器）不装网关的安全配置，也就没有这个配置 bean；那里没有人调本类，用默认值即可。
        // 直接要求注入会让那种进程起不来 —— 而这个类只是给「接口资源」页用的
        this.properties = properties.getIfAvailable(GatewayProperties::new);
        this.auditLog = auditLog;
    }

    public PageData<ApiResourceVO> page(String endCode, String keyword, String source, String status,
                                        long page, long size) {
        long pageNo = Math.max(1, page);
        long pageSize = Math.min(200, Math.max(1, size));
        AdminQuery query = new AdminQuery(endCode, keyword, source, status);
        return PageData.of(store.page(query, pageNo, pageSize).stream().map(ApiResourceAdmin::view).toList(),
                store.count(query), pageNo, pageSize);
    }

    public SummaryVO summary() {
        var counts = store.counts();
        return new SummaryVO(counts.total(), counts.disabled(), counts.stale(), counts.manual(),
                properties.getOps().getPrecheck(), properties.getOps().isProxy());
    }

    public ApiResourceVO setStatus(long id, String status, String operator) {
        if (status == null || !STATUSES.contains(status)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        AdminRow row = store.find(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        if ("DISABLED".equals(status) && locked(row)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (!status.equals(row.status())) {
            store.setStatus(id, status, operator);
            statuses.invalidate(row.endCode());
            indexes.invalidate();
            auditLog.record("DISABLED".equals(status) ? "API_RESOURCE_DISABLED" : "API_RESOURCE_ENABLED",
                    row.method() + " " + row.pattern(),
                    ("DISABLED".equals(status) ? "停用接口 " : "恢复接口 ") + row.method() + " " + row.pattern(), true);
        }
        return view(store.find(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND)));
    }

    private static boolean locked(AdminRow row) {
        // 本进程里不带权限码的入口就是登录、验证码、当前用户、菜单这一类：扫描只知道它们「没有注解」，分不出哪条是登录
        // 入口上写了「只问是不是商家」而被归为登录即可的行带着依据（rule_text 非空），它们是业务入口，可以停用
        boolean shell = "SCAN".equals(row.source()) && !"POINT".equals(row.access()) && !"DEFER".equals(row.access())
                && (row.ruleText() == null || row.ruleText().isBlank());
        // C 端的入口都记成「留给入口判」，上面那条认不出登录；按地址把登录、验证码、令牌这几类单独锁住
        boolean consumerLogin = "mp".equals(row.endCode()) && CONSUMER_LOGIN.matcher(row.pattern()).find();
        return shell || consumerLogin || row.pattern().startsWith(SELF_PREFIX);
    }

    /** 能不能在库里调规则：只调本进程入口；会话外壳与本页自己的接口不能调。 */
    private static boolean adjustable(AdminRow row) {
        return "SCAN".equals(row.source()) && !"PUBLIC".equals(row.access()) && !row.pattern().startsWith(SELF_PREFIX)
                && !SESSION_SHELL.matcher(row.pattern()).find();
    }

    private static ApiResourceVO view(AdminRow row) {
        return new ApiResourceVO(row.id(), row.endCode(), row.method(), row.pattern(), row.source(), row.location(),
                row.targetPath(), row.access(), row.ruleText(), row.handler(), row.status(), row.stale(), locked(row),
                row.appVersion(), row.updatedBy(), row.updatedAt(), admission(row), row.adjustedAccess(),
                row.adjustedRule(), row.adjustedBy(), row.adjustedAt(), adjustable(row));
    }

    /** 库里可以把一条入口调整成的放行方式。不能调成「公开」：把一条入口变成不用登录，不该是页面上点一下的事。 */
    private static final Set<String> ADJUSTABLE = Set.of("LOGIN", "POINT", "DEFER");

    /**
     * 在库里调整一条入口的权限规则（TDD-管理范围权限搬到网关-权限码方案「规则在库里」）。
     *
     * <p>入口注解上写的规则启动时抄进库，是初始值；这里做的调整叠在上面，网关按调整后的判，
     * 业务代码不用为此改一行。{@code access} 传空表示撤销调整。
     *
     * <p>只调本进程入口的行；会话外壳（登录、验证码、当前用户、菜单等，见 {@code SESSION_SHELL}）不能调（调错了整端进不来）。
     * 没有注解、权限写在方法体里的业务入口可以调 —— 网关就是靠这一步接管它们的权限。
     * 调整只在网关提前判权开着（观察或拦截）时起作用 —— 网关关着时，生效的仍是入口上的注解。
     *
     * @param access LOGIN / POINT / DEFER；空 = 撤销
     * @param rule   权限式，写法同页面上显示的，如 {@code (a 或 b)}、{@code sv.x.read @BRAND(brandNo)}
     */
    public ApiResourceVO adjust(long id, String access, String rule, String operator) {
        AdminRow row = store.find(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        boolean clear = access == null || access.isBlank();
        if (!adjustable(row) || (!clear && !ADJUSTABLE.contains(access))) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        String text = clear || rule == null || rule.isBlank() ? null : rule.trim();
        if ("POINT".equals(access)) {
            // 式子必须读得懂；存进去的是规范写法，页面上看到的与网关判的是同一段文字
            text = PermRuleText.parse(text).map(Object::toString).orElseThrow(() -> BizException.of(ErrorCode.BAD_REQUEST));
        } else if (!clear) {
            text = null;
        }
        store.adjust(id, clear ? null : access, text, operator);
        indexes.invalidate();
        String before = row.adjustedAccess() == null ? row.access() + " " + nullToDash(row.ruleText())
                : row.adjustedAccess() + " " + nullToDash(row.adjustedRule());
        auditLog.record(clear ? "API_RESOURCE_RULE_RESET" : "API_RESOURCE_RULE_ADJUSTED", row.method() + " " + row.pattern(),
                (clear ? "撤销接口权限规则的调整 " : "调整接口权限规则 ") + row.method() + " " + row.pattern()
                        + "：" + before + " → " + (clear ? "入口注解上的规则" : access + " " + nullToDash(text)), true);
        return view(store.find(id).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND)));
    }

    private static String nullToDash(String value) {
        return value == null ? "-" : value;
    }

    /** B 端入口在门店准入表里落在哪一类；别的端没有这一步。读不出来不影响这一页，留空 */
    private static String admission(AdminRow row) {
        if (!"biz".equals(row.endCode())) {
            return null;
        }
        try {
            return StoreAdmissionRoutes.describe(row.method(), row.pattern());
        } catch (RuntimeException e) {
            return null;
        }
    }
}
