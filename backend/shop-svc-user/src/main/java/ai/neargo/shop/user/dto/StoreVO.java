package ai.neargo.shop.user.dto;

/**
 * 门店（商家侧列表与管理用）。
 *
 * @param storeNo       门店号。**一旦生成就不再变** —— 评价、订单、顾客的「我常逛的店」
 *                      都挂在它上面；换主体只换 entityNo，不换它
 * @param isDefault     是否默认店。一个主体<b>恰好一家</b>
 * @param status        ACTIVE / READONLY
 * @param payMerchantNo 这家店用哪个收款号。**空 = 用主体的默认收款号**，
 *                      不是"没配"——单通道时永远为空，行为与今天一致
 * @param payReady      这家店现在能不能收钱。端上照它显示，别自己去比状态串
 * @param staffCount    授权到这家店的员工数（不含老板）。0 表示只有老板能管这家店
 */
public record StoreVO(String storeNo, String name, String address, boolean isDefault,
                      String status, String payMerchantNo, boolean payReady, int staffCount) {
}
