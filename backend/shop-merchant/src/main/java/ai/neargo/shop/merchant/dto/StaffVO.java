package ai.neargo.shop.merchant.dto;

import java.util.List;

/**
 * 商家员工（B 端账号 + 他在各门店的角色）。
 *
 * @param mchAccountNo 商家账号号。**不叫 staffNo** —— 那个名字被平台运营占着，
 *                     而两者是完全不同的人（运营 vs 店员）
 * @param loginPhone   登录手机号，**脱敏**。完整号不回显给端：
 *                     能看到这张列表的人（**只有老板**，端点要 {@code biz:store:admin}）
 *                     一次就拿到全体员工的手机号，那等于一份可导出的通讯录。
 *                     脱敏的理由与「谁看得到」无关 —— 收窄了可见范围不等于该把号发出去
 * @param isOwner      老板。**老板不受门店授权限制**，他的店都归他管
 * @param status       ACTIVE / DISABLED
 * @param roles        他在各门店的角色。老板为空 —— 不是"没授权"，是"不需要授权"
 */
public record StaffVO(String mchAccountNo, String loginPhone, boolean isOwner,
                      String status, List<StoreRoleVO> roles) {

    /**
     * @param role 角色码。取值域以 {@link ai.neargo.shop.merchant.entity.MchStoreRole#GRANTABLE}
     *             为准（店长 / 店员 / 理货员 / 配送员 / 客服）——
     *             <b>这里不再列第二份</b>：上一版注释只写了 MANAGER / CLERK，
     *             而角色早已是五个，照着注释写判断的人会漏掉三个。
     *             各角色能做什么见 {@code ai.neargo.shop.auth.BizPerms}
     */
    public record StoreRoleVO(String storeNo, String storeName, String role) {
    }
}
