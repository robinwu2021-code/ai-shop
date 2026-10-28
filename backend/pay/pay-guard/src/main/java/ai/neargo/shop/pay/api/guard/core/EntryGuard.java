package ai.neargo.shop.pay.api.guard.core;

import ai.neargo.shop.pay.api.guard.PortalFace;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 一个入口的差异（ADR-045；TDD-支付服务-接入守卫模块pay-guard §2.2）：管线 {@link GuardFilter} 本身不分入口，
 * 入口只回答「凭证怎么读、怎么验」，以及要不要解密字段、要不要签响应。每个 {@link PortalFace} 恰好一个实现。
 *
 * <p>以接口隔离是为了两种装配（ADR-044 §2-4）：内嵌形态复用主应用的运营 / 商家会话；独立形态经统一鉴权校验令牌。
 * 换形态只换实现，controller 一行不改。
 */
public interface EntryGuard {

    PortalFace entry();

    /**
     * 管线 ④–⑧：认出并验证调用方（按入口：签名、时间窗、nonce、IP、授权范围、会话）。
     * 失败给出原因 —— 只进请求记录与日志，<b>不回给调用方</b>；请求不会进 controller。
     *
     * @param request 已缓存请求体的请求（{@link CachedBodyRequest}）
     */
    GuardResult authenticate(HttpServletRequest request);

    /** 管线 ⑨：敏感字段解密（明文只活在本次请求）。默认不解密；回绝时返回结果（明文、解不开 → 400），放行返回 null */
    default GuardResult decryptSensitiveFields(HttpServletRequest request, PortalPrincipal who) {
        return null;
    }

    /** 管线 ⑪ 的开关：返回 true 时管线缓存响应体并调 {@link #signResponse}；默认不签，响应不被包装 */
    default boolean signsResponses() {
        return false;
    }

    /**
     * 管线 ⑪：给响应加签名头。在响应体写回调用方<b>之前</b>调用。
     *
     * @param body 完整响应体
     * @param who  认出的调用方；回绝时为空（据请求头找调用方，开发计划 M3.4）
     */
    default void signResponse(HttpServletRequest request, HttpServletResponse response, byte[] body, PortalPrincipal who) {
    }
}
