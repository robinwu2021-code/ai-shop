package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.StoreVO;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import ai.neargo.shop.merchant.service.StoreAdminService;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 跨店总览与对比（B-11.12.5 / 11.12.6）—— <b>增值包真正卖的那两样东西</b>。
 *
 * <p>需求 §2.1 的原话：卖的不是「允许你开第二家店」（他开两个主体就能有，只是麻烦），
 * 是「<b>一个手机管完，并且能横着比</b>」。只做额度闸不做这两页，
 * 商家花了钱仍然要一家一家切着看，他会觉得被骗了。
 *
 * <p><b>它必须住在 app 层</b>，理由与 {@link BizDashboardController} 同一条：
 * 数字来自 trade（订单）、product（店级库存）、review/merchant（评分）、
 * merchant（门店档案与订阅）四个域，而域之间不得互相依赖。
 *
 * <h2>两道正交的门</h2>
 * <ol>
 *   <li><b>权限</b>（{@code biz:customer}）：这个<b>人</b>能不能看经营数据。
 *       解法是找老板授权</li>
 *   <li><b>能力位</b>（{@code cross_store_stats}）：这家<b>店</b>买没买这个包。
 *       解法是升档</li>
 * </ol>
 * 两者都不满足时先报权限 —— Spring Security 的注解在方法体之前跑。
 * 合成一道门的话，一个 FREE 商家的老板会看到「你没有权限」，
 * 然后去找一个并不存在的开关。
 */
@Profile("api")
@RestController
public class BizCrossStoreController {

    private final MerchantPlanService planService;
    private final StoreAdminService storeAdminService;
    private final MerchantOrderService orderService;
    private final MerchantGoodsService goodsService;
    private final MerchantQueryPort merchantPort;

    public BizCrossStoreController(MerchantPlanService planService,
                                   StoreAdminService storeAdminService,
                                   MerchantOrderService orderService,
                                   MerchantGoodsService goodsService,
                                   MerchantQueryPort merchantPort) {
        this.planService = planService;
        this.storeAdminService = storeAdminService;
        this.orderService = orderService;
        this.goodsService = goodsService;
        this.merchantPort = merchantPort;
    }

    /**
     * 跨店总览：按店并列今日订单 / 销售额 / 本月 / 三项待办。
     *
     * <p><b>数字与单店页面同源</b>：走 {@code MerchantOrderService.statsByStore} 与
     * {@code todoByStore}，与 {@code /biz/dashboard/stats}、{@code /biz/order} 是同一批行、
     * 同一个成交口径。另存一份计数器是这类功能最常见的做法，也是最贵的 ——
     * 「总览说 3 单，点进去只有 2 单」之后，商家从此不再相信任何一个数字。
     *
     * <p><b>单店商家也能打开</b>（只要有能力位）：他看到的就是他那一家，
     * 不是空列表也不是报错。多门店是渐进的，第二家店建起来的前一天这一页就该在。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/cross-store/overview")
    public OverviewVO overview() {
        String merchantNo = requireCrossStoreStats();
        List<StoreVO> stores = visibleStores(merchantNo);
        Set<String> scope = storeScopeOf(stores);

        var stats = orderService.statsByStore(merchantNo, scope);
        var todo = orderService.todoByStore(merchantNo, scope);

        List<StoreRow> rows = new ArrayList<>();
        for (StoreVO s : stores) {
            /*
             * 没有单的门店取零值行，**不是从列表里消失**：
             * 一家今天还没开张的店从总览里不见了，店主的第一反应是「我的店呢」。
             * 零是一个答案，缺席不是。
             */
            var st = stats.get(s.storeNo());
            var td = todo.get(s.storeNo());
            rows.add(new StoreRow(s.storeNo(), s.name(), s.isDefault(), s.status(),
                    st == null ? 0 : st.todayOrders(),
                    st == null ? 0L : st.todayGmvMinor(),
                    st == null ? 0 : st.monthOrders(),
                    st == null ? 0L : st.monthGmvMinor(),
                    td == null ? 0 : td.toShip(),
                    td == null ? 0 : td.toDeliver(),
                    td == null ? 0 : td.toStock()));
        }
        return new OverviewVO("CNY", rows);
    }

    /**
     * 跨店对比：窗口内的销售额 / 订单数 / 复购率 / 缺货数，外加一个<b>主体级</b>评分。
     *
     * @param days 回看天数（含今天），默认 30。夹在 1–365 之间 ——
     *             端上传了个 0 或者 99999 不该让整页报错或者拖垮一次全表扫
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/cross-store/compare")
    public CompareVO compare(@RequestParam(required = false, defaultValue = "30") int days) {
        String merchantNo = requireCrossStoreStats();
        int window = Math.min(Math.max(days, 1), MAX_WINDOW_DAYS);

        List<StoreVO> stores = visibleStores(merchantNo);
        Set<String> scope = storeScopeOf(stores);

        var compare = orderService.compareByStore(merchantNo, scope, window);
        var outOfStock = goodsService.outOfStockCountByStore(merchantNo, scope);

        List<CompareRow> rows = new ArrayList<>();
        for (StoreVO s : stores) {
            var c = compare.getOrDefault(s.storeNo(), MerchantOrderService.StoreCompare.empty());
            rows.add(new CompareRow(s.storeNo(), s.name(), s.isDefault(), s.status(),
                    c.orders(), c.gmvMinor(), c.buyers(), c.repeatBuyers(), c.repeatRate(),
                    outOfStock.getOrDefault(s.storeNo(), 0),
                    // ★ 门店评分（V155）：这一列此前不存在，评分只能放在页面顶部
                    s.rating() / 10.0, s.ratingCount()));
        }

        /*
         * 顶层这个是**主体整体评分**，与每行的门店评分并存（V155 起）。
         *
         * 两个数都要给：门店分回答「哪家店做得好」，主体分回答「这个牌子在平台上的分」——
         * 后者是 C 端商家卡上显示的那个，商家问「为什么我的店 4.9 而搜索里是 4.6」时，
         * 页面上要能同时看到两者才解释得通。
         *
         * 【历史】V155 之前 `rvw_review` 只有 entity_no 没有 store_no，
         * 门店维度的评分**没有数据源**，所以这个数只能放在顶层，
         * 并且要专门写一句说明「为什么三家店是同一个数」——
         * 一个说不清来源的数字比没有这个数字更糟。
         * 那次改造（C 端写入时落 store_no + 历史回填）已经做完，见 TDD-评价归门店。
         */
        double rating = merchantPort.find(merchantNo)
                .map(MerchantQueryPort.MerchantBrief::rating).orElse(0d);
        int ratingCount = merchantPort.find(merchantNo)
                .map(MerchantQueryPort.MerchantBrief::ratingCount).orElse(0);

        return new CompareVO(window, "CNY", rating, ratingCount, rows);
    }

    // ---------------------------------------------------------------- 内部

    /** 一次对比最多回看一年。再长的窗口对「哪家店最近更好」没有意义，只是一次更慢的全表扫 */
    private static final int MAX_WINDOW_DAYS = 365;

    /**
     * 能力位门禁。<b>没有就明确拒绝，不返回空数据</b>。
     *
     * <p>返回空列表看起来「更友好」，实际是把「你还没买这个」说成了「它坏了」：
     * 商家明明有两家店，这一页却什么都没有 —— 他的下一步是打客服电话，
     * 而这本该是一次升档。错误文案里带上当前档位，他才知道差在哪。
     *
     * @return 主体号，后面都要用
     */
    private String requireCrossStoreStats() {
        String merchantNo = BizContext.requireMerchantNo();
        if (!planService.canCrossStoreStats(merchantNo)) {
            throw BizException.of(ErrorCode.PLAN_CAPABILITY_REQUIRED,
                    planService.current(merchantNo).planCode());
        }
        return merchantNo;
    }

    /**
     * 这个人能看到哪几家店。
     *
     * <p>老板看全部；子账号<b>只看被授权的那几家</b> —— 跨店总览是这个包的卖点，
     * 但它不是一张绕过门店授权的后门。只被授权到 A 店的店员在这里看到 B 店的流水，
     * 与他在订单页越权看到 B 店的单是同一件事。
     */
    private List<StoreVO> visibleStores(String merchantNo) {
        Set<String> allowed = BizContext.current().allowedStoresOrAll();
        List<StoreVO> all = storeAdminService.list(merchantNo);
        if (allowed == null) {
            return all;
        }
        // 空集合 = 一家都没授权 → 空列表，fail-closed。当成「不过滤」是这类越权最常见的写法
        return all.stream().filter(s -> allowed.contains(s.storeNo())).toList();
    }

    /**
     * 交给聚合方法的门店范围。
     *
     * <p><b>始终是一个显式集合，从不传 null</b>：null 在下游是「不按门店过滤」，
     * 会把 {@code store_no} 为空的历史单也算进来 —— 而那些单不属于任何一行，
     * 加进去就会让「总数 = 各店之和」不成立。
     *
     * <p>门店列表为空时返回空集合，下游 fail-closed 返回空结果，不是全量。
     */
    private static Set<String> storeScopeOf(List<StoreVO> stores) {
        return stores.stream().map(StoreVO::storeNo).collect(java.util.stream.Collectors.toSet());
    }

    // ---------------------------------------------------------------- 出参

    /**
     * @param currency 统计口径的币种。与 {@code /biz/dashboard/stats} 同一个字段，
     *                 一期只有 CNY
     * @param stores   按店并列。顺序与 {@code /biz/store/list} 一致（默认店在前），
     *                 端上不必自己排 —— 两处各排一次，切页面时行会跳
     */
    public record OverviewVO(String currency, List<StoreRow> stores) {
    }

    /**
     * 总览的一行。
     *
     * <p><b>只有门店维度的三项待办</b>（{@code toShip}/{@code toDeliver}/{@code toStock}）。
     * 工作台上还有 {@code toVerify}（待核销）与 {@code toPick}（待分拣），
     * 这里<b>刻意不给</b>：那两个数是<b>自提点</b>维度且不限商家 ——
     * 一个自提点承接多家商家的货（ADR-005）。把它们摆进「门店」这一列，
     * 商家会读成「这家店的活」，点进去却是别人的货。
     *
     * @param status ACTIVE / READONLY。停用的店也在列表里 ——
     *               看不见的话商家会以为店被删了，而它的历史数字还在
     */
    public record StoreRow(String storeNo, String storeName, boolean isDefault, String status,
                           int todayOrders, long todayGmvMinor,
                           int monthOrders, long monthGmvMinor,
                           int toShip, int toDeliver, int toStock) {
    }

    /**
     * 跨店对比。
     *
     * @param days        实际生效的窗口天数（已夹取）。回显出来，端上才知道
     *                    「我传的 99999 被截成了 365」
     * @param rating      <b>主体整体评分</b>（各店合成，也是 C 端商家卡上显示的那个）。
     *                    每家店自己的分在 {@link CompareRow#rating()} 上（V155 起）——
     *                    端上两个都要显示：商家问「为什么我的店 4.9 而搜索里是 4.6」时，
     *                    只有并排看得到才解释得通
     * @param ratingCount 计入评分的评价条数。0 = 还没人评过，端上据此显示「暂无评价」
     *                    而不是 0 颗星
     */
    public record CompareVO(int days, String currency, double rating, int ratingCount,
                            List<CompareRow> stores) {
    }

    /**
     * 对比的一行。
     *
     * @param buyers       窗口内下过单的买家数（去重）
     * @param repeatBuyers 其中下过 ≥2 单的
     * @param repeatRate   {@code repeatBuyers / buyers}，0–1。<b>分母为 0 时是 0</b>，
     *                     不是除零、不是 null —— 一家还没开张的店该显示 0%
     * @param outOfStockSkus 该店 {@code prd_store_stock} 里可用量（stock − locked）≤ 0 的 SKU 数。
     *                     <b>只数已启用分店库存的 SKU</b>：一条店级行都没有的 SKU 走主体总量，
     *                     不算这家店缺货
     */
    /**
     * @param rating      **这家店自己的**评分（V155）。与顶层的主体评分是两个数：
     *                    主体分是各店的合成，反过来推不回去
     * @param ratingCount 计入这家店评分的条数。<b>0 = 暂无评价</b>，端上按条数判空 ——
     *                    老评价没有门店归属，所以老店在第一条新评价到来之前也是 0，
     *                    那时显示「暂无评价」而不是 0 颗星
     */
    public record CompareRow(String storeNo, String storeName, boolean isDefault, String status,
                             int orders, long gmvMinor,
                             int buyers, int repeatBuyers, double repeatRate,
                             int outOfStockSkus, double rating, int ratingCount) {
    }
}
