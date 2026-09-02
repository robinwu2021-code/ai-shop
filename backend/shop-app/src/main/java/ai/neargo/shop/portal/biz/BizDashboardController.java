package ai.neargo.shop.portal.biz;

import ai.neargo.shop.auth.BizPerms;
import org.springframework.security.access.prepost.PreAuthorize;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.marketing.group.GroupService;
import ai.neargo.shop.product.review.ReviewService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.merchant.service.MerchantStoreService;
import ai.neargo.shop.merchant.service.PosterService;
import ai.neargo.shop.merchant.service.StoreCodeService;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    private final MerchantStoreService storeService;
    private final StoreCodeService storeCodeService;
    private final ai.neargo.shop.merchant.service.StoreLinkService storeLinkService;
    private final PosterService posterService;

    public BizDashboardController(MerchantOrderService orderService, AfterSaleService afterSaleService,
                                  ReviewService reviewService, GroupService groupService,
                                  MerchantQueryPort merchantPort, MerchantStoreService storeService,
                                  StoreCodeService storeCodeService,
                                  ai.neargo.shop.merchant.service.StoreLinkService storeLinkService,
                                  PosterService posterService) {
        this.storeService = storeService;
        this.storeCodeService = storeCodeService;
        this.storeLinkService = storeLinkService;
        this.posterService = posterService;
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
     *
     * <p><b>门禁是「任一」而不是 {@code order:view}</b>：这 7 个数字分属 5 个权限，
     * 用其中一个盖住全部，等于「除了店员和店长，谁都看不到自己今天有多少活」。
     * 理货员被挡在外面尤其说不通 —— 待分拣就是他的本职。
     * 哪些格子画出来由端上按 {@code perms} 决定，见 {@code @perm.canAnyBiz} 的说明。
     */
    @PreAuthorize("@perm.canAnyBiz('" + BizPerms.ORDER_VIEW + "','" + BizPerms.RECEIVE
            + "','" + BizPerms.VERIFY + "','" + BizPerms.SHIP + "','" + BizPerms.AFTERSALE
            + "','" + BizPerms.REVIEW + "','" + BizPerms.CAMPAIGN + "')")
    @GetMapping("/biz/dashboard/todo")
    public TodoVO todo() {
        BizContext ctx = BizContext.current();
        String merchantNo = BizContext.requireMerchantNo();
        /*
         * 两个作用域：发货按门店，分拣与核销按自提点 —— 见 MerchantOrderService.todo。
         *
         * 门店那一维取**当前门店**而不是「我有权限的全部门店」：后者对老板返回 null
         * （不过滤），于是他在首页切门店时待办数纹丝不动 —— 切哪家店都是名下合计，
         * 而且不报错。要看几家店的合计有「跨店报表」，不是这里。
         */
        var counts = orderService.todo(merchantNo, ctx.currentStoreScope(), ctx.pickupNos());
        return new TodoVO(counts.toShip(), counts.toDeliver(), counts.toStock(),
                counts.toVerify(), counts.toPick(),
                afterSaleService.merchantPendingCount(merchantNo),
                reviewService.pendingReplyCount(merchantNo),
                groupService.quotableCount(merchantNo));
    }

    /** 经营数据。同上：无单返回 0。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/dashboard/stats")
    public StatsVO stats() {
        BizContext ctx = BizContext.current();
        String merchantNo = BizContext.requireMerchantNo();
        // 同 todo：按当前门店，不是名下全部 —— 否则切门店时这几个数字不会变
        var s = orderService.stats(merchantNo, ctx.currentStoreScope());
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

    // ---------------------------------------------------------------- 顾客与门店配置

    /**
     * 顾客列表（B-11.10）。<b>不下发完整手机号</b>（B12）。
     *
     * <p>沉默客户排在最前 —— 那是店主唯一能立刻行动的一批：
     * 一份「谁快流失了」的名单，比一份按字母排序的通讯录有用得多。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.CUSTOMER + "')")
    @GetMapping("/biz/customers")
    public List<MerchantOrderService.CustomerSummary> customers() {
        BizContext ctx = BizContext.current();
        // 顾客页自己也有门店切换条，同样要跟着当前门店走
        return orderService.customers(BizContext.requireMerchantNo(), ctx.currentStoreScope());
    }

    /** 当前门店的配送规则。没配过时返回默认值，不是空。 */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/delivery/rule")
    public MerchantStoreService.DeliveryRuleVO deliveryRule() {
        return storeService.deliveryRule(BizContext.requireMerchantNo(),
                BizContext.current().currentStoreNo());
    }

    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @PostMapping("/biz/delivery/rule")
    public MerchantStoreService.DeliveryRuleVO saveDeliveryRule(@RequestBody DeliveryRuleReq req) {
        return storeService.saveDeliveryRule(BizContext.requireMerchantNo(),
                BizContext.current().currentStoreNo(),
                new MerchantStoreService.DeliveryRuleVO(req.radius(), req.minOrderMinor(),
                        req.feeMinor(), req.freeThresholdMinor()));
    }

    /**
     * 分享物料（B-11.11）：一句可以直接粘进群里的文案 + 一张海报。
     *
     * <p>带 {@code goodsNo} 就分享单品，不带就分享整店。文案在后端拼而不是端上拼：
     * 三端（微信/支付宝/H5）和三种语言各拼一遍，迟早出现「店名对、链接错」的版本。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/store/share-kit")
    public ShareKitVO shareKit(@RequestParam(required = false) String goodsNo) {
        String merchantNo = BizContext.requireMerchantNo();
        // 分享出去的也是当前这家店的码（V298）；没切店时是默认店
        String code = storeCodeService.ensureForStore(merchantNo, BizContext.current().currentStoreNo());
        String shopName = merchantPort.find(merchantNo)
                .map(MerchantQueryPort.MerchantBrief::merchantName).orElse("我的小店");
        /*
         * **没配域名就不发链接**，而不是发一个占位域名。
         *
         * 这里原本写死 `https://shop.example.com/s/<code>` —— 商家复制出去的链接、
         * 印在包装袋上的码，全都指向一个不存在的地方。而功能点在清单上是「已实现」。
         * 发假的与不发，在商家那边的区别是：**前者他印了 500 张贴纸才发现**。
         */
        String url = storeLinkService.linkOf(code, goodsNo);
        String text = url == null
                // 没链接时文案也不能带一个空洞 —— 商家会连着那半句一起粘出去
                ? shopName + "：街坊邻居下单，楼下自提，扫下方小程序码进店"
                : goodsNo == null || goodsNo.isBlank()
                        ? shopName + "：街坊邻居下单，楼下自提，" + url
                        : shopName + " 上新了，点进来看看：" + url;
        return new ShareKitVO(text, url);
    }

    /**
     * 真·海报（B-11.11 补，2026-08-24）：封面图/店名/价格/小程序码合成的一张 PNG，
     * 能直接发朋友圈，不是一句文案。
     *
     * <p><b>回 base64 JSON，不是图片 URL</b>：这条路由跟其余 {@code /biz/**} 一样要带
     * {@code Authorization: Bearer} 才能过 {@code @PreAuthorize}，而 {@code <image src>}
     * 这类标签发不出自定义请求头——直接给 URL 的话端上一加载就是 401。与店铺码
     * （{@code StoreQrcode.imageBase64}）走的是同一条路。
     */
    @PreAuthorize("@perm.canBiz('" + BizPerms.STORE + "')")
    @GetMapping("/biz/store/poster")
    public PosterVO poster(@RequestParam(required = false) String goodsNo) {
        String merchantNo = BizContext.requireMerchantNo();
        byte[] png = posterService.render(merchantNo, goodsNo);
        if (png == null) {
            return new PosterVO(null);
        }
        return new PosterVO(java.util.Base64.getEncoder().encodeToString(png));
    }

    public record DeliveryRuleReq(int radius, long minOrderMinor, long feeMinor,
                                  long freeThresholdMinor) {
    }

    /** @param posterUrl 落地页链接，海报文字用；真正的海报图走 {@code /biz/store/poster} */
    public record ShareKitVO(String text, String posterUrl) {
    }

    /** @param imageBase64 海报 PNG 的 base64（不含 data: 前缀）。生成不出来时为 null */
    public record PosterVO(String imageBase64) {
    }

    /**
     * @param toReply  待回复的评价数
     * @param quotable 可报价的求团需求数
     */
    public record TodoVO(int toShip, int toDeliver,
                         /** 待备货：自提单已付款，货还没送到自提点。**按门店**，这是供货方的活 */
                         int toStock,
                         int toVerify, int toPick,
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
