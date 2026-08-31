package ai.neargo.shop.auth;

/**
 * 凭据池。**三端各一个，互不通用。**
 *
 * <p>令牌前缀即池标识（{@code ctk_} / {@code btk_} / {@code otk_}）。
 * 前缀不符时过滤器在第一行就 401，**不查库** —— 这是端隔离的第一道，也是最便宜的一道。
 *
 * <h2>为什么曾经只有两个</h2>
 * ADR-001 一期把商家端做成「C 端小程序里的商家专区」，店主用自己的微信号，
 * 再发一套商家令牌意味着同一个人要登录两次 —— 于是 C 与 B 共用 {@code ctk_}。
 *
 * <p><b>那个前提在 2026-08-11 随 ADR-014 作废</b>（商家端已是独立的 {@code b-app}），
 * 共用令牌池随之在 {@code TDD-三端服务拆分} §5 被推翻。
 *
 * <p>留着「只有两个」的旧注释是有代价的：它读起来像一个**仍然成立的决策**，
 * 而不是一段已作废的历史 —— 后来者据此会得出「B 端共池是有意为之」的结论，
 * 然后绕开它去解决别的问题。
 *
 * <h2>共用曾经掩盖的问题</h2>
 * {@code LoginUser.userNo} 这一个字段里，C 端塞 {@code usr_account.user_no}、
 * B 端塞 {@code mch_account.mch_account_no}（{@code MerchantStaffServiceImpl} 签发的是
 * {@code LoginUser.consumer(mchAccountNo)}）。生产上号段恰好不撞
 * （{@code U2026…} vs {@code SF-…}），<b>但那是约定不是结构保证</b>：
 * 任一端改了发号规则，撞上的表现是「拿商家的令牌读到某个消费者的数据」，
 * 而它不会以报错的形式出现。分池之后，这种撞车在结构上不可能发生。
 */
public enum Realm {

    /** C 端消费者（{@code ctk_}），链 {@code /mp/**}。 */
    CONSUMER,

    /**
     * B 端商家（{@code btk_}），链 {@code /biz/**}。
     *
     * <p>老板可以用 C 端账号**校验身份**（入驻是从 C 端发起的），但签发的是 {@code btk_}
     * —— <b>校验源与令牌池是两件事</b>，分开之后两者才能各自演进。
     */
    MERCHANT,

    /** 平台运营（{@code otk_}），链 {@code /ops/**}。 */
    OPERATOR;

    /** 本池的令牌前缀。**唯一的真源** —— 别在别处再写一遍字面量。 */
    public String tokenPrefix() {
        return switch (this) {
            case CONSUMER -> "ctk_";
            case MERCHANT -> "btk_";
            case OPERATOR -> "otk_";
        };
    }

    /** 令牌属于哪个池；前缀不认识时为 {@code null}（调用方直接 401，不必查库）。 */
    public static Realm ofToken(String token) {
        if (token == null) {
            return null;
        }
        for (Realm r : values()) {
            if (token.startsWith(r.tokenPrefix())) {
                return r;
            }
        }
        return null;
    }
}
