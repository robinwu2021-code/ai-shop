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
}
