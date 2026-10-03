package ai.neargo.shop.merchant.dto;

import java.util.List;

/**
 * 商家员工（B 端账号 + 他在各门店的角色）。
 *
 * @param displayName  姓名（老板自己写的）。<b>认人靠它</b> —— 一列号码谁也分不清
 * @param mchAccountNo 商家账号号。**不叫 staffNo** —— 那个名字被平台运营占着，
 *                     而两者是完全不同的人（运营 vs 店员）
 * @param loginPhone   登录手机号，<b>完整、不脱敏</b>（2026-08-12 拍板）。
 *                     它<b>就是这个员工的登录用户名</b> —— 老板要核对「他用哪个号登录」、
 *                     人换号时要改，脱敏之后这两件事都做不了；而号码本来就是老板填进去的。
 *                     能看到这张列表的只有老板（端点要 {@code biz:store:admin}）
 * @param isOwner      老板。**老板不受门店授权限制**，他的店都归他管
 * @param status       ACTIVE / DISABLED
 * @param roles        他在各门店的角色。老板为空 —— 不是"没授权"，是"不需要授权"
 */
public record StaffVO(String mchAccountNo, String displayName, String loginPhone, boolean isOwner,
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
