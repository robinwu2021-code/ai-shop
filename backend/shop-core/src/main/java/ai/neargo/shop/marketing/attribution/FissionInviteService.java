package ai.neargo.shop.marketing.attribution;

/**
 * 裂变邀请台账：**落行 → 判新客 → 发奖 → 首单回填**。
 *
 * <p>与 {@link FissionService} 分开：那一层管**活动本身**（建、改、启停），
 * 这一层管**一次具体的邀请**。混在一起的话，运营端的写操作会和 C 端触发的
 * 写入共用一个入口 —— 而后端注释里明确写着运营不能手工补发奖，
 * 因为那会绕开幂等键 {@code uk_fission_invitee}。
 */
public interface FissionInviteService {

    /**
     * 记一次邀请。**在被邀请人注册落地那一刻调**。
     *
     * <p>三条规矩来自表结构自己的注释，实现不得偏离：
     * <ul>
     *   <li><b>非新客照样落行、但不发奖</b> —— 不落行的话「邀了 100 个只有 3 个算数」
     *       在数据里看不见，运营只会看到一个莫名其妙偏低的数</li>
     *   <li><b>发奖失败不打断主流程</b> —— 失败写进 rewardError，注册照常完成</li>
     *   <li><b>幂等</b> —— 同一个 (fissionNo, inviteeNo) 只算一次，靠唯一键兜底</li>
     * </ul>
     *
     * @param deviceId  被邀请人设备号，新客因子 DEVICE 用；没有就传 null
     * @param phoneTail 手机号后四位，新客因子 PHONE 用；**完整号码不要传进来**
     */
    void record(String fissionNo, String inviterNo, String inviteeNo,
                String deviceId, String phoneTail);

    /**
     * 首单回填。下单成功那一刻调，把「这次邀请变成了生意」记下来。
     *
     * <p>只回填**还没有首单**的行 —— 第二单不该覆盖第一单，那会让「转化」
     * 变成「最近一单」，而两者在报表上长得一样。
     */
    void onFirstOrder(String userNo, String orderNo);
}
