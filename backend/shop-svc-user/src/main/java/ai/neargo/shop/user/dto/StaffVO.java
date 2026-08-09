package ai.neargo.shop.user.dto;

import java.util.List;

/**
 * 商家员工（B 端账号 + 他在各门店的角色）。
 *
 * @param mchAccountNo 商家账号号。**不叫 staffNo** —— 那个名字被平台运营占着，
 *                     而两者是完全不同的人（运营 vs 店员）
 * @param loginPhone   登录手机号，**脱敏**。完整号不回显给端：
 *                     店长能看到所有店员的手机号，那就等于一份可导出的通讯录
 * @param isOwner      老板。**老板不受门店授权限制**，他的店都归他管
 * @param status       ACTIVE / DISABLED
 * @param roles        他在各门店的角色。老板为空 —— 不是"没授权"，是"不需要授权"
 */
public record StaffVO(String mchAccountNo, String loginPhone, boolean isOwner,
                      String status, List<StoreRoleVO> roles) {

    /**
     * @param role MANAGER（店长）/ CLERK（店员）
     */
    public record StoreRoleVO(String storeNo, String storeName, String role) {
    }
}
