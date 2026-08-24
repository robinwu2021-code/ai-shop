package ai.neargo.shop.spi.user;

/**
 * 微信「手机号快速验证」（{@code phonenumber.getPhoneNumber}）。
 *
 * <p><b>它不是免费的，也不是随时可用的</b>：要小程序已认证（300 元/年）、
 * 非个人主体，且**按次计费**。所以它只能是「可用就走的快路」，
 * 不能是唯一入口 —— 验证码那条路必须一直在（见 TDD-手机号授权与自动登录 §3.1）。
 */
public interface WxPhonePort {

    /**
     * 用端上 {@code getPhoneNumber} 回调里的 code 换手机号。
     *
     * @return 手机号；**换不到时返回 null，不返回占位号码** ——
     *         假号码会被当成真手机号写进账号，比「没拿到」贵得多
     */
    String phoneOf(String code);

    /** 这条通道当前可不可用。端上据此决定显示一键按钮还是验证码表单 */
    boolean enabled();
}
