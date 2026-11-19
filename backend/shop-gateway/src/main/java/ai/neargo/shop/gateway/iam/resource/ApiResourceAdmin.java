package ai.neargo.shop.gateway.iam.resource;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.gateway.core.config.GatewayProperties;
import ai.neargo.shop.gateway.core.resource.ResourceIndexes;
import ai.neargo.shop.gateway.core.resource.ResourceStatusCache;
import ai.neargo.shop.gateway.core.resource.ResourceStore;
import ai.neargo.shop.gateway.core.resource.ResourceStore.AdminQuery;
import ai.neargo.shop.gateway.core.resource.ResourceStore.AdminRow;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.time.LocalDateTime;
import java.util.Set;
import java.util.regex.Pattern;
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

    /** 给页面的一行；{@code locked} 为 true 表示这一行不能停用。 */
    public record ApiResourceVO(long id, String endCode, String method, String pattern, String source,
                                String location, String targetPath, String access, String ruleText, String handler,
                                String status, boolean stale, boolean locked, String appVersion, String updatedBy,
                                LocalDateTime updatedAt) {
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
                            GatewayProperties properties, AuditLogPort auditLog) {
        this.store = store;
        this.statuses = statuses;
        this.indexes = indexes;
        this.properties = properties;
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

    private static ApiResourceVO view(AdminRow row) {
        return new ApiResourceVO(row.id(), row.endCode(), row.method(), row.pattern(), row.source(), row.location(),
                row.targetPath(), row.access(), row.ruleText(), row.handler(), row.status(), row.stale(), locked(row),
                row.appVersion(), row.updatedBy(), row.updatedAt());
    }
}
