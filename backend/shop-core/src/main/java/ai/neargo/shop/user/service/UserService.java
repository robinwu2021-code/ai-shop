package ai.neargo.shop.user.service;

import ai.neargo.shop.user.dto.UserVO;

/** 我的资料与归属绑定（[API 清单 §2.1/2.2]）。 */
public interface UserService {

    UserVO profile();

    /**
     * 当前登录人的 C 端档案，<b>没有就返回 null 而不是抛 401</b>。
     *
     * <p>给 B 端用。员工走 {@code /biz/auth/staff-login}（手机号 + 验证码）登录，
     * <b>可能根本没有 C 端账号</b> —— 对他而言「查不到 usr_account」是正常状态，
     * 不是会话失效。用 {@link #profile()} 会把这件正常的事报成 10401，
     * 表现为「店员登录进得去，刷新一次就被踢回入驻引导页」。
     */
    UserVO profileOrNull();

    /**
     * 绑定社区 + 自提点（C-AC-02）。切换社区也走这里 —— 覆盖而非并存。
     *
     * @throws ai.neargo.shop.common.BizException 自提点不属于该社区时
     */
    UserVO bindCommunity(String communityNo, String pickupNo);

    /** 修改昵称/头像。传 null 的字段不动。 */
    UserVO updateProfile(String nickname, String avatar);

    /**
     * 绑定手机号（C-AC-04）。**绑定后两种标识指向同一账号** ——
     * 微信进来的用户绑了手机号，之后用手机号登录必须还是同一个人，
     * 否则同一用户会有两套订单与两个购物车。
     */
    UserVO bindPhone(String phone, String code);

    /**
     * 绑定一个**已经被验证过**的手机号（微信手机号快速验证）。
     *
     * <p>不校验验证码 —— 号码由 {@code phonenumber.getPhoneNumber} 换出，端上碰不到它。
     * 冲突处理与 {@link #bindPhone} 完全一致：属于别人时报 CONFLICT，不自动合并。
     */
    UserVO bindPhoneTrusted(String phone);

    /**
     * 注销账号（微信对有账号体系的小程序**要求提供**这个入口）。
     *
     * <p><b>做法是匿名化 + 解绑凭证，不是删行。</b> 订单、结算、发票有留存义务，
     * 删掉它们既违规、也会让对账与售后凭空断掉。所以：
     * <ul>
     *   <li>昵称/头像抹掉，状态置 {@code DEREGISTERED}</li>
     *   <li>{@code usr_identity} 上的 openid / unionid / 手机号 / apple_sub <b>全部解绑</b>
     *       —— 解绑之后同一个微信再进来是<b>一个全新账号</b>，这正是「注销」的含义</li>
     *   <li>踢掉所有在线会话</li>
     *   <li>交易记录原样留着，挂在那个已匿名的 userNo 下</li>
     * </ul>
     *
     * <p><b>有未走完的订单时拒绝</b>：注销后没人能再联系到他，而货可能在路上。
     */
    void deregister();
}
