package ai.neargo.shop.spi.notify;

/**
 * 一次发送是**为了什么**。
 *
 * <p>放在 spi 而不是 message 域的实体上：调用方（user / platform / merchant 域）
 * 要传这个值，而它们**不该依赖 message 域** —— 依赖了，两个域就再也拆不开，
 * 而这正是架构守卫拦下的那条（`platform 不得依赖 message`）。
 *
 * <p>取值与 {@code sys_notify_log.biz_type} 一一对应。加一种用途要同时改两处，
 * 而两处都在这份契约的可见范围内。
 */
public final class NotifyBizType {

    private NotifyBizType() {
    }

    /** 登录验证码。系统自动发出，没有操作人 */
    public static final String OTP = "OTP";

    /** 运营账号的一次性初始密码 */
    public static final String OPS_INIT_PASSWORD = "OPS_INIT_PASSWORD";

    /** 运营账号的密码重置码 */
    public static final String OPS_RESET_PASSWORD = "OPS_RESET_PASSWORD";

    /** 运营端页面上手动触发的测试发送 */
    public static final String TEST = "TEST";

    /** 交易关键节点的用户触达（到货、退款）。事件驱动，没有操作人 */
    public static final String TRADE_NOTIFY = "TRADE_NOTIFY";
}
