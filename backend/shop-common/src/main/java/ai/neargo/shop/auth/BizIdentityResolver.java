package ai.neargo.shop.auth;

/**
 * 「这个用户在经营侧是谁」的解析 SPI。实现方在 {@code shop-svc-user}（查商家、自提点、我发起的团），
 * common 只留接口 —— 否则 common 就要依赖业务表，横切层立刻变成业务层。
 *
 * <p>S0 阶段由 {@link #NONE} 兜底：所有 {@code /biz/**} 请求拿到空作用域，即<b>全部 403</b>。
 * 这是刻意的 fail-closed：宁可 B 端暂时用不了，也不要因为解析器没接上而默认放行。
 */
@FunctionalInterface
public interface BizIdentityResolver {

    BizIdentityResolver NONE = userNo -> BizContext.NONE;

    BizContext resolve(String userNo);
}
