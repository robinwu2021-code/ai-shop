package ai.neargo.shop.merchant.dto;

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
 * @param planSuspended 这家店的只读<b>是套餐降级压下来的</b>，不是商家自己停的（V150）。
 *                      两者的 {@code status} 一模一样，靠这一位区分 ——
 *                      而它决定端上给出的下一步完全不同：<b>补缴</b>还是<b>点一下启用</b>。
 *                      不给这一位的话，商家会去点那个对降级店无效的启用按钮
 * @param rating        门店评分 ×10（V155，ADR-011：评价归门店）。
 *                      <b>与主体评分是两个数</b>：主体分是各店的合成，反过来推不回去
 * @param ratingCount   计入门店评分的评价条数。<b>0 = 暂无评价</b>，不是 0 分 ——
 *                      端上按条数判空；老评价没有门店归属，所以老店在第一条新评价到来前也是 0
 */
public record StoreVO(String storeNo, String name, String address, boolean isDefault,
                      String status, String payMerchantNo, boolean payReady, int staffCount,
                      boolean planSuspended, int rating, int ratingCount) {
}
