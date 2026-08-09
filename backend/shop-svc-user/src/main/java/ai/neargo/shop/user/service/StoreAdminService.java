package ai.neargo.shop.user.service;

import ai.neargo.shop.user.dto.StoreVO;

import java.util.List;

/**
 * 门店管理（M6 多门店的商家侧）。
 *
 * <p>与 {@link MerchantStoreService} 的分工：那个管**一家店的门面**（公告/营业时间/地址/主推），
 * 这个管**有几家店、哪家是哪家**。分开是因为前者商家天天改，后者一年动不了几次，
 * 且后者每一个动作都有硬约束（额度、默认店唯一、未完成订单）。
 *
 * <p><b>门店与主体是关联不是归属</b>：换执照店照开（见「账号主体门店-关系定稿」）。
 * 所以门店号 {@code store_no} 一旦生成就不再变 —— 评价、订单、顾客的「我常逛的店」
 * 全挂在它上面，换主体只换 {@code entity_no}。
 */
public interface StoreAdminService {

    /** 本主体的门店列表（含停用的）。停用的也要看得见 —— 看不见的话商家会以为店被删了。 */
    List<StoreVO> list(String merchantNo);

    /**
     * 新建门店。
     *
     * <p><b>受额度限制</b>：超出时拒绝并告诉他上限是多少。
     * 额度当前来自配置（`shop.store.max-per-entity`，默认 1 = 与单店时代行为一致）；
     * M4 Plan 落地后改由订阅档位决定（FREE 1 / PRO 3 / CHAIN 10）。
     * <b>现在不建 plan 表</b> —— 结构没到位就先把名字占上，只会留下一张空表和一处假逻辑。
     */
    StoreVO create(String merchantNo, String name, String address);

    /** 改门店名与地址。门面其余部分走 {@link MerchantStoreService}。 */
    StoreVO rename(String merchantNo, String storeNo, String name, String address);

    /**
     * 停用 / 启用门店。
     *
     * <p><b>默认店不能停用</b>：主体必须恰好有一家默认店，停掉它之后
     * 「这个主体的店在哪」就没有答案了 —— 而很多链路（下单兜底、门店码、分享）都依赖它。
     * 要停就先把默认标转给别家。
     *
     * <p>一期<b>不校验未完成订单</b>：停用只是不再接新单，已有的单照常履约核销。
     * 拿未完成订单挡住停用，会让一家已经关门的店因为一个迟迟不来取货的顾客而一直停不掉。
     */
    StoreVO setStatus(String merchantNo, String storeNo, boolean active);

    /**
     * 转移默认店。
     *
     * <p>做成一个显式动作而不是「新店可勾选默认」：后者会在中间态出现
     * 两家默认店或零家默认店，而这两种状态没有任何地方能表达。
     */
    StoreVO setDefault(String merchantNo, String storeNo);

    /**
     * 换这家店的收款商户号。
     *
     * <p>四条硬约束，全部拦在这里：
     * <ol>
     *   <li>目标收款号必须**属于同一个主体** —— 否则钱会打到别人的执照名下</li>
     *   <li>目标收款号必须已 **ACTIVE** —— 换到一个收不了款的号上，下一单就失败</li>
     *   <li>传空 = 回到「用主体的默认收款号」，这是合法操作，不是清空错误</li>
     *   <li>换动作**留痕**（{@code payment_changed_at}）—— 它改变的是钱的去向</li>
     * </ol>
     */
    StoreVO setPayment(String merchantNo, String storeNo, String payMerchantNo);
}
