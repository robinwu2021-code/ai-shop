package ai.neargo.shop.auth;

/**
 * 凭据池。**只有两个**：C 端消费者与平台运营。
 *
 * <p>B 端（商家/自提点/团发起人）刻意<b>不单独立池</b> —— 一期商家专区内嵌 C 端小程序（ADR-001），
 * 店主用的就是自己那个微信号，再发一套商家 token 意味着同一个人要登录两次。
 * B 端的差别是<b>数据可见性</b>，由 {@link BizContext} + DataScope 表达，不是身份池的差别。
 */
public enum Realm {

    /** C 端与 B 端共用（token 前缀 {@code ctk_}）。 */
    CONSUMER,

    /** ops-web 运营端（token 前缀 {@code otk_}）。 */
    OPERATOR
}
