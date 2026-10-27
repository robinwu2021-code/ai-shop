package ai.neargo.shop.pay.api.guard.core;

import ai.neargo.shop.pay.api.guard.PortalFace;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * 一个入口的鉴权：从请求里认出「谁在调」。每个 {@link PortalFace} 恰好一个实现（I5 / I7 落地）。
 *
 * <p>以接口隔离是为了两种装配（ADR-044 §2-4）：内嵌形态复用主应用的运营 / 商家会话；
 * 独立形态经统一鉴权的内省接口校验令牌。换形态只换实现，controller 一行不改。
 *
 * <p>返回空 = 未认出，过滤器回 401 并记 {@code REJECTED_AUTH}，请求**不进** controller。
 */
public interface PortalAuthenticator {

    PortalFace face();

    Optional<PortalPrincipal> authenticate(HttpServletRequest request);
}
