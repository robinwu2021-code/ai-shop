package ai.neargo.shop.auth;

/**
 * 「这个用户在经营侧是谁」的解析 SPI。实现方在 {@code shop-svc-user}（查商家、自提点、我发起的团），
 * common 只留接口 —— 否则 common 就要依赖业务表，横切层立刻变成业务层。
 *
 * <p>S0 阶段由 {@link #NONE} 兜底：所有 {@code /biz/**} 请求拿到空作用域，即<b>全部 403</b>。
 * 这是刻意的 fail-closed：宁可 B 端暂时用不了，也不要因为解析器没接上而默认放行。
 *
 * <h2>为什么 {@code storeNo} 是解析的<b>入参</b>，而不是解析完再套上去</h2>
 *
 * <p>一个账号可以名下有多张营业执照（多主体）。「他现在是哪个主体」这件事，
 * 答案只能来自「他现在站在哪家门店里」—— 而门店号是端上带来的
 * （{@link BizContextFilter#STORE_HEADER}）。
 *
 * <p>M1~M5 期间解析器固定取默认主体，切门店只在解析<b>之后</b>把 {@code currentStoreNo}
 * 换掉（{@link BizContext#withStore}）。单主体时这没问题；一旦一个人有两张执照，
 * 它就错得很安静：进 B 主体的店，{@code merchantNo} 还停在 A ——
 * 权限、商品库、订单全按 A 主体算，页面照常打开，只是<b>数据是另一家的</b>。
 *
 * <p>所以必须把门店号提前到这一步，让解析器「按店反查主体」。
 *
 * <h2>安全边界</h2>
 *
 * <p>{@code storeNo} 是<b>客户端可控的请求头</b>，因此实现必须满足：传进来一个不属于
 * 自己的门店号（哪怕它真实存在），结果<b>只能</b>回落到自己的默认主体，
 * 绝不能解析成那家店的主体。这是整条多主体改造里唯一有越权面的地方。
 */
public interface BizIdentityResolver {

    BizIdentityResolver NONE = (userNo, storeNo) -> BizContext.NONE;

    /**
     * @param storeNo 端上声明的「我现在在哪家店」，可为 null（用默认主体的默认店）。
     *                <b>不可信</b>：不属于本人的门店号必须被忽略，见类注释「安全边界」。
     */
    BizContext resolve(String userNo, String storeNo);

    /**
     * 没有门店上下文时的解析 —— 登录接口用（那一刻请求上还没有 {@code X-Store-No}）。
     */
    default BizContext resolve(String userNo) {
        return resolve(userNo, null);
    }
}
