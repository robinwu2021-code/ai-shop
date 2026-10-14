package ai.neargo.shop.spi.marketing;

/**
 * user → marketing：**新用户注册时记一次邀请**。
 *
 * <p>调用时机只有一个 —— <b>新账号创建成功那一刻</b>。老用户再次登录不算邀请，
 * 否则同一个人可以靠反复登录把邀请数刷上去。
 *
 * <p><b>这条链路此前是断的</b>：`LoginReq` 一直带着 `inviterNo`，
 * 端上也一直在传，而 `AuthServiceImpl` **一次都没用过它** —— 参数被接收后直接丢掉。
 * 于是裂变台账 `mkt_fission_invite` 一行都没有，运营端「邀请有礼」的
 * 累计邀请 / 完成首单两列恒为 0。
 */
public interface FissionPort {

    /**
     * 记一次邀请。**失败不打断注册** —— 实现里吞掉异常：
     * 让一次营销统计失败去挡住用户注册，代价方向完全反了。
     *
     * @param inviterNo 邀请人 userNo；为空表示自然注册，直接返回
     * @param inviteeNo 刚创建的新用户
     * @param deviceId  设备号，新客因子 DEVICE 用；没有传 null
     * @param phoneTail 手机号**后四位**，因子 PHONE 用；完整号码不要传进来（B12）
     */
    void onRegister(String inviterNo, String inviteeNo, String deviceId, String phoneTail);
}
