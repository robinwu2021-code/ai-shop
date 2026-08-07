package ai.neargo.shop.auth;

/**
 * 数据域维度码（TDD-backend §5.3）。作为 {@code DataScopeSpec} 的 {@code dim} 与
 * {@code DataScopeTableRegistry} 的锚点键，两处必须用同一套常量 —— 拼错字符串不会报错，
 * 只会静默地让 SQL 拼出 {@code 1=0} 或者干脆不过滤，这是最难查的一类越权。
 */
public final class ScopeDim {

    /** 属主：C 端「我的」一切。 */
    public static final String SELF = "SELF";

    /** 我卖的货与钱。 */
    public static final String MERCHANT = "MERCHANT";

    /** 我要核销的货（含别家商家的商品，响应需字段级裁剪）。 */
    public static final String PICKUP = "PICKUP";

    /** 我发起的团（邻里自提，零报酬，作用域单团）。 */
    public static final String GROUP = "GROUP";

    /** 社区（运营端按社区授权）。 */
    public static final String COMMUNITY = "COMMUNITY";

    private ScopeDim() {
    }
}
