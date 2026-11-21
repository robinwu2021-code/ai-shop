package ai.neargo.shop.gateway.biz;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** 从当前请求上取「管理范围」判权要用的东西：操作人与管理空间号。只有网关用它。 */
final class ManagementScopeRequest {

    private ManagementScopeRequest() {
    }

    /** 当前请求；不在请求线程里返回 null。 */
    static HttpServletRequest current() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }

    /**
     * 管理空间号：先看请求头 {@code X-Management-No}，再看查询参数 {@code managementNo}。<b>不读请求体</b>。
     * 两处都没有返回 null —— 网关这一条不判，留给业务（过渡规则）。
     */
    static String managementNo(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String header = request.getHeader(ManagementAuthority.HEADER);
        if (header != null && !header.isBlank()) {
            return header.trim();
        }
        String param = request.getParameter(ManagementAuthority.PARAM);
        return param == null || param.isBlank() ? null : param.trim();
    }

    /** 管理范围里的操作人标识，与业务侧 {@code ManagementAccess#actor} 的规则相同；不是商家身份返回 null。 */
    static String actorKey(LoginUser user) {
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
