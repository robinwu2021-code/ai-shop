package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.marketing.group.GroupService;
import ai.neargo.shop.product.review.ReviewService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商家工作台（B-11.1）：待办与经营数据。
 *
 * <p><b>它必须住在 app 层</b>：待办的七个数字分别来自 trade（订单、售后）、
 * product/review（待回复评价）、marketing（可报价需求）三个域，而域之间不得互相依赖。
 * 放进任何一个域，都会让那个域为了凑一屏数据去依赖另外两个。
 *
 * <p><b>门店口径：按当前门店，不是按主体。</b> 店员打开工作台看到的必须是他这家店的活 ——
 * 看到主体全部待办，他会去干别人店的单，而那些单在他的订单列表里根本点不开。
 */
@Profile("api")
@RestController
public class BizDashboardController {

    private final MerchantOrderService orderService;
    private final AfterSaleService afterSaleService;
    private final ReviewService reviewService;
    private final GroupService groupService;
    private final MerchantQueryPort merchantPort;

    public BizDashboardController(MerchantOrderService orderService, AfterSaleService afterSaleService,
                                  ReviewService reviewService, GroupService groupService,
                                  MerchantQueryPort merchantPort) {
        this.orderService = orderService;
        this.afterSaleService = afterSaleService;
        this.reviewService = reviewService;
        this.groupService = groupService;
        this.merchantPort = merchantPort;
    }

    /**
     * 待办。
     *
     * <p>无单时是一串 0，<b>不是错误</b> —— 新店第一天打开工作台是最正常的场景，
     * 而一个报错的首屏会让店主以为账号没开通。
     */
    @GetMapping("/biz/dashboard/todo")
    public TodoVO todo() {
        BizContext ctx = BizContext.current();
        String merchantNo = BizContext.requireMerchantNo();
        var counts = orderService.todo(merchantNo, ctx.allowedStoresOrAll());
        return new TodoVO(counts.toShip(), counts.toDeliver(), counts.toVerify(), counts.toPick(),
                afterSaleService.merchantPendingCount(merchantNo),
                reviewService.pendingReplyCount(merchantNo),
                groupService.quotableCount(merchantNo));
    }

    /** 经营数据。同上：无单返回 0。 */
    @GetMapping("/biz/dashboard/stats")
    public StatsVO stats() {
        BizContext ctx = BizContext.current();
        String merchantNo = BizContext.requireMerchantNo();
        var s = orderService.stats(merchantNo, ctx.allowedStoresOrAll());
        /*
         * 评分取**主体**的，不按门店切：一家店的评分是平台建档的商家主数据
         * （MerchantBrief.rating），门店维度的评分还没有数据来源。
         * 按门店给一个算不出来的数字，不如明说是主体口径。
         */
        var brief = merchantPort.find(merchantNo);
        double rating = brief.map(MerchantQueryPort.MerchantBrief::rating).orElse(0d);
        return new StatsVO(s.todayOrders(), s.todayGmvMinor(), s.monthOrders(), s.monthGmvMinor(),
                "CNY", rating, reviewService.list(null, merchantNo).size(), s.ownedTrafficRate());
    }

    /**
     * @param toReply  待回复的评价数
     * @param quotable 可报价的求团需求数
     */
    public record TodoVO(int toShip, int toDeliver, int toVerify, int toPick,
                         int afterSale, int toReply, int quotable) {
    }

    /**
     * @param currency         统计口径的币种。一期只有 CNY，多市场（B6）时按门店所在市场取
     * @param ownedTrafficRate 自带客流占比 0–1，决定费率档（ADR-004 §6）
     */
    public record StatsVO(int todayOrders, long todayGmvMinor, int monthOrders, long monthGmvMinor,
                          String currency, double rating, int ratingCount, double ownedTrafficRate) {
    }
}
