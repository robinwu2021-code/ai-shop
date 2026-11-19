package ai.neargo.shop.auth;

import ai.neargo.shop.spi.merchant.ManagementAuthorityPort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 从当前请求上取「管理范围」判权要用的三样东西：操作人、管理空间号、资源号
 * （TDD-管理范围权限搬到网关-权限码方案）。入口上的注解与网关用的是同一套取法，所以两边不会对同一个请求得出不同的范围。
 */
public final class ManagementScopeRequest {

    private ManagementScopeRequest() {
    }

    /** 当前请求；不在请求线程里（定时任务、消息消费）返回 null。 */
    public static HttpServletRequest current() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }

    /**
     * 管理空间号：先看请求头 {@code X-Management-No}，再看查询参数 {@code managementNo}。<b>不读请求体</b>。
     * 两处都没有返回 null —— 调用方按「这一层不判，留给服务」处理（过渡规则，见方案「管理空间号从哪取」）。
     */
    public static String managementNo(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String header = request.getHeader(ManagementAuthorityPort.HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String param = request.getParameter(ManagementAuthorityPort.PARAM);
        return param == null || param.isBlank() ? null : param.trim();
    }

    /** 管理范围里的操作人标识，与 {@code ManagementAccess#actor} 的规则相同；不是商家身份返回 null。 */
    public static String actorKey(LoginUser user) {
        if (user == null || user.realm() != Realm.MERCHANT || user.subjectKind() == null) {
            return null;
        }
        String kind = user.subjectKind().name();
        if (kind.equals("MCH")) {
            return "MCH:" + user.userNo();
        }
        return kind.equals("USR") ? "USER:" + user.userNo() : null;
    }
}
