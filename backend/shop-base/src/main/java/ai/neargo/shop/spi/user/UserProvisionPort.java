package ai.neargo.shop.spi.user;

/**
 * trade → user：<b>按手机号确保有一个账号</b>。
 *
 * <p>只为一件事而存在：代客下单（P-4.1.4）要让「没装过 App 的人」也能电话下单。
 * 客服替他下的单必须落在一个真实账号上 —— 否则那张单没有主人：
 * 他看不到、付不了、也退不了，而运营端界面上一切正常。
 *
 * <p><b>与登录建户是同一条路</b>（{@code AuthService#ensureAccountByPhone} 走
 * {@code findOrCreate}），所以他日后用这个手机号登录时命中的是同一个账号，
 * 那张单自然出现在他的订单列表里 —— <b>「认领」不需要任何额外动作</b>。
 *
 * <p><b>手机号只进不出</b>：这里收完整号，返回只有 userNo。
 * 要展示一律用后四位（B12：完整号码永远不出 {@link UserQueryPort}）。
 */
public interface UserProvisionPort {

    /**
     * @param phone 完整手机号（11 位）
     * @return 该手机号对应的 userNo；没有账号时**新建一个**
     */
    String ensureUserByPhone(String phone);
}
