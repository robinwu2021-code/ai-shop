package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.spi.fulfillment.FreightPort;
import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.user.PickupQueryPort;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.service.CloseRuleService;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.OrderStateMachine;
import ai.neargo.shop.trade.service.OrderStatusView;

import ai.neargo.shop.spi.marketing.AttributionPort;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.marketing.CouponPort;
import ai.neargo.shop.spi.settle.PointsPort;
import ai.neargo.shop.spi.product.GoodsQueryPort;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.event.AfterCommit;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.Fulfillments;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.trade.ShipmentTraceQueryPort;
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdCartItem;
import ai.neargo.shop.trade.mapper.TradeMappers.CartItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 交易主干实现。下单链路严格按 TDD-backend §7.1 的八步走。
 *
 * <p><b>拆单是这里最重要的一件事</b>（E3/ADR-002）：购物车跨商家时按 {@code merchantNo} 分组，
 * 每组一个子订单，各自算钱、各自履约、各自分账。预览与下单**共用同一个拆分方法**，
 * 否则「预览 2 个包裹、下单变 3 个」这类问题会一直复发。
 */
@Service
public class OrderServiceImpl implements OrderService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(OrderServiceImpl.class);

    /**
     * 支付时限**由平台配置决定**（P-4.2.3，{@code /orders?tab=close}）。
     *
     * <p>此前这里是 {@code PAY_TTL = 15 分钟} 的常量，而运营端有一个能编辑、
     * 能保存的关单策略表单 —— 那个表单配的是一个<b>不存在的行为</b>。
     *
     * <p><b>在下单这一刻按当时的配置算好、盖在 {@code pay_deadline_at} 上</b>，
     * 而不是让关单任务每轮现算：
     * <ul>
     *   <li>改配置不会回头关掉已经在跑的老单 —— 运营改个数不会让一批订单当场消失</li>
     *   <li>端上倒计时读的就是这枚章，倒计时与真实关单时刻<b>由构造保证一致</b>，
     *       不需要端上再同步一份时长</li>
     * </ul>
     */
    private final CloseRuleService closeRuleService;
    private final ShipmentTraceQueryPort shipmentTracePort;
    private static final String CURRENCY_CNY = "CNY";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderMapper orderMapper;
    private final SubOrderMapper subOrderMapper;
    private final ai.neargo.shop.spi.user.AdmissionPort admissionPort;
    private final OrderItemMapper itemMapper;
    /**
     * 支付方式可用性的唯一判定入口（四层取交集）。
     * <b>别在本类里再判一遍</b> —— 结算页与商品详情页会因此各说各话。
     */
    /**
     * 走 Port 而不是直接注入 {@code product.service.PayModeService} ——
     * trade 域不认识 product 域的 Service。上一版是直接注入的，
     * 而拦它的那条 ArchUnit 规则常年红着，于是**没有任何信号**就混了进来。
     */
    private final ai.neargo.shop.spi.product.PayModePort payModeService;
    private final CartItemMapper cartMapper;
    private final GoodsQueryPort goodsPort;
    private final StockPort stockPort;
    private final MerchantQueryPort merchantPort;
    private final ai.neargo.shop.spi.user.MerchantAdminPort merchantAdminPort;
    private final AttributionPort attributionPort;
    private final CouponPort couponPort;
    /** 店铺活动的自动优惠（满减）。此前 mkt_campaign 没有任何消费方 */
    private final CampaignPort campaignPort;
    private final PointsPort pointsPort;
    /** 取该商家按市场筛出的可用通道（S4）—— 下单要选通道 */
    private final ai.neargo.shop.spi.pay.PayChannelMasterPort payChannelMasterPort;
    /**
     * 「这一行配了什么积分规则」（product 域）。
     *
     * <p>M9（2026-09-01）之前这个 Port 是<b>支付域</b>在调 —— 支付域反过来问商品配置。
     * 现在由这边查好、随 {@code EarnLine} 传进去：规则是下单那一刻的事实，
     * 而下单链路本来就在这儿。
     */
    private final ai.neargo.shop.spi.product.PointsRulePort pointsRulePort;
    private final SettlePort settlePort;
    private final StatusLogMapper statusLogMapper;

    /** 自动确认收货要跳过争议中的单（AFTER_SALE_OPEN），判据与结算入批同一份。 */
    private final ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper afterSaleMapper;
    private final PickupQueryPort pickupPort;
    /** 自提点匹配的规则在聚落域一处（归属链 + 距离），这里只负责把商家的许可点喂进去 */
    private final ai.neargo.shop.spi.user.CommunityQueryPort communityQueryPort;
    /** 取买家绑定的社区，下单时固化到主单 —— 运营按社区做数据域隔离 */
    private final ai.neargo.shop.spi.user.UserQueryPort userPort;
    /** 快递运费模板（TDD-快递100商家寄件 §8）。可空：只装了交易域的测试切片里没有履约域 */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private FreightPort freightPort;
    /** 挑「服务这个社区的最近门店」时要它给社区坐标 */
    private final ai.neargo.shop.spi.user.CommunityQueryPort communityPort;
    private final IdempotencyService idempotency;
    private final OutboxEventBus eventBus;
    /** 支付成功后告诉会员域「他买了一单」。trade 不认识会员表，也不该认识 */
    private final ai.neargo.shop.spi.member.MemberEventPort memberEventPort;
    /** 买家的人档号。没有（微信登录未授权手机号）就不入会 */
    private final ai.neargo.shop.spi.user.PersonPort personPort;
    private final ai.neargo.shop.spi.user.AppointmentSlotPort appointmentSlotPort;
    /** 订单详情要说「评价过没有」与「有没有挂着售后单」。**只在详情用**，列表不查 */
    private final ai.neargo.shop.spi.product.ReviewQueryPort reviewQueryPort;
    private final AfterSaleService afterSaleService;
    /** 极速退判定（§3）：详情页要在申请之前就说得出会不会秒退 */
    private final ai.neargo.shop.trade.service.AfterSaleRuleService afterSaleRuleService;
    /**
     * 社区集单：下单时问「这一单属于哪一期」（TDD-营销域-详细设计 §1.3）。
     * 用 setter 注入而不是再加一个构造参数：构造函数已经 30 个参数，
     * 可选依赖放 setter 让没有集单能力的装配（部分切片测试）也能起来 —— 缺了按「不是集单」处理。
     */
    private ai.neargo.shop.spi.marketing.PeriodPort periodPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPeriodPort(ai.neargo.shop.spi.marketing.PeriodPort periodPort) {
        this.periodPort = periodPort;
    }

    /**
     * 门店状态（TDD-C端门店化与门店门户 §2.7）：落店只落营业中的店、落到暂停的店就拒。
     * setter 注入，理由同 {@link #periodPort}；缺了（切片装配）按「都营业」处理 —— 与加它之前相同。
     */
    private ai.neargo.shop.spi.user.StoreDirectoryPort storeDirectory;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStoreDirectory(ai.neargo.shop.spi.user.StoreDirectoryPort storeDirectory) {
        this.storeDirectory = storeDirectory;
    }

    /** 空串是历史数据，按营业算（与门户的 closed 判断同一口径）；查不到的门店也按营业算，不拿缺失数据拦单 */
    private static boolean open(Map<String, String> statuses, String storeNo) {
        String st = statuses.get(storeNo);
        return st == null || st.isBlank() || ai.neargo.shop.spi.user.StoreDirectoryPort.STORE_ACTIVE.equals(st);
    }

    private Map<String, String> storeStatuses(java.util.Collection<String> storeNos) {
        return storeDirectory == null || storeNos.isEmpty() ? Map.of() : storeDirectory.statuses(storeNos);
    }

    /**
     * 拼团：参团 / 开团接到下单上（TDD-营销域-详细设计 §1.4）。setter 注入，理由同 {@link #periodPort}；
     * 缺了的装配里带团号的单一律拒（BAD_REQUEST），不静默变成普通单。
     */
    private ai.neargo.shop.spi.marketing.GroupJoinPort groupJoinPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setGroupJoinPort(ai.neargo.shop.spi.marketing.GroupJoinPort groupJoinPort) {
        this.groupJoinPort = groupJoinPort;
    }

    /**
     * 微信发货信息录入。setter 注入，理由同 {@link #groupJoinPort}：
     * {@code shop-core} 单独跑测试时没有 paybridge。**缺了就不会上报**，
     * 所以 {@link #notifyShippingOnPaid} 里要喊一声，不能静默。
     */
    private ai.neargo.shop.spi.trade.ShippingUploadPort shippingUploadPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setShippingUploadPort(ai.neargo.shop.spi.trade.ShippingUploadPort port) {
        this.shippingUploadPort = port;
    }

    /** 物流页（TDD-物流模块 批 3）。setter 注入：存量手工构造本类的地方不用跟着改 */
    private ai.neargo.shop.spi.logistics.LogisticsPort logisticsPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLogisticsPort(ai.neargo.shop.spi.logistics.LogisticsPort port) {
        this.logisticsPort = port;
    }

    /**
     * 微信支付下单的 {@code description}。
     *
     * <p><b>它不只是下单参数</b>：用户在微信「我-小店与卡包-小程序购物订单」里
     * 看到的商品信息就是这一串，而「能认出自己买了什么」正是
     * 《小程序订单管理》这个能力存在的理由。此前这里传的是 {@code "订单 " + orderNo}，
     * 于是用户看到的是「订单 O202609200001」—— 恰好是认不出的那一串。
     *
     * <p>拼不出来时<b>退回订单号</b>而不是空串：微信的 {@code description} 不许为空，
     * 空了整笔下单会被拒 —— 认不出总比付不了强。走到这一步说明订单没有明细，
     * 那本身是另一个问题，会在别处报出来。
     */
    private String payDescription(String orderNo) {
        var titles = DataScopeContext.executeWithoutScope(() ->
                itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                        .eq(OrdItem::getOrderNo, orderNo)))
                .stream().map(OrdItem::getTitle).toList();
        String desc = ai.neargo.shop.common.GoodsDesc.of(
                titles, ai.neargo.shop.common.GoodsDesc.PAY_MAX);
        if (desc.isBlank()) {
            log.error("[pay] 订单 {} 没有明细，拼不出商品描述 —— 退回订单号，"
                    + "用户在微信购物订单里将认不出这一单", orderNo);
            return "订单 " + orderNo;
        }
        return desc;
    }

    /**
     * 服务类（到店核销 / 预约上门）**在支付成功这一刻**向微信报发货。
     *
     * <p>它们在我们这儿根本没有「发货」这个动作 —— 付款即出码，商家没有任何前置动作，
     * 因此<b>没有任何按钮能触发上报</b>。把上报挂在按钮上的写法会整块漏掉这一类，
     * 而漏掉的后果不是少个功能，是这些单的钱结不出来。见 {@code WxLogisticsTypes} 类注释。
     */
    private void notifyShippingOnPaid(OrdSubOrder sub) {
        if (!ai.neargo.shop.common.WxLogisticsTypes.uploadOnPaid(sub.getFulfillment())) {
            return;   // 实物类等商家发货 / 等到自提点，各自有迁移点
        }
        if (shippingUploadPort == null) {
            log.error("[wxship] 装配里没有 ShippingUploadPort，服务类子单 {} 不会上报 —— 这笔钱会结不出来",
                    sub.getSubOrderNo());
            return;
        }
        shippingUploadPort.enqueue(sub.getOrderNo(), sub.getSubOrderNo(), sub.getFulfillment());
    }

    /**
     * 仅活动商品的可买判定（TDD-商品仅活动可售）。setter 注入，理由同 {@link #periodPort}。
     * <b>缺了按「没有活动在跑」处理</b>—— 仅活动的货普通下单一律拒。与拼团缺口同一取向：
     * 宁可少卖，也不把它当单品卖出去。
     */
    private ai.neargo.shop.spi.marketing.SaleGatePort saleGatePort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSaleGatePort(ai.neargo.shop.spi.marketing.SaleGatePort saleGatePort) {
        this.saleGatePort = saleGatePort;
    }

    /** 每人限购（P1）。setter 注入：构造器已经很长，且缺了只意味着「不拦」—— 与今天的行为一样 */
    private PurchaseLimitGuard purchaseLimit;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPurchaseLimit(PurchaseLimitGuard purchaseLimit) {
        this.purchaseLimit = purchaseLimit;
    }

    public OrderServiceImpl(ai.neargo.shop.spi.user.AppointmentSlotPort appointmentSlotPort,
                            ai.neargo.shop.spi.product.ReviewQueryPort reviewQueryPort,
                            AfterSaleService afterSaleService,
                            ai.neargo.shop.trade.service.AfterSaleRuleService afterSaleRuleService,
                            OrderMapper orderMapper, SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                            ai.neargo.shop.spi.product.PayModePort payModeService,
                            CartItemMapper cartMapper, GoodsQueryPort goodsPort, StockPort stockPort,
                            MerchantQueryPort merchantPort,
                            ai.neargo.shop.spi.user.MerchantAdminPort merchantAdminPort,
                            AttributionPort attributionPort,
                            CouponPort couponPort, CampaignPort campaignPort, PointsPort pointsPort,
                            ai.neargo.shop.spi.pay.PayChannelMasterPort payChannelMasterPort,
                            ai.neargo.shop.spi.product.PointsRulePort pointsRulePort,
                            SettlePort settlePort,
                            StatusLogMapper statusLogMapper,
                            ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper afterSaleMapper,
                            PickupQueryPort pickupPort,
                            ai.neargo.shop.spi.user.CommunityQueryPort communityQueryPort,
                            ai.neargo.shop.spi.user.UserQueryPort userPort,
                            ai.neargo.shop.spi.user.CommunityQueryPort communityPort,
                            IdempotencyService idempotency, OutboxEventBus eventBus,
                            ai.neargo.shop.spi.user.AdmissionPort admissionPort,
                            ai.neargo.shop.spi.member.MemberEventPort memberEventPort,
                            ai.neargo.shop.spi.user.PersonPort personPort,
                            CloseRuleService closeRuleService,
                            ShipmentTraceQueryPort shipmentTracePort) {
        this.payModeService = payModeService;
        this.appointmentSlotPort = appointmentSlotPort;
        this.reviewQueryPort = reviewQueryPort;
        this.afterSaleService = afterSaleService;
        this.afterSaleRuleService = afterSaleRuleService;
        this.orderMapper = orderMapper;
        this.subOrderMapper = subOrderMapper;
        this.admissionPort = admissionPort;
        this.closeRuleService = closeRuleService;
        this.shipmentTracePort = shipmentTracePort;
        this.itemMapper = itemMapper;
        this.cartMapper = cartMapper;
        this.goodsPort = goodsPort;
        this.stockPort = stockPort;
        this.merchantPort = merchantPort;
        this.merchantAdminPort = merchantAdminPort;
        this.attributionPort = attributionPort;
        this.couponPort = couponPort;
        this.pointsPort = pointsPort;
        this.payChannelMasterPort = payChannelMasterPort;
        this.pointsRulePort = pointsRulePort;
        this.campaignPort = campaignPort;
        this.settlePort = settlePort;
        this.statusLogMapper = statusLogMapper;
        this.afterSaleMapper = afterSaleMapper;
        this.pickupPort = pickupPort;
        this.communityQueryPort = communityQueryPort;
        this.userPort = userPort;
        this.communityPort = communityPort;
        this.idempotency = idempotency;
        this.eventBus = eventBus;
        this.memberEventPort = memberEventPort;
        this.personPort = personPort;
    }

    // ---------------------------------------------------------------- 预览与下单

    @Override
    public ai.neargo.shop.trade.dto.CheckoutCapabilityVO capability(CreateOrderCommand cmd) {
        Split raw = split(cmd);
        Map<String, String> stores = storesOf(cmd, raw);
        // 运费计进额度判断：快递单的「这一单要付多少」含运费（TDD-快递100商家寄件 §8）
        // OrNull：结算能力也被不带登录态的内部调用读（没有用户就拿不到收货地址，运费按基础价算，不因此抛错）
        Split split = withFreight(raw, cmd, stores, SecurityUtils.currentUserNoOrNull());

        List<ai.neargo.shop.trade.dto.CheckoutCapabilityVO.MerchantCapability> rows =
                new ArrayList<>();
        java.util.Set<String> usable = null;
        boolean anyNoInvoice = false;

        for (Group g : split.groups) {
            var cap = merchantPort.payCapabilityOf(g.merchantNo, stores.get(g.key()));
            long amount = g.goodsAmount() + g.freight;
            boolean noInvoice = !cap.invoiceCapable();
            anyNoInvoice = anyNoInvoice || noInvoice;

            /*
             * 自送圆心与半径下发给端上，让它能提前把送不到的地址置灰。
             * 取不到（门店没标点）就是三个 null —— 那正是「这条规则不成立」的表达，
             * 与 requireWithinDeliveryRadius 里的放行是同一件事。
             */
            // 圆心按这一单落在的那家店（多门店时不是默认店）
            var origin = merchantPort.deliveryOrigin(g.merchantNo, stores.get(g.key())).orElse(null);
            rows.add(new ai.neargo.shop.trade.dto.CheckoutCapabilityVO.MerchantCapability(
                    g.merchantNo, g.merchantName, cap.invoiceCapable(),
                    new ArrayList<>(cap.payMethods()),
                    cap.quotaExhausted(), cap.wouldExceed(amount),
                    origin == null ? null : origin.latE6(),
                    origin == null ? null : origin.lngE6(),
                    origin == null ? null : origin.radiusM(), g.storeNo()));

            /*
             * 交集而非并集：一笔支付覆盖整单，有一家不支持这种方式就用不了。
             *
             * 空的支付方式集合当作「未配置」跳过，而不是当作「一种都不支持」——
             * 进件还没走完的商家会是空集，用它求交集会把整单的可用方式清空，
             * 而那家店的货其实是能买的（钱先欠着）。
             */
            if (!cap.payMethods().isEmpty()) {
                usable = usable == null ? new java.util.LinkedHashSet<>(cap.payMethods())
                        : intersect(usable, cap.payMethods());
            }
        }

        /*
         * **未配置返回 null，不返回空数组**。
         *
         * 一个商家都没配支付方式时（进件还没走完），交集从未被赋值 —— 那是「不知道」，
         * 不是「一种都不支持」。返回空数组的话两者在端上长得一模一样，
         * 而端上对空数组的正确动作是**拦住下单**：于是一个完全正常的订单被拦死。
         * 这个错是在浏览器里跑真实数据时才现形的，单测和类型都拦不住。
         */
        /*
         * 支付方式（线上/线下）也取交集，理由与通道相同：一笔支付覆盖整单。
         *
         * **按行取而不是按商家**：四层判定里最里面一层是商品自己的 pay_modes，
         * 同一家店可以一件支持当面付、一件不支持。按商家取会让不支持的那件
         * 跟着支持的一起放行 —— 而下单时 create 会再判一次并拒掉，
         * 于是结算页说能当面付、点下去说不能。
         *
         * ONLINE 永远在集合里（四层判定的约定），所以交集不会空。
         */
        java.util.Set<String> payModes = null;
        for (Group g : split.groups) {
            for (Line line : g.lines) {
                // 带履约判：商家配送 × 线下要门店开了货到付款 —— 与建单同一个入口，结算页不会说一套、提交判一套
                var modes = payModeService.availablePayModes(
                        line.snapshot.goodsNo(), stores.get(g.key()), cmd.fulfillment());
                payModes = payModes == null ? new java.util.LinkedHashSet<>(modes)
                        : intersect(payModes, modes);
            }
        }
        return new ai.neargo.shop.trade.dto.CheckoutCapabilityVO(
                usable == null ? null : new ArrayList<>(usable), anyNoInvoice, rows,
                payModes == null ? List.of(ai.neargo.shop.common.PayModes.ONLINE)
                        : new ArrayList<>(payModes));
    }

    /**
     * 这一单各组的店名。**必须解域**：买家会话带数据域，而门店名查询故意不解域，
     * 不解的话登录后店名全是空（匿名测试看不出来）。
     */
    private Map<String, String> storeNamesOf(Split split) {
        List<String> nos = split.groups.stream().map(Group::storeNo).filter(java.util.Objects::nonNull)
                .distinct().toList();
        if (nos.isEmpty()) {
            return Map.of();
        }
        return ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(() -> merchantPort.storeNames(nos));
    }

    private static java.util.Set<String> intersect(java.util.Set<String> a,
                                                   java.util.Set<String> b) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>(a);
        out.retainAll(b);
        return out;
    }

    @Override
    public OrderVO preview(CreateOrderCommand cmd) {
        String userNo = SecurityUtils.currentUserNo();
        // 团价在预览就要算进去：确认页显示的是「参团 ¥8」，提交后不能变成原价
        Split raw = split(cmd);
        Split priced = repriced(raw, groupQuoteOf(cmd, raw, userNo));
        // 快递运费按模板算进预览：确认页显示的运费就是提交后要付的（TDD-快递100商家寄件 §8）
        Split split = withFreight(priced, cmd, storesOf(cmd, priced), userNo);
        /*
         * **预览也要把配到的自提点算出来**：确认页要在**付款前**按取货点分组说清楚
         * 「本单 2 个取货点」。等到下单响应才知道就晚了 —— 那时钱已经付了。
         * 非 strict：配不出来的商家留空，由确认页标出来，而不是把整页打死。
         */
        var matched = resolvePickups(cmd, split, userNo, false);
        // 距离只在预览这一次算：确认页要说「这个点离你多远」，而历史订单里这个数没有意义
        var distances = pickupDistances(cmd, split, userNo);
        /*
         * 名字在这儿查好一起传进去：Split 是静态类，够不着 port。
         * **预览就要给名字** —— 只给点号的话确认页只能显示一串 PP0001。
         */
        java.util.Map<String, PickupPick> pickups = new java.util.LinkedHashMap<>();
        matched.forEach((merchantNo, pickupNo) ->
                pickups.put(merchantNo, new PickupPick(pickupNo, pickupNameOf(pickupNo),
                        distances.get(pickupNo))));
        // 预览不落库、不锁库存：用户可能在结算页反复改地址与履约方式。
        // 但**优惠要按下单时同一套规则算**，否则结算页显示的金额和实付对不上
        Discounts discounts = discountsOf(cmd, split, userNo);
        return split.toVO(discounts, pickups, remainingOf(split, userNo), storeNamesOf(split))
                .withDiscountLines(discountLinesOf(discounts))
                // 优惠选项与最省组合（批 2）：只在预览算，下单时按顾客提交的选择走
                .withOffers(offersOf(cmd, split, userNo, discounts))
                // 超出配送范围：预览给标记不拦（P6），建单时 requireWithinDeliveryRadius 才拦
                .withOutOfRange(outOfRangeMerchants(cmd, split, userNo));
    }

    /**
     * 预览用：每件设了限购的货的限购数与已买量。
     * 开关关着时返回空 —— 步进器只按库存，与「只显示不拦」一致。
     */
    private Map<String, Quota> remainingOf(Split split, String userNo) {
        Map<String, Integer> limits = split.limitsByGoods();
        if (purchaseLimit == null || limits.isEmpty() || !purchaseLimit.enforced()) {
            return Map.of();
        }
        Map<String, Integer> bought = purchaseLimit.boughtQty(userNo, limits.keySet());
        Map<String, Quota> out = new HashMap<>();
        limits.forEach((goodsNo, limit) ->
                out.put(goodsNo, new Quota(limit, bought.getOrDefault(goodsNo, 0))));
        return out;
    }

    /**
     * 这一张子单关闭后券与积分的去向（待办设计 P3）。**从数据查，不从状态推**：
     * 券看它现在是不是回到了券包，分看这张子单上的 REFUND / CLAWBACK 流水。
     * 非关闭态返回 null。B 端订单详情也用这一份，商家客服接到「我的券呢」时要看得到。
     */
    /**
     * 这张子单减了什么、谁出的钱（C 端与 B 端订单详情共用，批 3 · B8）。
     *
     * <p><b>只列本子单那家店的</b>：优惠记录按主单查，一单多家店时不过滤的话，
     * 每张子单都会把别家店的满减也列出来。
     */
    @Override
    public List<OrderVO.DiscountLine> discountLinesOf(OrdSubOrder sub) {
        if (sub == null || sub.getDiscountAmount() == null || sub.getDiscountAmount() <= 0) {
            return List.of();
        }
        return campaignPort.appliedOf(sub.getOrderNo()).stream()
                .filter(d -> d.merchantNo() == null || d.merchantNo().equals(sub.getEntityNo()))
                .map(d -> new OrderVO.DiscountLine(d.kind(), d.name(), d.amountMinor(), d.funder()))
                .toList();
    }

    @Override
    public OrderVO.Returned returnedOf(OrdSubOrder sub) {
        if (sub == null || !(OrdSubOrder.CANCELLED.equals(sub.getStatus())
                || OrdSubOrder.REFUNDED.equals(sub.getStatus()))) {
            return null;
        }
        String title = couponPort.returnedTitleOf(sub.getOrderNo());
        var pts = pointsPort.returnedOf(List.of(sub.getSubOrderNo()));
        return new OrderVO.Returned(title, pts.refunded(), pts.clawedBack());
    }

    /** 一件货的限购与已买量 */
    private record Quota(int limit, int bought) {
        int left() {
            return Math.max(0, limit - bought);
        }
    }

    /**
     * 每个 SKU 该送几件赠品。
     *
     * <p>按 goodsNo 查规则、按行的购买数算件数。同一商品分散在多行（不同规格）时
     * **各行分别算** —— 合并算会让「买 2 件 A 规格 + 2 件 B 规格」凑出一份赠品，
     * 而商家的「买 2 送 1」说的是同一规格。
     */
    private Map<String, Integer> giftQtyOf(Split split) {
        if (split.items.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, CampaignPort.GiftRule> rules = campaignPort.giftRules(
                split.items.stream().map(i -> i.snapshot.goodsNo()).distinct().toList());
        Map<String, Integer> out = new HashMap<>();
        for (Line line : split.items) {
            CampaignPort.GiftRule rule = rules.get(line.snapshot.goodsNo());
            if (rule == null) {
                continue;
            }
            int n = rule.giftQty(line.qty);
            if (n > 0) {
                out.merge(line.snapshot.skuNo(), n, Integer::sum);
            }
        }
        return out;
    }

    /**
     * 每家商家这单从**哪家门店**出货。
     *
     * <p>顾客选的自提点属于哪家店，货就从哪家店出（V16 起自提点归属到门店）。
     * 此前恒取默认门店 —— 多门店时的表现是「扣了 A 店的库存，顾客却到 B 店去取货」，
     * 自提场景下这是一次直接的履约事故：人到了，货不在。
     *
     * <p><b>只认属于本主体的自提点</b>：顾客可以在邻居家（NEIGHBOR）或平台点取货，
     * 那两类的 ownerStoreNo 为空，此时回落默认门店 ——
     * 「去哪儿取」与「从哪儿发」本来就是两件事。
     *
     * <p><b>抽成一个方法是必须的</b>：算价（门店级满减）、锁库存、写子单三处都要用它，
     * 而三处各算一次的话，迟早出现「按 A 店的活动减了钱、扣了 A 店的库存、
     * 订单却记在 B 店」—— 那种错不报错，只会在对账时表现成三本账互相对不上。
     */
    private Map<String, String> storesOf(CreateOrderCommand cmd, Split split) {
        /*
         * ★ 组的键 → 门店（ADR-031）。门店在拆单时就定了（归属门店，或测试种子按主体落店的结果），
         * 这里不再解析第二遍 —— 两遍各算一次正是「减 A 店的活动、扣 B 店的库存」那类错的来源。
         */
        Map<String, String> out = new LinkedHashMap<>();
        for (Group g : split.groups) {
            if (g.storeNo() != null) {
                out.put(g.key(), g.storeNo());
            }
        }
        /*
         * 状态闸：落到的店必须营业（AC7）—— 与 storesOfEntities 末尾同一道，归属门店也要过。
         * 预览、能力、下单都经过这里，暂停营业的店在预览那一步就拒。
         */
        Map<String, String> statuses = storeStatuses(out.values().stream().distinct().toList());
        for (String storeNo : out.values()) {
            if (!open(statuses, storeNo)) {
                throw BizException.of(ErrorCode.STORE_PAUSED);
            }
        }
        return out;
    }

    /**
     * 同一套解析，但只要主体号 —— <b>拆单前就要用它取门店价</b>，
     * 而那时 {@link Split} 还没建出来。
     */
    // 包内可见：StoreOrderRoutingTest 直接量落店结果 —— OrderVO 不带门店号，从外面看不出单落在哪家店
    Map<String, String> storesOfEntities(CreateOrderCommand cmd, List<String> merchantNos) {
        return storesOfEntities(cmd, merchantNos, Map.of());
    }

    /**
     * 带**件**的落店（TDD-C端商品归属门店与库存校验 AC4/AC5）。
     *
     * <p>候选按业务优先级排好之后交给 {@code goodsQueryPort.firstStoreThatCanFulfil}
     * 挑第一家「在架 ∧ 有货」的。一家都挑不出 → <b>拒单，不退回默认店</b>：
     * 少卖可恢复，把单发给一家既没上架也没货的店不可恢复（那时候人已经付了钱）。
     *
     * @param skuQtyByMerchant 主体号 → 这一单在它名下要发的 SKU 与件数。
     *                         <b>空 map = 不判</b>（取门店价那条路调用时还没拆出件来，
     *                         而它只是用来选价格的口径，选错不产生履约后果）
     */
    Map<String, String> storesOfEntities(CreateOrderCommand cmd, List<String> merchantNos,
                                         Map<String, Map<String, Integer>> skuQtyByMerchant) {
        Map<String, String> out = new HashMap<>();
        String pickupStoreNo = pickupPort.find(cmd.pickupNo())
                .map(ai.neargo.shop.spi.user.PickupQueryPort.PickupBrief::ownerStoreNo)
                .filter(no -> no != null && !no.isBlank())
                .orElse(null);
        /*
         * 买家所在社区 —— 用来挑「真的服务他的那家店」（可见性按门店算 · 第 4 步）。
         *
         * <p><b>不能用 {@code SecurityUtils.currentUserNo()}</b>：它取不到人时**抛**
         * UnauthorizedException，而这个方法会被 {@code capability()} 这类
         * <b>没有登录会话</b>的路径调到（未登录也能看「这单能怎么付」）。
         * 用 currentUser() 的 Optional 版本：取不到人就当成「不知道他在哪个社区」，
         * 回落默认店，与改造前逐字相同。
         *
         * 取不到（没登录、或没设过默认地址）时这一档自然跳过。
         */
        String communityNo = SecurityUtils.currentUser()
                .map(ai.neargo.shop.auth.LoginUser::userNo)
                .flatMap(userPort::communityOf)
                .orElse(null);
        Map<String, String> choices = cmd.storeChoices() == null ? Map.of() : cmd.storeChoices();
        for (String merchantNo : merchantNos) {
            List<String> own = merchantPort.storeNos(merchantNo);
            // 一次订单可以拆给多家商家，自提点只可能属于其中一家（或谁都不属于）
            boolean mine = pickupStoreNo != null && own.contains(pickupStoreNo);
            Map<String, Integer> items = skuQtyByMerchant.getOrDefault(merchantNo, Map.of());
            if (mine) {
                // 人要去那儿取货，改不了；那家店暂停/下架/没货都由下面几道闸各自拒 —— 换店等于让人白跑
                out.put(merchantNo, pickupStoreNo);
                continue;
            }
            /*
             * ★ 顾客在逛哪家店（门户，§2.7）：在 B 店门户里挑的货就由 B 店履约。
             * 不看服务范围 —— 送不送得到由后面的配送闸判，与从默认店下单同一套闸，不在这里另判一遍。
             * 不属于这个主体的门店号忽略（端上记错了、或是别家的），按下面的老规则落。
             */
            String chosen = choices.get(merchantNo);
            if (chosen != null && own.contains(chosen)) {
                /*
                 * 他就是在这家店的门户里挑的货 —— **不换店，也不在这里拒**。
                 *
                 * 这一支只有一个候选，闸门在这里只能改变错误码而改变不了结果，
                 * 而那恰恰会改错：在架与缺货对买家不是同一件事。
                 * 下游两道闸各自给的码是对的 —— 快照的 onSale 给 70076「已下架」、
                 * 锁库存给 20001「库存不足」。合并成一个码之后，
                 * 一个正看着详情页的买家会被告知「库存不足」然后反复重试
                 * （StoreScopedVisibilityFlowTest#goodsOffSaleAtThisStoreCannotBeOrdered
                 * 当场把这个错误决定挡了下来）。
                 */
                out.put(merchantNo, chosen);
                continue;
            }
            Map<String, String> statuses = storeStatuses(own);
            /*
             * ★ **默认店服务得了就还用默认店；服务不了才挑别家。**
             *
             * 要解决的问题是：可见性按门店算之后（第 3 步），买家能看到这件货是因为
             * **某一家**店既摆着它又服务他所在的社区 —— 而单一律落到默认店的话，
             * 可见性与履约对不上：页面上一切正常，单却发给了一家既没有这件货、
             * 也不送这个小区的店。
             *
             * <p><b>但修法刻意是「兜底」而不是「择优」</b>。一开始写的是「取最近的那家」，
             * 查生产数据时发现那样不行：线上那个多门店主体三家店全是 ALL 范围，
             * 也就是**三家都服务任何社区** —— 于是「取最近」会把单从默认店挪到另一家，
             * 而订单的 store_no 决定**结算归属、门店级活动匹配、跨店报表**。
             * 更糟的是那三家里两家坐标相同、一家没坐标，最后是靠 storeNo 字符串排序
             * 才碰巧仍然选中默认店 —— 依赖这种巧合的东西迟早会安静地变。
             *
             * <p>改成兜底之后：默认店服务得了（今天所有商家都是这样）→ 行为与改造前
             * 逐字相同；只有默认店真的不服务这个社区时才挑别家，而那正是原先会出错的场合。
             * 这一批的行为变化面因此缩到只剩那一种情况。
             */
            String defaultStore = merchantPort.defaultStoreNo(merchantNo).orElse(null);
            // 暂停营业的默认店不再接单（§2.7）：此前 READONLY 的默认店照样收单
            boolean defaultOpen = defaultStore != null && open(statuses, defaultStore);
            /*
             * **候选按优先级排好，再一次性交给「发得出吗」那道闸**（AC4/AC5）。
             *
             * 原先是逐条 if 直接落店，于是「默认店服务得了」就结束了 ——
             * 而服务得了不等于它摆着这件货、更不等于它有货。线上实测：
             * 20013 对（社区 × 商品）只因为**非默认店**摆着才可见，那些单会落到
             * 一家既没上架也没库存的默认店，页面一切正常、闸门全绿。
             */
            List<String> candidates = new ArrayList<>();
            if (defaultOpen && (communityNo == null
                    || merchantPort.serves(merchantNo, defaultStore, communityNo))) {
                candidates.add(defaultStore);
            }
            // 默认店服务不了（或暂停了）：挑一家真的服务这个社区的营业店（多家都行时取最近，理由见方法注释）
            String served = communityNo == null ? null
                    : nearestServingStore(merchantNo, communityNo, statuses);
            if (served != null) {
                candidates.add(served);
            }
            /*
             * 剩下的营业店按**创建序**补在后面（`own` 来自 storeNos()，已按 id 升序=最早的店在前）。
             * **只有带件时才用得上**；不带件时闸门取第一顺位。
             *
             * ⚠️ 这里原先是 `.sorted()`——按门店号字符串排。旧业务码是「前缀+时间戳+递增seq」，
             * 字符串序恰好=创建序，于是没人发现这条默默依赖了 ID 格式。业务码改成带随机段之后
             * （ADR-033），字符串序变任意序，落店会随机落到另一家，而不报错。
             * 改回「用 own 自己的创建序」：确定、含义是「最早的店优先」、与 ID 格式无关。
             */
            if (!items.isEmpty()) {
                own.stream().filter(st -> open(statuses, st))
                        .filter(st -> !candidates.contains(st)).forEach(candidates::add);
            } else if (candidates.isEmpty() && !defaultOpen) {
                // 不知道买家在哪个社区、默认店又暂停了：取最早的营业店 —— 必须确定
                own.stream().filter(st -> open(statuses, st)).findFirst()
                        .ifPresent(candidates::add);
            }
            if (candidates.isEmpty()) {
                candidates.add(defaultStore);
            }
            out.put(merchantNo, pickable(merchantNo, candidates, items));
        }
        /*
         * 状态闸：落到的店必须营业（AC7）。放在这里而不是 create 里：预览、「这单能怎么付」、
         * 拆单取门店价都经过这个方法 —— 在这里拒，买家在预览那一步就知道，不会付款时才炸。
         */
        Map<String, String> landed = storeStatuses(out.values().stream().filter(java.util.Objects::nonNull).toList());
        for (String storeNo : out.values()) {
            if (storeNo != null && !open(landed, storeNo)) {
                throw BizException.of(ErrorCode.STORE_PAUSED);
            }
        }
        return out;
    }

    /**
     * 候选里第一家「在架 ∧ 有货」的门店；一家都没有 → <b>拒单</b>。
     *
     * <p>不带件（{@code items} 空）时退回第一顺位 —— 取门店价那条路就是这么调的，
     * 它只决定按谁的价算，选错不产生履约后果。
     *
     * <p>拒用的是 {@link ErrorCode#STOCK_NOT_ENOUGH}：买家看到「库存不足」，
     * 而这确实就是「这批货在任何一家发得出的店里都凑不齐」。不新开一个码 ——
     * 对买家来说「这家店没上架」与「这家店没货」是同一件事：买不到。
     */
    private String pickable(String merchantNo, List<String> candidates, Map<String, Integer> items) {
        List<String> real = candidates.stream()
                .filter(st -> st != null && !st.isBlank()).distinct().toList();
        /*
         * **一个门店都没有 ≠ 一家都发不出。**
         *
         * 这家主体名下没有登记门店时，旧代码把 null 落下去、状态闸跳过 null，
         * 整条按主体级走 —— 那是今天绝大多数商家的样子。把它当成「发不出」去拒，
         * 等于给所有单店商家加一道他们根本不在的闸：
         * 实测 StoreStockFlowTest 的两条 AC8 用例当场红在 20001，而它们的库存是够的。
         */
        if (real.isEmpty()) {
            return null;
        }
        if (items.isEmpty()) {
            return real.get(0);
        }
        return goodsPort.firstStoreThatCanFulfil(merchantNo, real, items)
                .orElseThrow(() -> BizException.of(ErrorCode.STOCK_NOT_ENOUGH));
    }

    /**
     * 这个主体名下**服务该社区**的门店里离得最近的那家；一家都没有时返回 null。
     *
     * <p><b>只在默认店服务不了时才会走到这里</b>（见调用处）—— 所以「取最近」
     * 影响的是一个原先必然出错的场合，不会去动本来就正确的那些单。
     *
     * <p>「服务」的判据与可见性同一个出口（{@code serves}，即 ReachRule）——
     * 另写一套迟早分岔，而分岔的表现是「他看得见却下不了单」或者反过来。
     */
    private String nearestServingStore(String merchantNo, String communityNo, Map<String, String> statuses) {
        List<String> stores = merchantPort.storeNos(merchantNo);
        if (stores.isEmpty()) {
            return null;
        }
        List<String> serving = stores.stream()
                .filter(st -> open(statuses, st))
                .filter(st -> merchantPort.serves(merchantNo, st, communityNo))
                .toList();
        if (serving.isEmpty()) {
            return null;
        }
        if (serving.size() == 1) {
            return serving.get(0);
        }
        var cc = communityPort.coordsOfCommunities(java.util.List.of(communityNo)).get(communityNo);
        var sc = merchantPort.coordsOfStores(serving);
        if (cc == null || sc.isEmpty()) {
            // 算不出距离就按门店号取定的那家 —— **必须确定**，否则同一个买家两次下单可能落到两家店
            return serving.stream().sorted().findFirst().orElse(null);
        }
        return serving.stream()
                .sorted(java.util.Comparator
                        .comparingLong((String st) -> {
                            int[] p = sc.get(st);
                            if (p == null) {
                                return Long.MAX_VALUE;
                            }
                            long dLat = (long) (p[0] - cc[0]);
                            long dLng = (long) (p[1] - cc[1]);
                            return dLat * dLat + dLng * dLng;
                        })
                        .thenComparing(java.util.function.Function.identity()))
                .findFirst().orElse(null);
    }

    /**
     * 优惠合计：**先活动、后券**。预览与下单共用 —— 两处各算一次必然算出两个数。
     *
     * <p>顺序不是随便定的。满减是自动生效的（用户没得选），券是用户挑的；
     * 券作用在满减**之后**的金额上，「这张券帮我省了多少」才是他心里那个增量。
     * 反过来（先券后满减）会让同一张券在不同订单里显示的减免额对不上用户的心算。
     *
     * <p>门槛判定也随之落在活动后金额上 —— 这是保守的一侧：
     * 满 100 减 10 之后剩 95，再要用「满 100 可用」的券就不行了。
     * 宽松的一侧（按原价判门槛）会让商家承担两次优惠而事先算不出来。
     */
    /**
     * @param userNo 用券的人。<b>必须传进来，不能在这里取当前登录人</b> ——
     *               代客下单（{@link #createFor}）下单的是客服、用券的是顾客，
     *               取当前登录人会去扣客服自己的券，而且不会有任何报错
     */
    private Discounts discountsOf(CreateOrderCommand cmd, Split split, String userNo) {
        if (split.groups.isEmpty()) {
            return Discounts.none();
        }
        // 门店级满减只对这单出货的那家店生效 —— 预览与下单走同一个解析，
        // 否则会出现「确认页减了 8 块、提交后没减」
        Map<String, String> stores = storesOf(cmd, split);
        /*
         * 线下付款的单**不带逐件小计**，平台活动在这一单上不生效：平台出资要从资金流里补给商家，
         * 线下没有资金流可补（与平台券不能线下用同一条理由）。平台活动是自动生效的、买家没法不选，
         * 所以这里是「不参与」而不是像平台券那样「拒单」—— 拒了的话活动期间线下单一律下不了。
         */
        // 顾客对活动的选择（批 2）：没选的店按最优；选的那个不成立就拒（ACTIVITY_CHOICE_UNAVAILABLE）
        CampaignPort.Discount auto = campaignPort.autoDiscount(merchantAmounts(cmd, split, stores),
                cmd.activityChoices());
        if (cmd.couponNo() == null || cmd.couponNo().isBlank()) {
            return new Discounts(auto, CouponPort.Allocation.none());
        }
        CouponPort.Allocation coupon = couponPort.allocate(userNo, cmd.couponNo(),
                split.groups.stream()
                        .map(g -> new CouponPort.MerchantAmount(
                                g.merchantNo, g.goodsAmount() - auto.of(g.merchantNo, g.storeNo()), g.storeNo()))
                        .toList());
        return new Discounts(auto, coupon);
    }

    /** 按商家算活动用的金额。预览、下单、枚举最省组合三处共用这一份 */
    private List<CampaignPort.MerchantAmount> merchantAmounts(CreateOrderCommand cmd, Split split,
                                                             Map<String, String> stores) {
        boolean offline = PayModes.OFFLINE.equals(cmd.payMode());
        return split.groups.stream()
                .map(g -> new CampaignPort.MerchantAmount(
                        g.merchantNo, g.goodsAmount(), g.goodsQty(), stores.get(g.key()),
                        // 逐件小计：平台活动只对报名的货生效，门槛按那几件货判（P3）
                        offline ? List.<CampaignPort.GoodsLine>of() : g.lines.stream().map(l -> new CampaignPort.GoodsLine(
                                l.snapshot.goodsNo(), l.amount(), l.qty)).toList()))
                .toList();
    }

    /** 枚举超过这么多组合就退回「活动取最优、再挑最好的券」—— 预览要快，几百组以内都算得动 */
    private static final int MAX_OFFER_COMBOS = 200;

    /**
     * 下单页的优惠选项与<b>最省组合</b>（优惠券全链路梳理 批 2，Q2 + Q4）。
     *
     * <p><b>活动与券一起枚举，不是「先挑最优活动、再在剩下的金额上挑券」</b>：
     * 参加满减后本店金额可能掉到券门槛以下，于是「不参加活动、只用券」反而更省 ——
     * 这正是顾客要自己去点「不参与」最常见的理由，系统应该先替他想到。
     *
     * <p>券能不能用、减多少只问 {@code couponPort.allocate}（与下单同一个实现），这里不另算一遍。
     * 同额时取枚举里靠前的：每家店的选项按「系统默认的最优在前、不参加在最后」排，券「不用」在最前 ——
     * 所以同样省钱的组合里，建议的总是改动最少的那个。
     */
    private OrderVO.Offers offersOf(CreateOrderCommand cmd, Split split, String userNo, Discounts current) {
        Map<String, String> stores = storesOf(cmd, split);
        List<CampaignPort.MerchantAmount> amounts = merchantAmounts(cmd, split, stores);
        List<CampaignPort.AppliedActivity> cands = campaignPort.candidates(amounts);
        /*
         * 按组（门店）键（ADR-031）：同主体两家店各有各的活动选项。
         * 顾客的选择先按门店号找、再按主体号找（老端上只按主体传）。
         */
        Map<String, List<CampaignPort.AppliedActivity>> byMerchant = new LinkedHashMap<>();
        Map<String, Group> groupOf = new LinkedHashMap<>();
        for (Group g : split.groups) {
            groupOf.put(g.key(), g);
            List<CampaignPort.AppliedActivity> mine = cands.stream()
                    .filter(a -> a.merchantNo().equals(g.merchantNo)
                            && java.util.Objects.equals(a.storeNo(), g.storeNo())).toList();
            if (!mine.isEmpty()) {
                // 默认最优在前：与 CampaignPort.pick 同一个取法（同额取先出现的）
                List<CampaignPort.AppliedActivity> sorted = new ArrayList<>(mine);
                sorted.sort((x, y) -> Long.compare(y.amountMinor(), x.amountMinor()));
                byMerchant.put(g.key(), sorted);
            }
        }
        List<OrderVO.MerchantOffers> merchants = new ArrayList<>();
        for (Group g : split.groups) {
            List<CampaignPort.AppliedActivity> mine = byMerchant.get(g.key());
            if (mine == null) {
                continue;
            }
            String choice = cmd.activityChoices() == null ? null
                    : cmd.activityChoices().containsKey(g.key()) ? cmd.activityChoices().get(g.key())
                    : cmd.activityChoices().get(g.merchantNo);
            String chosen = current.auto().applied().stream()
                    .filter(a -> a.merchantNo().equals(g.merchantNo)
                            && java.util.Objects.equals(a.storeNo(), g.storeNo()))
                    .map(CampaignPort.AppliedActivity::activityNo).findFirst()
                    .orElse(CampaignPort.CHOICE_NONE.equals(choice) ? CampaignPort.CHOICE_NONE : null);
            merchants.add(new OrderVO.MerchantOffers(g.merchantNo, g.merchantName,
                    mine.stream().map(a -> new OrderVO.Option(a.activityNo(), a.name(), a.amountMinor())).toList(),
                    chosen, g.storeNo()));
        }

        List<String> coupons = new ArrayList<>();
        coupons.add(null);
        if (userNo != null) {
            coupons.addAll(couponPort.heldUsable(userNo));
        }
        if (merchants.isEmpty() && coupons.size() == 1) {
            return null;
        }
        List<String> mNos = new ArrayList<>(byMerchant.keySet());
        long combos = coupons.size();
        for (String m : mNos) {
            combos *= byMerchant.get(m).size() + 1;
        }
        boolean offline = PayModes.OFFLINE.equals(cmd.payMode());
        Map<String, Long> goodsOf = new HashMap<>();
        split.groups.forEach(g -> goodsOf.put(g.key(), g.goodsAmount()));

        // 活动组合：每家店从 [候选…, 不参加] 里选一个。超过上限只看「全部按最优」这一组
        List<Map<String, CampaignPort.AppliedActivity>> actCombos = new ArrayList<>();
        if (combos > MAX_OFFER_COMBOS) {
            Map<String, CampaignPort.AppliedActivity> best = new LinkedHashMap<>();
            mNos.forEach(m -> best.put(m, byMerchant.get(m).get(0)));
            actCombos.add(best);
        } else {
            actCombos.add(new LinkedHashMap<>());
            for (String m : mNos) {
                List<Map<String, CampaignPort.AppliedActivity>> next = new ArrayList<>();
                for (var base : actCombos) {
                    for (CampaignPort.AppliedActivity a : byMerchant.get(m)) {
                        var c = new LinkedHashMap<>(base);
                        c.put(m, a);
                        next.add(c);
                    }
                    var none = new LinkedHashMap<>(base);
                    none.put(m, null);
                    next.add(none);
                }
                actCombos = next;
            }
        }

        long bestTotal = -1;
        Map<String, CampaignPort.AppliedActivity> bestActs = Map.of();
        String bestCoupon = null;
        for (var acts : actCombos) {
            long actTotal = acts.values().stream().filter(java.util.Objects::nonNull)
                    .mapToLong(CampaignPort.AppliedActivity::amountMinor).sum();
            List<CouponPort.MerchantAmount> after = split.groups.stream()
                    .map(g -> new CouponPort.MerchantAmount(g.merchantNo,
                            goodsOf.get(g.key()) - (acts.get(g.key()) == null
                                    ? 0L : acts.get(g.key()).amountMinor()), g.storeNo()))
                    .toList();
            for (String c : coupons) {
                long couponOff = 0L;
                if (c != null) {
                    try {
                        CouponPort.Allocation al = couponPort.allocate(userNo, c, after);
                        // 当面付用不了平台出资的券（requirePayModeSupported 同一条规则）
                        if (offline && !al.byMerchant()) {
                            continue;
                        }
                        couponOff = al.totalDiscount();
                    } catch (BizException notApplicable) {
                        continue;
                    }
                    if (couponOff <= 0) {
                        continue;
                    }
                }
                if (actTotal + couponOff > bestTotal) {
                    bestTotal = actTotal + couponOff;
                    bestActs = acts;
                    bestCoupon = c;
                }
            }
        }
        List<OrderVO.Choice> suggested = new ArrayList<>();
        for (String m : mNos) {
            CampaignPort.AppliedActivity a = bestActs.get(m);
            Group g = groupOf.get(m);
            suggested.add(new OrderVO.Choice(g.merchantNo, a == null ? CampaignPort.CHOICE_NONE : a.activityNo(),
                    g.storeNo()));
        }
        return new OrderVO.Offers(merchants, suggested, bestCoupon, Math.max(bestTotal, 0L));
    }

    /**
     * 一笔订单上的全部优惠。
     *
     * <p>把两种优惠合成一个对象，而不是让下面的落库代码分别问两次 ——
     * 分别问的话，每加一种优惠就要在主单、子单、VO 三处各改一遍，
     * 而漏改一处的症状是「金额对不上」，最难查的那种。
     */
    /**
     * 把这一单的优惠**拆成给人看的几条**（TDD-C端优惠依据）。
     *
     * <p>买家此前看到的是一个光秃秃的「优惠 −¥10」—— 活动？券？两者叠加？一个字都没有。
     * 后端一直知道（算价时就带着活动号与券名），只是没下发。
     *
     * <p>名字取不到就**不给这一条**，而不是编一个「活动优惠」——
     * 那种占位说法与真名字长得一样，读的人分不出哪个是真的。
     */
    private java.util.List<OrderVO.DiscountLine> discountLinesOf(Discounts d) {
        java.util.List<OrderVO.DiscountLine> out = new java.util.ArrayList<>();
        for (var a : d.auto().applied()) {
            if (a.amountMinor() > 0 && a.name() != null && !a.name().isBlank()) {
                out.add(new OrderVO.DiscountLine(OrderVO.DiscountLine.ACTIVITY, a.name(), a.amountMinor()));
            }
        }
        var c = d.coupon();
        if (c.totalDiscount() > 0 && c.title() != null && !c.title().isBlank()) {
            out.add(new OrderVO.DiscountLine(OrderVO.DiscountLine.COUPON, c.title(), c.totalDiscount()));
        }
        return out;
    }

    private record Discounts(CampaignPort.Discount auto, CouponPort.Allocation coupon) {

        static Discounts none() {
            return new Discounts(CampaignPort.Discount.none(), CouponPort.Allocation.none());
        }

        long total() {
            return auto.total() + coupon.totalDiscount();
        }

        long of(String merchantNo) {
            return auto.of(merchantNo) + coupon.discountOf(merchantNo);
        }

        /** 这一组（主体 + 门店）分到的优惠（ADR-031：子单按门店拆，同主体两组各算各的） */
        long of(String merchantNo, String storeNo) {
            return auto.of(merchantNo, storeNo) + coupon.discountOf(merchantNo, storeNo);
        }

        long merchantFunded(String merchantNo, String storeNo) {
            return auto.of(merchantNo, storeNo) - auto.platformOf(merchantNo, storeNo)
                    + (coupon.byMerchant() ? coupon.discountOf(merchantNo, storeNo) : 0L);
        }

        long platformFunded(String merchantNo, String storeNo) {
            return auto.platformOf(merchantNo, storeNo)
                    + (coupon.byMerchant() ? 0L : coupon.discountOf(merchantNo, storeNo));
        }

        /**
         * 商家出资部分。店铺活动恒为商家出资；**平台活动**（P3）按出资比例拆开，平台那份不算在这里。
         */
        long merchantFunded(String merchantNo) {
            return auto.of(merchantNo) - auto.platformOf(merchantNo)
                    + (coupon.byMerchant() ? coupon.discountOf(merchantNo) : 0L);
        }

        /**
         * 平台出资部分：平台券 + 平台活动里平台出的那份。落进子单 {@code discount_platform}，
         * 结算时算回给商家（{@code gross = 实付 + 平台补贴}），平台随统一结算周期付这笔钱。
         */
        long platformFunded(String merchantNo) {
            return auto.platformOf(merchantNo) + (coupon.byMerchant() ? 0L : coupon.discountOf(merchantNo));
        }
    }

    @Override
    @Transactional
    public OrderVO create(CreateOrderCommand cmd, String idempotencyKey) {
        return createFor(SecurityUtils.currentUserNo(), cmd, idempotencyKey);
    }

    @Override
    @Transactional
    public OrderVO createFor(String userNo, CreateOrderCommand cmd, String idempotencyKey) {
        return createFor(userNo, cmd, idempotencyKey, null);
    }

    @Override
    @Transactional
    public OrderVO createFor(String userNo, CreateOrderCommand cmd, String idempotencyKey,
                             Integer payMinutes) {
        return idempotency.execute(idempotencyKey, "POST /mp/order", userNo, OrderVO.class,
                () -> doCreate(cmd, userNo, payMinutes));
    }

    /**
     * ⚠️ 事务注解放在 {@link #create} 上而不是这里：本方法是被同类的 lambda 调用的，
     * 自调用不走代理，写在这里的 {@code @Transactional} 完全不生效 ——
     * 那样「锁了库存但订单没落库」会变成常态，且测试很难发现。
     */
    private OrderVO doCreate(CreateOrderCommand cmd, String userNo) {
        return doCreate(cmd, userNo, null);
    }

    private OrderVO doCreate(CreateOrderCommand cmd, String userNo, Integer payMinutes) {
        Split raw = split(cmd);
        if (raw.items.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        /*
         * 参团 / 开团：团还能不能参、按什么价，**在锁库存与任何写入之前**判 ——
         * 团已散 / 已过期要在付款前就让买家知道，而不是付完再退。
         */
        var group = groupQuoteOf(cmd, raw, userNo);
        Split split = repriced(raw, group);
        /*
         * 履约门店：**在锁库存之前算好，并与写进子单的那个值同一个来源**。
         *
         * 两处各算一次的话，迟早会出现「扣了 A 店的库存、订单却记在 B 店」——
         * 那种错不会报错，只会在盘点时表现成两家店的账都对不上。
         * 提到校验之前：门店送货方式的闸（方案 v4）要按同一家店判。
         */
        Map<String, String> storeOfMerchant = storesOf(cmd, split);

        requireFulfillmentSupported(cmd.fulfillment(), split, storeOfMerchant, userNo);
        /*
         * 快递运费（TDD-快递100商家寄件 §8 AC15）：与预览同一个 withFreight，写进子单的 freight_amount 就是它。
         * 收货地址命中模板的「不配送」地区：在这里拒，不让买家付完钱才发现寄不到。
         */
        var freightQuotes = freightQuotes(split, cmd, storeOfMerchant, userNo);
        if (freightQuotes.values().stream().anyMatch(FreightPort.Quote::rejected)) {
            throw BizException.of(ErrorCode.OUT_OF_DELIVERY_RANGE);
        }
        /*
         * 商品级限购地区（#3/#4①）：收货地址的省落在某商品的 restricted_regions 里 → 拒。
         * 与上面运费模板的「不配送省」分层——那是整店快递的运费/拒单,这道是「这件货不卖到哪」,
         * 对**所有带收货地址的履约**（快递 / 自送）都查,自提没有收货地址、天然跳过。
         * 门店经营范围里排除的省（「不卖新疆、西藏」）走同一道闸（TDD-经营范围排除地区 AC3）。
         */
        requireNotRegionRestricted(cmd, split, storeOfMerchant, userNo);
        split = applyFreight(split, freightQuotes);
        /*
         * 支付方式的三道校验。**全部前置且只读**，不改上面任何分支的顺序 ——
         * create 是全站最要害的方法，加东西的正确姿势是「在它之前挡住」，
         * 不是「在它中间插一脚」。
         */
        String payMode = requirePayModeSupported(cmd, split, storeOfMerchant);
        if (purchaseLimit != null) {
            // 每人限购：只读、在锁库存之前（与上面几道校验同一个姿势）
            purchaseLimit.require(userNo, split.qtyByGoods(), split.limitsByGoods());
        }
        requireReceiverWhenShipped(cmd, userNo);
        requireWithinDeliveryRadius(cmd, split, userNo);
        /*
         * **自提点在这一刻配出来，不再要求买家事先选**
         * （TDD-C端位置选择-地址取代自提点 §M4）。
         *
         * 端上显式传了点（「换一个取货点」）就用他传的，仍走下面那道
         * 「这家店服不服务这个点」的校验；没传就按买家坐标逐个商家配，
         * 配出来的本来就过滤过许可点，所以那道校验对它是恒真的。
         */
        java.util.Map<String, String> pickupByMerchant = resolvePickups(cmd, split, userNo);
        if (group != null && group.pickupNo() != null
                && Fulfillments.NEIGHBOR_PICKUP.equals(cmd.fulfillment())) {
            // 团按自提点成团（一车送到一个点）：参团的单一律送团的那个点，不按买家坐标另配
            pickupByMerchant = new java.util.HashMap<>(pickupByMerchant);
            pickupByMerchant.put(split.groups.get(0).key(), group.pickupNo());
        }
        requirePickupServed(cmd, split);
        requireAppointmentWhenNeeded(cmd, split, storeOfMerchant);

        /*
         * 占预约名额。**一单一次**，不在建子单的循环里 —— 带时段的单只有一个商家
         * （前置校验保证），循环里调会在将来某次放宽限制时变成重复占位。
         */
        var slot = bookAppointmentSlot(cmd, split, storeOfMerchant);

        String orderNo = BizKey.next(BizKey.ORDER);
        long now = System.currentTimeMillis();

        /*
         * 买赠：算出每行该送几件。
         *
         * **赠品不阻断下单**：库存不够就少送，而不是让整单失败 ——
         * 为了一件免费的赠品把一笔真实成交挡掉，代价与收益完全不成比例。
         * 少送几件在订单里看得见（赠品行的 qty），商家侧也能对上。
         */
        Map<String, Integer> gifts = giftQtyOf(split);

        // ⑤ 锁库存 —— 放在落库之前：库存不足就整单失败，不留半张订单
        try {
            List<StockPort.SkuQty> lock = new ArrayList<>();
            for (Group g : split.groups) {
                String storeNo = storeOfMerchant.get(g.key());
                for (Line i : g.lines) {
                    // 赠品与付费件是同一个 SKU（活动表里没有「赠哪件」），合并成一次锁
                    lock.add(new StockPort.SkuQty(
                            i.skuNo(), i.qty() + gifts.getOrDefault(i.skuNo(), 0), storeNo));
                }
            }
            stockPort.lock(orderNo, lock);
        } catch (RuntimeException e) {
            /*
             * 连赠品一起锁失败时**退回只锁付费件**再试一次：
             * 这一步就是「库存不够少送」的落地 —— 不重试的话，
             * 赠品缺货会表现成「这单买不了」，而用户根本没要那个赠品。
             */
            gifts.clear();
            try {
                List<StockPort.SkuQty> paidOnly = new ArrayList<>();
                for (Group g : split.groups) {
                    String storeNo = storeOfMerchant.get(g.key());
                    for (Line i : g.lines) {
                        paidOnly.add(new StockPort.SkuQty(i.skuNo(), i.qty(), storeNo));
                    }
                }
                stockPort.lock(orderNo, paidOnly);
            } catch (RuntimeException retry) {
                throw BizException.of(ErrorCode.STOCK_NOT_ENOUGH);
            }
        }

        // 优惠：与预览同一套规则（discountsOf 是唯一实现）
        Discounts discounts = discountsOf(cmd, split, userNo);

        /*
         * 子单号**提前生成**：积分抵扣要落到各个子单上（三家里退了一家时才退得准），
         * 而扣减必须在落库之前完成 —— 余额不足要能降级成不抵扣，不能让订单先落库再回滚。
         */
        Map<String, String> subOrderNoOf = new LinkedHashMap<>();
        for (Group g : split.groups) {
            subOrderNoOf.put(g.key(), BizKey.next(BizKey.SUB_ORDER));
        }

        /*
         * 积分抵扣。**券之后、运费之外**：
         *
         * · 券先抵 —— 反过来的话积分把金额压低，券的满减门槛就达不到了，
         *   用户会发现「用了积分反而更贵」
         * · 运费不参与 —— 含运费的话一单全靠积分抵掉，商家一分收不到
         *
         * 端上传的 usePoints 只是意愿值，积分域按四道闸截断。
         * 抵不了就是不抵（返回零值），**不抛异常** —— 为了抵扣失败把一笔真实成交挡掉，
         * 代价与收益完全不成比例，与买赠缺货少送是同一条原则。
         */
        PointsPort.Deduction points = cmd.usePoints() == null || cmd.usePoints() <= 0
                ? PointsPort.Deduction.none()
                : pointsPort.deduct(userNo, cmd.usePoints(), split.groups.stream()
                        .map(g -> new PointsPort.Target(g.merchantNo,
                                g.goodsAmount() - discounts.of(g.merchantNo, g.storeNo()),
                                subOrderNoOf.get(g.key())))
                        /*
                         * 端与支付方式一起传进去：能不能用积分抵扣是平台策略，
                         * 判定收在积分域一处。**传的是本次请求的端**（cmd.payScene()
                         * 来自 X-Client），核销判定的对象就是当前端 ——
                         * 发放那边恰好相反，读的是订单快照，别把两者写混。
                         */
                        .toList(), payMode, cmd.payScene());

        /*
         * 线下支付**不能用平台券** —— 券要按出资方拆开看：
         *   商家券：商家自己少收，与积分同理，平台不介入 → 可以用
         *   平台券：平台要把补贴的钱给商家，而线下**没有资金流可补** → 不行
         * 硬发就是平台白送且无处对账。区分依据是现成的：
         * 下面落库时本来就要分 discountPlatform / discountMerchant 两列。
         *
         * **拦在这里而不是支付后**：付过钱再告诉他「这张券不能用」，他要先退款才能重下。
         */
        if (PayModes.OFFLINE.equals(payMode) && discounts.total() > 0
                && split.groups().stream().anyMatch(g -> discounts.platformFunded(g.merchantNo(), g.storeNo()) > 0)) {
            throw BizException.of(ErrorCode.PLATFORM_COUPON_OFFLINE_FORBIDDEN);
        }

        // ⑥ 落库：主单 + 子单 + 行，同一事务
        OrdOrder order = new OrdOrder();
        order.setOrderNo(orderNo);
        order.setUserNo(userNo);
        // 积分抵扣计入实付，但**不计入 discountAmount** —— 那一列是营销优惠，
        // 混进去会让「这单让了多少利」算错，而分账要按它拆出资方
        order.setPayAmount(split.payAmount() - discounts.total() - points.amountMinor());
        order.setGoodsAmount(split.goodsAmount());
        order.setFreightAmount(split.freightAmount());
        order.setDiscountAmount(discounts.total());
        order.setCurrency(CURRENCY_CNY);
        /*
         * 线下支付落 WAIT_OFFLINE_PAY：钱还没收到，**不能算已支付** ——
         * 直接落 PAID 的话，商家一旦收不到钱，退款链路要去退一笔平台从没收过的钱。
         * 库存照常锁（下面 confirm 之后才转实扣），与线上单一致。
         */
        order.setStatus(PayModes.OFFLINE.equals(payMode)
                ? OrdOrder.WAIT_OFFLINE_PAY : OrdOrder.WAIT_PAY);
        /*
         * 下单端快照。**列从 V1 baseline 就有，缺的一直是这一行写入。**
         * 积分发放的端判定读它 —— 发放发生时可能没有任何「当前端」
         * （超时自动完成是系统动作），只有下单这一刻的端是确定的。
         */
        order.setPayScene(cmd.payScene());
        /*
         * 社区固化到主单上。**运营按社区做数据域隔离** —— 不写的话，
         * 平台端按社区筛订单永远是空的，而列表本身是好的，看起来只是「这个社区没单」。
         *
         * 固化而不是每次现查用户当前绑定：用户搬家换社区后，历史订单仍属于当时那个社区，
         * 否则昨天的单会跳到新社区的报表里。
         */
        userPort.communityOf(userNo).ifPresent(order::setCommunityNo);
        // 时限：默认按平台关单策略；代客单由调用方给（电话下单的人要挂了电话才去付）
        int minutes = payMinutes == null ? closeRuleService.unpaidMinutes() : payMinutes;
        order.setPayDeadlineAt(now + minutes * 60_000L);

        /*
         * 弱主体限额（F-6）。**按拆单后的每个商家分别判**，不是按整单总额：
         * 限额是平台对单个商家的敞口上限，跨商家合单再按总额判，
         * 会因为同车买了别家的东西而误拦这一家。
         *
         * 放在 insert 之前：虽然事务回滚也能收拾，但先落库再抛会让
         * 订单号消耗掉、日志里留下一条永远查不到的单。
         */
        Map<String, Boolean> needsConfirm = new java.util.HashMap<>();
        /*
         * 社区集单：每个商家这一批货此刻属于哪一期（没有集单商品的为空）。
         * **在落库之前取**：份数满、两个集单混在一单这两种拒绝要在任何写入之前发生。
         * 归属按「下单这一刻」判 —— 截单前 1 秒的单属于今天，与任务什么时候扫到无关。
         */
        Map<String, ai.neargo.shop.spi.marketing.PeriodPort.PeriodTicket> ticketOf = new java.util.HashMap<>();
        // 团单不挂期：一件货同时在拼团与集单里时，按团走（团价已经算进去了，两套截单口径不叠）
        if (periodPort != null && group == null) {
            for (Group g : split.groups) {
                List<String> goodsNos = g.lines.stream().map(l -> l.snapshot.goodsNo()).distinct().toList();
                int qty = g.lines.stream().mapToInt(Line::qty).sum();
                periodPort.ticketFor(g.merchantNo, goodsNos, qty, now)
                        .ifPresent(t -> ticketOf.put(g.key(), t));
            }
        }
        /*
         * 经营额度 / 单笔上限按**主体汇总**判（ADR-031 §2.7）：同主体两家店各一张子单，
         * 逐组判的话两部分各自过、合起来却超了。
         */
        Map<String, Long> payByEntity = new LinkedHashMap<>();
        for (Group g : split.groups) {
            payByEntity.merge(g.merchantNo,
                    g.goodsAmount() + g.freight - discounts.of(g.merchantNo, g.storeNo()), Long::sum);
        }
        payByEntity.forEach((entityNo, pay) ->
                admissionPort.requireOrderAllowed(entityNo, pay, () -> paidAmountToday(entityNo)));
        for (Group g : split.groups) {
            long merchantPay = g.goodsAmount() + g.freight - discounts.of(g.merchantNo, g.storeNo());
            /*
             * 准入矩阵（§7.7）：这个主体能不能用这种履约方式。
             * 结论在下单这一刻定死并落进子单 —— 商家事后换自提点运营者，
             * 历史单的判定不该跟着变。
             */
            needsConfirm.put(g.key(), admissionPort.requireFulfillmentAllowed(
                    g.merchantNo, cmd.fulfillment(), cmd.pickupNo()));

            /*
             * 收款额度（P2-3）。**在下单这一步拦，而不是等付款时通道拒绝**：
             * 通道拒绝表现为「支付失败」四个字，买家不知道该换一家买，
             * 商家不知道该去升主体，运营不知道该去核对额度口径 —— 三方都卡住。
             *
             * 用 wouldExceed 而不是 quotaExhausted：正好卡在额度边缘的那一单，
             * 放过去仍然会在通道侧失败。
             */
            var cap = merchantPort.payCapabilityOf(g.merchantNo, storeOfMerchant.get(g.key()));
            if (cap.wouldExceed(merchantPay)) {
                throw BizException.of(ErrorCode.MERCHANT_QUOTA_EXHAUSTED);
            }
        }

        orderMapper.insert(order);

        List<String> subOrderNos = new ArrayList<>();
        for (Group g : split.groups) {
            String subOrderNo = subOrderNoOf.get(g.key());
            subOrderNos.add(subOrderNo);

            OrdSubOrder sub = new OrdSubOrder();
            sub.setSubOrderNo(subOrderNo);
            sub.setOrderNo(orderNo);
            sub.setUserNo(userNo);
            sub.setEntityNo(g.merchantNo);
            /*
             * 社区冗余进子单（V137）：数据域的锚点只能是**本表上的一列**，
             * 而社区在主单上。不写这一句，接上数据域之后配了社区域的运营
             * 打开订单页是整页空白 —— 见 OrdSubOrder#communityNo。
             */
            sub.setCommunityNo(order.getCommunityNo());
            /*
             * 双写门店（M2）：entity_no 是**结算键**（分账/积分/对账都按它），
             * store_no 是**履约键**（发货/自提/评价/门店报表按它）。
             * 单店时两者恒等 —— 这正是多门店能分阶段发布的原因。
             *
             * 取不到门店不让下单失败：订单照常创建，履约侧按「空 → 默认门店」兜底。
             * 为了一个统计维度把下单挡住，代价和收益完全不成比例。
             */
            // 与上面锁库存用的是同一个 map —— 两处各算一次会让「扣了 A 店、单记在 B 店」
            sub.setStoreNo(storeOfMerchant.get(g.key()));
            sub.setEntityName(g.merchantName);
            sub.setFulfillment(cmd.fulfillment());
            // 预约时段落到子单：商家的待服务列表按它排，买家的订单卡按它显示「几点」。
            // 只在预约类履约上写，其余留空 —— 一个不需要预约的单带着时间是噪音
            if (Fulfillments.NEEDS_APPOINTMENT.contains(cmd.fulfillment())) {
                /*
                 * 抢到时段的话，appointment_at **由时段推出**，不信端上传的那个。
                 * 两个来源写同一列的话，买家可以约 9 点的档、把 appointmentAt 传成 15 点 ——
                 * 商家的待服务列表按 15 点排，而名额扣在 9 点那一格。
                 */
                sub.setAppointmentAt(slot != null ? slot.startAt() : cmd.appointmentAt());
                sub.setAppointmentSlotNo(slot == null ? null : cmd.appointmentSlotNo());
            }
            /*
             * **按商家取各自的点**：属于多个就是多个（子单本来就按商家拆，
             * 而 pickup_no / pickup_name / pickup_owner_ref 全在子单上）。
             * 端上显式传了点时这张表里每家都是同一个值，行为与改造前逐字相同。
             */
            String pickupNo = pickupByMerchant.getOrDefault(g.key(), cmd.pickupNo());
            sub.setPickupNo(pickupNo);
            /*
             * 自提点快照。名称是给页面看的（改名不该影响历史订单），
             * **承接方是给钱看的**（换了承接门店不该改写历史订单算谁的）——
             * 两者一次取出，别分两处查，那会在并发改点时取到不一致的两半。
             */
            var brief = pickupPort.find(pickupNo);
            sub.setPickupName(brief.map(p -> p.name()).orElse(null));
            sub.setPickupOwnerRef(brief.map(p -> p.ownerRef()).orElse(null));
            sub.setPickupOwnerStoreNo(brief.map(p -> p.ownerStoreNo()).orElse(null));
            String subAddr = cmd.addressFor(g.storeNo(), g.merchantNo());
            sub.setAddressId(subAddr);
            /*
             * 收件人快照（V69）：与上面的 pickupName 同一个理由 ——
             * usr_address 可改可删，买家下完单改成新家，商家看到的就跟着变了，
             * 而货已经按旧地址在路上。
             *
             * **取不到不让下单失败**：自提单本来就没有 addressId；
             * 快递/自送单万一取不到，宁可让商家看到「地址：—」去问一句，
             * 也不该把已经付过钱的单挡在这里。
             */
            userPort.receiverOf(userNo, subAddr).ifPresent(r -> {
                sub.setReceiverName(r.name());
                sub.setReceiverPhone(r.phone());
                sub.setReceiverAddress(r.address());
            });
            // ★ 归因在下单这一刻固化，不是结算时回查（TDD-backend §7.4）
            sub.setTrafficSource(attributionPort.resolveTrafficSource(userNo, g.merchantNo));
            sub.setGoodsAmount(g.goodsAmount());
            sub.setFreightAmount(g.freight);
            long discount = discounts.of(g.merchantNo, g.storeNo());
            sub.setDiscountAmount(discount);
            // 出资方分列（Q9）：合成一列的话 M7 分账无法判断该扣谁的钱
            sub.setDiscountPlatform(discounts.platformFunded(g.merchantNo, g.storeNo()));
            sub.setDiscountMerchant(discounts.merchantFunded(g.merchantNo, g.storeNo()));
            // 积分快照：结算与售后直接读这两列，不用回查积分流水
            long pointsAmount = points.amountOf(subOrderNo);
            sub.setPointsDeduct((int) points.pointsOf(subOrderNo));
            sub.setPointsDeductMinor(pointsAmount);
            sub.setPayAmount(g.goodsAmount() + g.freight - discount - pointsAmount);
            sub.setRequireBuyerConfirm(
                    Boolean.TRUE.equals(needsConfirm.get(g.key())) ? 1 : 0);
            sub.setStatus(OrdSubOrder.WAIT_PAY);
            sub.setRemark(cmd.remark());
            if (group != null) {
                // 开团在这一刻建团（发起人 = 下单人），参团再判一次 —— 与订单同一个事务，下单失败团也不留
                sub.setGroupNo(groupJoinPort.bind(userNo, group, pickupNo));
            }
            var ticket = ticketOf.get(g.key());
            if (ticket != null) {
                // 提货日写进子单：履约批次与自提点看板按它分天，而不是按下单日（集单是今天下明天提）
                sub.setPeriodNo(ticket.periodNo());
                sub.setArriveDate(ticket.pickupDate());
            }
            subOrderMapper.insert(sub);
            appendStatusLog(subOrderNo, OrdSubOrder.WAIT_PAY, "已下单，待付款",
                    OrdStatusLog.BY_USER, userNo);

            for (Line line : g.lines) {
                OrdItem item = new OrdItem();
                item.setSubOrderNo(subOrderNo);
                item.setOrderNo(orderNo);
                item.setGoodsNo(line.snapshot.goodsNo());
                item.setSkuNo(line.snapshot.skuNo());
                item.setTitle(line.snapshot.title());
                item.setCover(line.snapshot.cover());
                item.setSpec(line.snapshot.spec());
                item.setPrice(line.snapshot.price());
                item.setQty(line.qty);
                item.setAmount(line.amount());
                item.setCategoryType(line.snapshot.categoryType());
                // 二级类目快照：积分按类目发放时读它，不现查商品（商品可以改类目）
                item.setCategoryNo(line.snapshot.categoryNo());
                itemMapper.insert(item);

                int giftQty = gifts.getOrDefault(line.snapshot.skuNo(), 0);
                if (giftQty > 0) {
                    /*
                     * 赠品作为**独立的一行**，价格 0、amount 0、is_gift=1。
                     * 不合并进付费行（把 qty 加上去）—— 那样订单里就分不清
                     * 「买了 3 件」还是「买 2 件送 1 件」，而这两者的售后与分账都不同。
                     */
                    OrdItem gift = new OrdItem();
                    gift.setSubOrderNo(subOrderNo);
                    gift.setOrderNo(orderNo);
                    gift.setGoodsNo(line.snapshot.goodsNo());
                    gift.setSkuNo(line.snapshot.skuNo());
                    gift.setTitle(line.snapshot.title());
                    gift.setCover(line.snapshot.cover());
                    gift.setSpec(line.snapshot.spec());
                    gift.setPrice(0L);
                    gift.setQty(giftQty);
                    gift.setAmount(0L);
                    gift.setCategoryType(line.snapshot.categoryType());
                    gift.setCategoryNo(line.snapshot.categoryNo());
                    gift.setIsGift(true);
                    itemMapper.insert(gift);
                }
            }
        }

        // 清掉已下单的购物车行
        cartMapper.delete(Wrappers.<TrdCartItem>lambdaQuery()
                .eq(TrdCartItem::getUserNo, userNo)
                .in(TrdCartItem::getSkuNo, split.items.stream().map(Line::skuNo).toList()));

        /*
         * 核销券。放在落库之后：券核销失败要能连订单一起回滚。
         *
         * **把分摊结果一起带过去**：营销域要记一行「这一单这张券减了多少」，
         * 而那个数只有这里知道 —— 它是这一单算价的结果，事后重算会因为
         * 券的门槛/封顶被改过而对不上。
         */
        couponPort.markUsed(userNo, cmd.couponNo(), orderNo, discounts.coupon());

        /*
         * 活动扣限量。**放在这里而不是算价那一步**：算价会被反复调用
         * （预览、改地址、改数量），在那儿扣的话，一个只是看看的用户能把限量耗光。
         * 与订单同事务：扣量失败要能连订单一起回滚，否则会「量扣了、单没成」。
         */
        campaignPort.commit(userNo, orderNo, discounts.auto());

        // ⑦ 发事件（只写 outbox，与业务同事务）
        eventBus.publish(new OrderEvents.OrderCreated(orderNo, userNo, subOrderNos, split.payAmount()));

        // 下单返回支付视角：端上下一步就是去收银台。**按下单归属的那个人查**，
        // 代客下单时下单的是客服、属主是顾客。此刻还没有运单，traceOf 必然为 null，
        // 展示渠道传什么都不影响 —— 给 MP 占位
        return detailOf(orderNo, userNo, "MP");
    }

    // ---------------------------------------------------------------- 支付

    @Override
    public ai.neargo.shop.trade.dto.OrderPayMethodVO payMethods(String orderNo) {
        /*
         * **主单号、子单号都认。** C 端「我的订单」与订单详情给的是子单号（Q6 订单视角），
         * 「去支付」把它原样带进收银台；这里只认主单号的话，从列表进来的每一单都是
         * 「数据不存在」，而从结算页进来的（主单号）一切正常 —— 2026-09-07 真机抓到。
         * 下游一律用解析出来的主单号，别再用入参。
         */
        OrdOrder order = resolveOrder(orderNo);
        String mainNo = order.getOrderNo();
        List<OrdSubOrder> subs = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getOrderNo, mainNo)));

        /*
         * **交集规则与结算页逐字一致** —— 一笔支付覆盖整单，有一家不支持就用不了；
         * 而「一家都没配」当作未配置放行，不当作「一种都不支持」。
         *
         * 两处算出不同结果的话，用户在结算页看到「可以用微信」，
         * 到了收银台却没有微信 —— 而两边各自都「对」。
         */
        /*
         * ⚠️ 比的是**通道**，不是支付方式。
         *
         * 第一版在这里用了 {@code payCapabilityOf().payMethods()} ——
         * 那是支付方式集合（JSAPI / H5），拿通道码去比永远比不中，
         * 症状是「进过件的商家一种方式都没有」。
         * <b>这正是领域模型里点名的那个最容易犯的错，而我自己犯了一次。</b>
         */
        java.util.Set<String> usable = null;
        boolean anyConfigured = false;
        for (OrdSubOrder sub : subs) {
            java.util.Set<String> mine =
                    merchantPort.activeChannelsOf(sub.getEntityNo(), sub.getStoreNo());
            if (mine.isEmpty()) {
                continue;                       // 未进件：跳过，不参与交集
            }
            anyConfigured = true;
            usable = usable == null ? new java.util.LinkedHashSet<>(mine)
                    : intersect(usable, mine);
        }

        /*
         * 不可用的也要返回并带原因。过滤掉的话用户会问
         * 「为什么别人有支付宝我没有」，而客服答不上来。
         */
        String market = subs.isEmpty() ? null
                : merchantPort.marketOf(subs.getFirst().getEntityNo());
        List<String> payable = payChannelMasterPort.payableChannels(market);
        List<ai.neargo.shop.trade.dto.OrderPayMethodVO.Method> methods = new ArrayList<>();
        for (String ch : payChannelMasterPort.enabledChannels(market)) {
            boolean gatewayReady = payable.contains(ch);
            boolean merchantOk = usable == null || usable.contains(ch);
            String reason = !gatewayReady ? "该支付方式暂未开通"
                    : !merchantOk ? "本单中有店铺尚未开通这种收款方式" : null;
            methods.add(new ai.neargo.shop.trade.dto.OrderPayMethodVO.Method(
                    ch, ch, payChannelMasterPort.channelName(ch),
                    gatewayReady && merchantOk, reason));
        }
        // 币种跟着通道走（与结算单同一口径）；取不到时留空，端上据此不显示金额
        String currency = methods.stream().filter(m -> m.available()).findFirst()
                .map(m -> payChannelMasterPort.currencyOf(m.payChannel())).orElse(null);
        return new ai.neargo.shop.trade.dto.OrderPayMethodVO(currency, anyConfigured, methods);
    }

    @Override
    public PayResult pay(String orderNo, String payChannel) {
        // 同 payMethods：子单号也要能进收银台；下游（记账、商户单号）只认主单号
        OrdOrder order = resolveOrder(orderNo);
        orderNo = order.getOrderNo();
        if (!OrdOrder.WAIT_PAY.equals(order.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        if (order.getPayDeadlineAt() != null && order.getPayDeadlineAt() < System.currentTimeMillis()) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        /*
         * **先在支付域记一行「发起了」，再把参数给端上。**
         *
         * 没有这一行的话，「用户付了钱而我方没收到回调」在库里没有任何痕迹 ——
         * 收款对账轴查的正是「停在 PENDING 的收款」，没有起点就没有可查的对象。
         * 2026-09-01 之前这一行不存在，于是那条轴每轮都报「没有差异」。
         *
         * 放在返回参数**之前**：先给参数再记账的话，两者之间进程挂掉，
         * 用户手上有一个能付的凭据，而我方一无所知 —— 那笔钱进来后没有任何东西认领它。
         * 幂等在支付域侧按 out_trade_no 做，用户反复点不会多出行。
         */
        /*
         * **商户单号由支付域给**（2026-09-01 改）：它独立于订单号 ——
         * 一笔订单支付失败后重试必须换新号，通道要求商户订单号唯一，
         * 而关掉的号不能复用。用订单号当商户单号的话，
         * 那笔订单永远只能向通道下一次单，症状是「点了没反应」。
         *
         * 复用未终态的收款时返回的是已有那笔的号 ——
         * 用户在收银台点两次不会在通道那边多出一个未支付单。
         */
        /*
         * **走网关下单**（S4 · 2026-09-02）。
         *
         * 此前这里通道写死 "STUB"、直接编一组假参数返回给端上 ——
         * <b>网关体系建好了，而「支付本身」这一步从来没走过它</b>，
         * PayGatewayRouter 只被对账回查用到。
         *
         * 于是「下单 → 向通道下单 → 拿参数」这一段<b>在真通道接上那天才第一次被执行</b>，
         * 而那是最不该第一次执行它的时候。
         */
        String channel = resolvePayChannel(order, payChannel);
        var init = settlePort.initPayment(new SettlePort.PaymentOpen(
                orderNo, order.getUserNo(), null, channel,
                order.getPayAmount() == null ? 0L : order.getPayAmount(),
                payDescription(orderNo)));

        if (!init.success()) {
            /*
             * **下单失败要抛，不能返回一组空参数。**
             *
             * 返回空参数的话端上会唤起一个付不了的收银台，而用户看到的是
             * 「点了没反应」—— 那是这条链上最难查的一类症状：
             * 订单在、流水在（已关闭）、日志里只有一行 warn。
             */
            // 网关原文进日志不进响应：它可能带外部厂商的措辞，且这条码的文案没有占位符，
            // 原来那个参数被 MessageFormat 静默丢掉 —— 排查时两头都没有
            log.warn("[pay] 下单失败 order={} channel={}：{}", orderNo, init.payChannel(), init.message());
            throw BizException.of(ErrorCode.PAY_CHANNEL_UNAVAILABLE);
        }
        /*
         * **免支付通道：就地把订单推成已支付。**
         *
         * 支付域那边已经结清了（SettlePortImpl 里那一段），这里做的是它的下游投影。
         * 0 元单没有任何外部系统会回调我们，不在这里推的话订单会一直停在待支付 ——
         * 而那正是本方案要修的缺陷。
         *
         * markPaid 自身幂等（已是 PAID 直接返回），用户连点两下不会推乱。
         */
        boolean settled = ai.neargo.shop.common.PayChannels.FREE.equals(init.payChannel());
        if (settled) {
            markPaid(orderNo, init.payChannel(), init.outTradeNo());
        }
        return new PayResult(orderNo, init.payChannel(), init.payParams(), settled);
    }

    @Override
    public OrderVO payResult(String orderNo) {
        return detail(orderNo, null);
    }

    @Override
    @Transactional
    public void markPaid(String orderNo, String payChannel, String payTradeNo) {
        OrdOrder order = orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, orderNo).last("limit 1"));
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (OrdOrder.PAID.equals(order.getStatus())) {
            return;   // 幂等：回调会重发，重复到达不能重复扣库存、重复发事件
        }
        OrderStateMachine.assertOrderTransit(order.getStatus(), OrdOrder.PAID);

        order.setStatus(OrdOrder.PAID);
        order.setPayChannel(payChannel);
        order.setPayTradeNo(payTradeNo);
        order.setPaidAt(System.currentTimeMillis());
        orderMapper.updateById(order);

        // 锁定转实扣
        stockPort.confirm(orderNo);

        java.util.Set<String> bonusGranted = new java.util.HashSet<>();
        for (OrdSubOrder sub : subOrders(orderNo)) {
            /*
             * **支付成功后落哪个状态，由履约方式决定**（《订单状态-统一整理》§2.2）。
             *
             * 实物类落 WAIT_FULFILL：商家要备货、要发货，得先动手。
             * 服务类（到店核销）落 FULFILLING：码已出，买家立刻能去用，
             * 商家没有任何前置动作 —— 把它丢进「待发货」，
             * 界面会说「待发货」而根本没有东西要发。**不是文案错，是状态落错了。**
             */
            boolean serviceLike = Fulfillments.SERVICE_LIKE.contains(sub.getFulfillment());
            String next = serviceLike ? OrdSubOrder.FULFILLING : OrdSubOrder.WAIT_FULFILL;
            OrderStateMachine.assertSubOrderTransit(sub.getStatus(), next);
            sub.setStatus(next);
            // 核销码在支付成功后生成：未付款的订单不该有能核销的码。
            // 全局唯一由 uk_verify_code 兜底 —— 撞码时插入失败总比核销台扫出两单强
            sub.setVerifyCode(newUnusedVerifyCode());
            subOrderMapper.updateById(sub);
            appendStatusLog(sub.getSubOrderNo(), next,
                    serviceLike ? "支付成功，凭码到店使用" : "支付成功，待备货",
                    OrdStatusLog.BY_SYSTEM, null);

            // 服务类在这一刻就要向微信报发货（实物类走各自的发货/到货迁移点）
            notifyShippingOnPaid(sub);

            /*
             * 参团单：**付款成功才算成员**（设计 D1）。团在付款之前已经散了 / 过期了的，
             * 钱付进来了却没有团可参 —— 提交后整张子单系统全额退款。
             * 放在提交之后：退款要读到已支付的子单，且不能让退款的失败回滚掉这笔支付。
             */
            if (sub.getGroupNo() != null && groupJoinPort != null) {
                var joined = groupJoinPort.onPaid(sub.getGroupNo(), sub.getSubOrderNo(), order.getUserNo());
                if (joined == ai.neargo.shop.spi.marketing.GroupJoinPort.PaidOutcome.CLOSED) {
                    final String subNo = sub.getSubOrderNo();
                    AfterCommit.run("团已结束退款 subOrderNo=" + subNo,
                            () -> afterSaleService.systemRefund(subNo, "拼团已结束，自动退款",
                                    "付款时团已结束，自动退款"));
                }
            }

            /*
             * 入会与会员指标（P1）。**在发分之前** —— 两者互不依赖，
             * 但会员那条更靠近「他是谁」，出问题也更好排查。
             *
             * <p><b>没有人档就什么都不做</b>：微信登录没授权手机号的人不入会。
             * 会员必须有已验证手机号是准入规则，而交易永远优先 ——
             * 他照常买到东西，商家会在会员页顶部看到「另有 N 位买家未绑手机号，未计入」。
             *
             * <p>失败不阻塞支付：这一步是派生数据，夜里的全量重算会兜住；
             * 而支付回调抛异常会让渠道重试，重试又会撞上「订单已支付」的幂等分支。
             */
            try {
                String personNo = personPort.findByUser(order.getUserNo())
                        .map(ai.neargo.shop.spi.user.PersonPort.PersonView::personNo).orElse(null);
                memberEventPort.onOrderPaid(new ai.neargo.shop.spi.member.MemberEventPort.OrderPaid(
                        sub.getSubOrderNo(), order.getUserNo(), personNo,
                        sub.getEntityNo(), sub.getStoreNo(),
                        sub.getPayAmount() == null ? 0L : sub.getPayAmount(),
                        order.getPaidAt()));
            } catch (RuntimeException e) {
                log.warn("[member] 入会失败 sub={}：{}", sub.getSubOrderNo(), e.toString());
            }

            /*
             * 发分。**基数是实付金额**（已扣券与积分），不含运费 ——
             * 拿运费也计分的话，一单加一次运费就能多赚一笔分。
             *
             * 进的是 pending_balance（待生效）而不是 balance：售后期内退款要把分收回，
             * 而已经花出去的分收不回来。转正任务本批不做。
             *
             * points_granted 是幂等标记：支付回调会重发，这个方法开头的
             * 「已 PAID 直接返回」挡掉大部分，但并发回调仍可能同时进来，
             * 所以这里再挡一层 —— 重复发分是**凭空印钱**，比重复扣库存严重。
             */
            /*
             * 累加收款额度用量（P2-3）。
             *
             * 放在这个循环里而不是主单上：额度是**按商家**算的，
             * 跨商家合单时一笔支付要分别记到各家头上。
             * 方法开头「已 PAID 直接返回」保证了不会重复累加。
             */
            merchantAdminPort.accruePayQuota(sub.getEntityNo(), sub.getStoreNo(),
                    sub.getPayAmount() == null ? 0L : sub.getPayAmount());

            if (!Boolean.TRUE.equals(sub.getPointsGranted())) {
                /*
                 * 发积分**在支付状态提交之后**做，与生成结算单同一条理由：
                 * 支付域独立成进程后就没有共享事务了，而这里后面还有事件发布 ——
                 * 中间任何一处抛异常，今天会把积分一起回滚，独立之后会留下
                 * 「用户拿到了分、而订单回滚了」。
                 *
                 * <p>推到提交之后，两种失败方向都是安全的：业务回滚 → 根本没发；
                 * 业务提交而发分失败 → 用户这次没拿到分，标记也没写上，
                 * <b>而 grantOnPay 现在自己按 EARN 流水幂等</b>，重试不会多发。
                 */
                final String subNo = sub.getSubOrderNo();
                final String userNo = order.getUserNo();
                final String entityNo = sub.getEntityNo();
                final long base = sub.getPayAmount() == null ? 0L
                        : sub.getPayAmount() - (sub.getFreightAmount() == null ? 0L : sub.getFreightAmount());
                final String scene = order.getPayScene();
                // 自己组合的「送积分」：随常规积分一起发（同一次发放幂等、同一笔费用金）
                // 活动赠积分按主体记（pmt_apply 没有门店维度）：同主体多张子单只发一次（ADR-031 §2.7）
                final long bonus = bonusGranted.add(entityNo) ? campaignPort.bonusPoints(orderNo, entityNo) : 0L;
                AfterCommit.run("发放积分 subOrderNo=" + subNo,
                        () -> grantPointsAfterPay(subNo, userNo, entityNo, base, payChannel, scene, bonus));
            }
        }

        /*
         * 结算单**在支付状态提交之后**生成。
         *
         * <p>此前它写在事务中段，靠「同生共死」保证一致 —— 那在单体里成立，
         * 但支付域独立成进程后就没有共享事务了。而这里的顺序尤其要紧：
         * 它后面还有事件发布与子单循环，**任何一处抛异常，今天会把结算单一起回滚，
         * 而独立之后会留下一条对不上任何单的账**（凭空多出来的钱，删账不可逆）。
         *
         * <p>推到提交之后，两种失败方向就都是安全的：
         * 业务回滚 → 这一步根本没执行；业务提交而这一步失败 → 资金巡检 I1
         * 每小时扫「已支付却无结算单」并自动补（generateForOrder 幂等）。
         *
         * <p>窗口从「毫秒」变成「投递延迟」，通常是秒级。可接受的理由有三条：
         * 用户已经付完款、看不到这一步；商家的「待结算」本来就有账期（T+1 起）；
         * 而 I1 超过一小时会告警。
         */
        AfterCommit.run("生成结算单 orderNo=" + orderNo,
                () -> settlePort.generateForOrder(orderNo));

        eventBus.publish(new OrderEvents.OrderPaid(orderNo, order.getUserNo(),
                order.getPayAmount(), payChannel));
        // B-N-1：商家的「新订单」提醒是子单粒度 —— 跨商家合单时每家只被自己的那单吵到
        for (OrdSubOrder sub : subOrders(orderNo)) {
            eventBus.publish(new OrderEvents.SubOrderPaid(sub.getSubOrderNo(), orderNo,
                    sub.getEntityNo(), sub.getStoreNo(), order.getUserNo(),
                    sub.getPayAmount() == null ? 0L : sub.getPayAmount()));
        }
    }

    /**
     * 支付提交后发放积分，并把结果写回子单。
     *
     * <p><b>重新读一次子单</b>，不用外面那个对象：那是提交之前读出来的，
     * 而这里已经在事务之外 —— 拿旧对象 updateById 会把它当时的全部字段
     * 原样写回去，覆盖掉这中间别处（比如履约）刚改的列。
     *
     * <p>失败不上抛：调用方是 {@link AfterCommit}，它会记 error。
     * 用户这次没拿到分而标记也没写上，重试由 {@code grantOnPay} 自己的
     * 流水幂等保证不会多发。
     */
    private void grantPointsAfterPay(String subOrderNo, String userNo, String entityNo, long base,
                                     String payChannel, String payScene, long bonusPoints) {
        /*
         * 通道与场景**传进去**，不让支付域回查订单（2026-09-01）。
         * 它们是支付那一刻的事实，这边本来就拿着：payChannel 是 markPaid 的参数，
         * payScene 在 ord_order 上。
         */
        List<ai.neargo.shop.spi.settle.PointsPort.EarnLine> lines = new ArrayList<>(earnLines(subOrderNo, base));
        if (bonusPoints > 0) {
            /*
             * 活动送的积分作为**一行定额**并进同一次发放：FIXED 规则按值原样发（基数只要 > 0），
             * 费用金照常按分数向商家收 —— 活动由商家出资，送的积分也是。
             * 不单独调一次 grant：发放按子单幂等，第二次调用会被当成重复回调吞掉。
             */
            lines.add(new ai.neargo.shop.spi.settle.PointsPort.EarnLine(null, null, 1L,
                    new ai.neargo.shop.spi.settle.PointsPort.EarnRule(
                            ai.neargo.shop.spi.settle.PointsPort.FIXED, bonusPoints)));
        }
        var g = pointsPort.grant(userNo, entityNo, lines, subOrderNo, payChannel, payScene);
        if (g.points() <= 0) {
            return;
        }
        OrdSubOrder fresh = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1"));
        if (fresh == null) {
            return;
        }
        fresh.setPointsGranted(true);
        /*
         * 费用金落在子单上，**结算时才真的扣**（发分即付，从货款里出）。
         *
         * 此前这一列全库零写入 —— 于是 stl_bill 也拿不到值，
         * B 端「本期积分支出」永远是 0，而池子只出不进：
         * 用户花分时 MERCHANT_PAY 出账，发分时却没有对应的入账，
         * 恒等式 2 会随发放量单调失衡。
         */
        fresh.setPointsFeeMinor(g.feeMinor());
        subOrderMapper.updateById(fresh);
    }

    // ---------------------------------------------------------------- 查询与取消

    @Override
    public OrderVO detail(String orderNo, String client) {
        return detailOf(orderNo, SecurityUtils.currentUserNo(), surfaceOf(client));
    }

    /**
     * 属主视角的详情，<b>属主由参数给</b>。
     *
     * <p>公开的 {@link #detail} 永远只认当前登录人 —— 属主鉴权仍然写在查询条件里
     * （防 IDOR 的第一层）。这里之所以要参数化：代客下单（{@link #createFor}）的
     * 下单人是客服、属主是顾客，下单末尾那句「返回支付视角」用当前登录人去查，
     * <b>查出来的必然是空</b>，于是一张已经建好的单以 404 收场。
     */
    private OrderVO detailOf(String orderNo, String userNo, String surface) {
        // Q6：先按子单号查（C 端绝大多数请求是订单视角），查不到再按主单号
        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, orderNo)
                .eq(OrdSubOrder::getUserNo, userNo)
                .last("limit 1"));
        if (sub != null) {
            OrdOrder order = orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                    .eq(OrdOrder::getOrderNo, sub.getOrderNo()).last("limit 1"));
            /*
             * **三样只在详情查**（见 OrderVO 上的说明）：列表一次几十条，
             * 在 `orderView` 里查就是每条三次额外查询。
             *
             * 三样都是端上早就声明、后端一直没发的字段 —— 缺了的表现分别是
             * 「已评价的单照样显示去评价」「售后进行中整张卡不显示」
             * 「拆单提示不显示」，三条都不报错。见 TDD-交互清单缺口修复 G15。
             */
            OrderVO vo = orderView(sub, order).withDetail(
                    reviewQueryPort.reviewed(sub.getSubOrderNo()),
                    afterSaleService.ofSubOrder(sub.getSubOrderNo()).orElse(null),
                    subOrderMapper.selectCount(Wrappers.<OrdSubOrder>lambdaQuery()
                            .eq(OrdSubOrder::getOrderNo, sub.getOrderNo())).intValue(),
                    // 「现在申请仅退款会不会秒退」由后端答。端上此前拿一份 ¥50 的常量比金额，
                    // 而后端阈值是 ¥100，且常量表达不了总开关与「下单 N 小时内」那两半
                    afterSaleRuleService.instantEligible(OrdAfterSale.REFUND_ONLY,
                            sub.getPayAmount() == null ? 0L : sub.getPayAmount(),
                            sub.getCreatedAt() == null ? null
                                    : sub.getCreatedAt().atZone(java.time.ZoneId.systemDefault())
                                            .toInstant().toEpochMilli()));
            /*
             * **优惠依据只在详情查**（与上面那三样同一条理由：列表一次几十条）。
             * 读的是当时落下的 `pmt_apply`，不是按现在的规则重算 —— 规则可能早改了。
             */
            var lines = discountLinesOf(sub);
            if (!lines.isEmpty()) {
                vo = vo.withDiscountLines(lines);
            }
            // 已取消 / 已退款：券与积分去了哪（P3）。只在详情、只在这两个状态查
            vo = vo.withReturned(returnedOf(sub));
            // 物流轨迹（Y4）：快递单读缓存；非快递/无单号/缓存空时 traceOf 返回 null
            vo = vo.withTrace(traceOf(sub, surface));
            if (sub.getPeriodNo() != null) {
                // 集单（s37）：提货日与「截单前可取消」。已截单、已退款的不再给可取消时刻
                Long until = periodPort == null || OrdSubOrder.REFUNDED.equals(sub.getStatus())
                        ? null : periodPort.openUntil(sub.getPeriodNo(), System.currentTimeMillis());
                vo = vo.withBatch(sub.getArriveDate(), until);
            }
            return vo;
        }
        OrdOrder order = requireOwnOrder(orderNo, userNo);
        return payView(order, subOrders(orderNo));
    }

    /**
     * 这张子单的物流轨迹（Y4）。只对快递履约、已回填单号的子单查缓存；其余 null（不展示）。
     * 只读缓存，不触发承运商查询（那是轮询 Job 的事）。
     */
    @Override
    public OrderVO.Trace logisticsTrace(String orderNo, String client) {
        return logisticsTrace(orderNo, client, false);
    }

    @Override
    public OrderVO.Trace logisticsTrace(String orderNo, String client, boolean refresh) {
        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, orderNo)
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (sub == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (logisticsPort == null || !OrdSubOrder.EXPRESS.equals(sub.getFulfillment())
                || sub.getExpressNo() == null || sub.getExpressNo().isBlank()) {
            return null;
        }
        return logisticsPort.track(ai.neargo.shop.spi.logistics.LogisticsPort.TrackQuery.subOrder(
                        sub.getSubOrderNo(), surfaceOf(client), refresh))
                .map(OrderServiceImpl::toTraceVO)
                .orElse(null);
    }

    private OrderVO.Trace traceOf(OrdSubOrder sub, String surface) {
        if (!OrdSubOrder.EXPRESS.equals(sub.getFulfillment())
                || sub.getExpressNo() == null || sub.getExpressNo().isBlank()) {
            return null;
        }
        /*
         * **轨迹真源是 logistics 域（lgs_waybill），不是 ful_shipment**（2026-10-10 换源）。
         *
         * 换源的理由是一个真缺陷：快递100 走的是**订阅推送**（SubscribeExecutor 发货即订，
         * 推送落 /callback/logistics → 写 lgs_waybill + lgs_waybill_node），而这里原来读的
         * ful_shipment 只由 LogisticsTracePollingJob（30 分钟一轮、走按单计费的查询接口、
         * 且快递100 在 probe-surfaces 里默认一个界面都不放行）写。**两套存储没接通** ——
         * 症状不是「延迟 30 分钟」，是推来的真实轨迹在订单详情里永远看不到，
         * 而接口全绿、日志干净。详情与 /trace 从此同源。
         *
         * refresh=false：**读路径不主动问渠道**，买家反复下拉打不穿配额、不花钱。
         * （LogisticsPortImpl 内部对微信那条有 10 分钟 TTL 的自动校正，那是它的事。）
         * 端（MP / APP / H5）由 Controller 读 X-Client 传进来，不在这儿读 request。
         *
         * ful_shipment 那套保留给运营列表与兜底；logisticsPort 缺失的装配回落到它，
         * 保证换源不比换之前少给东西。
         */
        if (logisticsPort == null) {
            return legacyTraceOf(sub, surface);
        }
        return logisticsPort.track(ai.neargo.shop.spi.logistics.LogisticsPort.TrackQuery
                        .subOrder(sub.getSubOrderNo(), surface, false))
                .map(OrderServiceImpl::toTraceVO)
                .orElse(null);
    }

    /**
     * {@link OrderVO.Trace} 的统一映射：**字段给全**。
     *
     * <p>换源之前详情只给 5 个字段（status/nodes/displayMode/displayToken/route），
     * 于是承运商、签收时刻、是否到驿站、能不能刷新这几样端上拿不到 —— 而它们正是
     * 「我的件现在怎么了」要答的。现在详情与物流页给的是同一份。
     *
     * <p><b>route 为 null</b>：城市路线（出发/当前/目的）是 ful_shipment 那套的派生字段，
     * logistics 域不产出。地图不受影响 —— 它画的是 nodes 上的坐标，那几样还在。
     */
    private static OrderVO.Trace toTraceVO(ai.neargo.shop.spi.logistics.LogisticsPort.TrackView v) {
        return new OrderVO.Trace(v.status(),
                v.nodes().stream().map(n -> new OrderVO.Trace.Node(n.at(), n.text(), n.location(),
                        n.latE6(), n.lngE6())).toList(),
                v.displayMode(), v.displayToken(), null,
                v.carrier(), v.waybillNo(), v.signedAt(), v.atLocker(), v.freshAt(), v.refreshable());
    }

    /** 换源前的读法（ful_shipment）。只在没有装配 logistics 域时回落 */
    private OrderVO.Trace legacyTraceOf(OrdSubOrder sub, String surface) {
        return shipmentTracePort.traceOf(sub.getSubOrderNo(), surface, null, null)
                .map(ct -> new OrderVO.Trace(ct.status(), ct.nodes().stream()
                                .map(n -> new OrderVO.Trace.Node(n.at(), n.text(), n.location(),
                                        n.latE6(), n.lngE6())).toList(),
                        ct.displayMode(), ct.displayToken(),
                        ct.routeFrom() == null && ct.routeCur() == null && ct.routeTo() == null ? null
                                : new OrderVO.Trace.Route(ct.routeFrom(), ct.routeCur(), ct.routeTo())))
                .orElse(null);
    }

    /**
     * 原始 {@code X-Client} 头 → 展示端（MP / APP / H5）。**纯字符串映射，不碰 request** ——
     * 读头在 Controller，这里只做翻译，worker/事件路径传什么都能调。
     * <p>认不出一律当 {@code MP}：买家侧绝大多数请求来自小程序，而认错只是少一个渠道可选。
     */
    static String surfaceOf(String client) {
        if (client == null || client.isBlank()) {
            return "MP";
        }
        String u = client.trim().toUpperCase(java.util.Locale.ROOT);
        if (u.startsWith("APP")) {
            return "APP";
        }
        if (u.startsWith("H5") || u.startsWith("WEB")) {
            return "H5";
        }
        return "MP";
    }

    /** 自提类履约，给展示状态的反向过滤用（SHIPPED 与 ARRIVED 在库里是同一个状态）。 */
    private static final List<String> PICKUP_FULFILLMENTS =
            List.of("STORE_PICKUP", "NEIGHBOR_PICKUP");

    @Override
    public PageData<OrderVO> list(String status, List<String> fulfillments, long page, long size) {
        // Q6：列表是子单粒度
        var w = Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo());
        /*
         * **两个正交的筛选条件**，不是一个。
         *
         * 此前端上传的是「状态 × 履约」的组合词（ARRIVED / SHIPPED），后端再拆回去 ——
         * 于是「待取货」这种页签是一个**状态值**，加一种履约就得加一个值。
         * 现在端上传抽象状态 + 想要的履约集合，页签变成**谓词**：
         * 「待取货」= FULFILLING ∧ 自提类，「待使用」= FULFILLING ∧ 服务类，
         * 想合并两个页签只改端上传的集合，后端一行不动。
         */
        List<String> stored = OrderStatusView.toStored(status);
        if (!stored.isEmpty()) {
            w.in(OrdSubOrder::getStatus, stored);
        }
        if (fulfillments != null && !fulfillments.isEmpty()) {
            w.in(OrdSubOrder::getFulfillment, fulfillments);
        }
        w.orderByDesc(OrdSubOrder::getId);

        Page<OrdSubOrder> p = subOrderMapper.selectPage(Page.of(page, size), w);
        if (p.getRecords().isEmpty()) {
            return PageData.empty(page, size);
        }
        Map<String, OrdOrder> orders = orderMapper.selectList(Wrappers.<OrdOrder>lambdaQuery()
                        .in(OrdOrder::getOrderNo,
                                p.getRecords().stream().map(OrdSubOrder::getOrderNo).distinct().toList()))
                .stream().collect(Collectors.toMap(OrdOrder::getOrderNo, o -> o, (a, b) -> a));

        List<OrderVO> records = p.getRecords().stream()
                .map(s -> orderView(s, orders.get(s.getOrderNo())))
                .toList();
        return PageData.of(records, p.getTotal(), p.getCurrent(), p.getSize());
    }

    @Override
    @Transactional
    public OrderVO cancel(String orderNo, String reason) {
        // 端上可能传子单号（订单列表上点取消）：解析成主单，一次支付整体取消
        OrdOrder order = resolveOrder(orderNo);
        if (OrdOrder.PAID.equals(order.getStatus())) {
            OrderVO undone = cancelPaidPeriodOrder(order);
            if (undone != null) {
                return undone;
            }
        }
        OrderStateMachine.assertOrderTransit(order.getStatus(), OrdOrder.CANCELLED);

        order.setStatus(OrdOrder.CANCELLED);
        order.setCancelReason(reason);
        orderMapper.updateById(order);

        for (OrdSubOrder sub : subOrders(order.getOrderNo())) {
            OrderStateMachine.assertSubOrderTransit(sub.getStatus(), OrdSubOrder.CANCELLED);
            sub.setStatus(OrdSubOrder.CANCELLED);
            subOrderMapper.updateById(sub);
            appendStatusLog(sub.getSubOrderNo(), OrdSubOrder.CANCELLED, "订单已取消",
                    OrdStatusLog.BY_USER, order.getUserNo());
        }
        stockPort.release(order.getOrderNo());
        couponPort.release(order.getOrderNo());
        /*
         * 积分按子单退。券是整单一张、积分是每个子单一条 ——
         * 所以这里逐个子单退，而不是像券那样传 orderNo。
         *
         * <p><b>推迟到提交之后</b>：退分是跨域写，理由与生成结算单、发放积分同一条。
         * 这一处推迟起来最省心 —— {@code reverse} 的幂等<b>在数据本身</b>：
         * 它只认状态为 PENDING 的 USE 流水，翻成 REVERSED 之后第二次进来就找不到了。
         * 不像 {@code grant} 那样靠调用方事务里的标记，所以移出事务不会让幂等失效。
         */
        for (OrdSubOrder sub : subOrders(order.getOrderNo())) {
            final String subNo = sub.getSubOrderNo();
            AfterCommit.run("退回积分 subOrderNo=" + subNo,
                    () -> pointsPort.reverse(subNo, "订单已取消"));
            // 名额还回去。幂等标记在子单上 —— 与超时关闭那条路同时到达也只还一次
            releaseAppointmentSlot(sub);
        }
        return detail(order.getOrderNo(), null);
    }

    /**
     * 社区集单：<b>已付款</b>的单在截单前可以撤（PRD §4.3.2 · AC-7）。
     *
     * <p>已付款的主单在状态机里没有出口 —— 这里不改主单状态，而是逐张子单走
     * 系统全额退款（与商家同意退款同一条收尾路径：先回退分账再退款、子单转 REFUNDED）。
     * <b>只有整单都是集单子单时才走这条路</b>；混着普通商品的单不在这里处理，
     * 返回空让调用方按原规则拒绝 —— 普通商品已付款后该走售后，不该被「撤单」绕过。
     *
     * @return 已按集单撤单处理时返回订单详情；不适用时返回空
     */
    private OrderVO cancelPaidPeriodOrder(OrdOrder order) {
        if (periodPort == null) {
            return null;
        }
        List<OrdSubOrder> subs = subOrders(order.getOrderNo());
        if (subs.isEmpty() || subs.stream().anyMatch(s -> s.getPeriodNo() == null)) {
            return null;
        }
        long now = System.currentTimeMillis();
        for (OrdSubOrder s : subs) {
            if (!OrdSubOrder.REFUNDED.equals(s.getStatus()) && periodPort.isCutOff(s.getPeriodNo(), now)) {
                // 商家已经按这一期的量去采购了：截单后不能撤，只能收货后走售后
                throw BizException.of(ErrorCode.PERIOD_CUT_OFF);
            }
        }
        for (OrdSubOrder s : subs) {
            afterSaleService.systemRefund(s.getSubOrderNo(), "截单前取消", "买家在截单前取消了订单");
        }
        return detail(order.getOrderNo(), null);
    }

    @Override
    @Transactional
    public int closeExpiredOrders(long now) {
        List<OrdOrder> expired = orderMapper.selectList(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getStatus, OrdOrder.WAIT_PAY)
                .le(OrdOrder::getPayDeadlineAt, now));

        for (OrdOrder order : expired) {
            closeOne(order, "支付超时");
        }
        return expired.size();
    }

    /**
     * 关掉一笔待支付的单。
     *
     * <p>抽出来是因为对账自查也要用它：通道明确回「没有这笔」时，那单可以安全关掉。
     * <b>两处必须走同一段代码</b> —— 关单要连着释放库存、券、积分，
     * 各写一遍的话，漏掉的那一项会让库存或券一直占着，而没有任何报错。
     */
    void closeOne(OrdOrder order, String reason) {
        // 状态先改再释放库存：改失败（并发下已被支付）就不该释放
        order.setStatus(OrdOrder.CLOSED);
        order.setCancelReason(reason);
        orderMapper.updateById(order);

        for (OrdSubOrder sub : subOrders(order.getOrderNo())) {
            if (!OrdSubOrder.WAIT_PAY.equals(sub.getStatus())) {
                continue;
            }
            sub.setStatus(OrdSubOrder.CANCELLED);
            subOrderMapper.updateById(sub);
            appendStatusLog(sub.getSubOrderNo(), OrdSubOrder.CANCELLED, reason + "，订单关闭",
                    OrdStatusLog.BY_SYSTEM, null);
        }
        // 幂等：release 只作用于 LOCKED 的锁定行，重复跑不会把库存加两遍
        stockPort.release(order.getOrderNo());
        couponPort.release(order.getOrderNo());
        /*
         * **活动配额也要退**（执行计划 B6）。此前这一行没有 ——
         * 限量 100 份的活动被没付款的单吃掉量，运营看到的「已用 N 份」里
         * 有几份从来没成交，而库存、券、积分三样都退了，唯独它不退。
         */
        campaignPort.release(order.getOrderNo());
        // 同上，积分逐子单退，并同样推迟到提交之后。
        // reverse 只认 PENDING 的 USE 流水，重复跑不会退两次 —— 幂等在数据里，不在标记上
        for (OrdSubOrder sub : subOrders(order.getOrderNo())) {
            final String subNo = sub.getSubOrderNo();
            AfterCommit.run("退回积分 subOrderNo=" + subNo,
                    () -> pointsPort.reverse(subNo, reason + "，订单关闭"));
            releaseAppointmentSlot(sub);
        }
    }

    /** 按单号关一笔待支付的单。已经不是待支付就当没事发生 —— 对账每轮都可能再撞到它 */
    @Override
    @Transactional
    public void closeUnpaid(String orderNo, String reason) {
        OrdOrder order = orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, orderNo).last("limit 1"));
        if (order == null || !OrdOrder.WAIT_PAY.equals(order.getStatus())) {
            return;
        }
        closeOne(order, reason);
    }

    @Override
    @Transactional
    public OrderVO confirmReceipt(String subOrderNo) {
        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, subOrderNo)
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (sub == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        OrderStateMachine.assertSubOrderTransit(sub.getStatus(), OrdSubOrder.COMPLETED);
        sub.setStatus(OrdSubOrder.COMPLETED);
        subOrderMapper.updateById(sub);
        appendStatusLog(subOrderNo, OrdSubOrder.COMPLETED, "已确认收货",
                OrdStatusLog.BY_USER, SecurityUtils.currentUserNo());
        return detail(subOrderNo, null);
    }

    @Override
    public ai.neargo.shop.trade.dto.TrackVO trackBySubOrder(String subOrderNo, String client) {
        // **不加 userNo 过滤**：票据已验，持票即可看（见接口注释）。按子单号直查
        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, subOrderNo)
                .last("limit 1"));
        if (sub == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        OrdOrder order = orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, sub.getOrderNo()).last("limit 1"));
        OrderVO.StoreBrief store = storeBriefOf(sub);
        List<ai.neargo.shop.trade.dto.TrackVO.Item> items = itemsOf(subOrderNo).stream()
                .map(i -> new ai.neargo.shop.trade.dto.TrackVO.Item(
                        i.getTitle(), i.getCover(), i.getSpec(), i.getQty() == null ? 0 : i.getQty()))
                .toList();
        return new ai.neargo.shop.trade.dto.TrackVO(
                sub.getSubOrderNo(),
                OrderStatusView.toContract(sub.getStatus(), order == null ? null : order.getStatus()),
                sub.getFulfillment(),
                store == null ? null : store.storeName(),
                sub.getReceiverName(),
                ai.neargo.shop.common.Masks.phone(sub.getReceiverPhone()),
                sub.getReceiverAddress(),
                sub.getExpressCompany(),
                sub.getExpressNo(),
                items,
                traceOf(sub, surfaceOf(client)));
    }

    /**
     * 售后未闭环的状态 —— 与 {@code SettleSourcePortImpl.AFTER_SALE_OPEN} 同一份口径。
     *
     * <p>两处各写一遍迟早分岔，而分岔的方向是危险的那一侧：漏登记一个状态，
     * 争议中的单会被自动确认收货，等于替买家签了字。
     */
    private static final java.util.Set<String> AFTER_SALE_OPEN = java.util.Set.of(
            ai.neargo.shop.trade.entity.OrdAfterSale.APPLIED,
            ai.neargo.shop.trade.entity.OrdAfterSale.REFUNDING,
            ai.neargo.shop.trade.entity.OrdAfterSale.ARBITRATING);

    @Override
    @Transactional
    public int autoConfirmReceipt(long now, int shippedDays) {
        return autoConfirmReceipt(now, shippedDays, 0);
    }

    /**
     * @param signedDays 签收后多少天自动确认收货。<b>0 = 不启用签收判据</b>，整条退回只按发货算
     *                   （与接签收之前逐字相同）
     */
    @Override
    public int autoConfirmReceipt(long now, int shippedDays, int signedDays) {
        if (shippedDays <= 0) {
            return 0;
        }
        List<OrdSubOrder> candidates = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getStatus, OrdSubOrder.FULFILLING)));
        if (candidates.isEmpty()) {
            return 0;
        }
        /*
         * 自提类排除：超时没来取要的是退款或催取，不是替买家签收。
         * 挤进同一个 job 的话，「超时未取」会被静默结算掉 —— 而那笔钱本该退。
         */
        List<OrdSubOrder> shipped = candidates.stream()
                .filter(x -> !Fulfillments.isPickup(x.getFulfillment()))
                .toList();
        if (shipped.isEmpty()) {
            return 0;
        }
        List<String> subNos = shipped.stream().map(OrdSubOrder::getSubOrderNo).toList();

        /*
         * 发货时间取「进入 FULFILLING 那条流水的 at」——
         * `ord_sub_order` 上**没有发货时间字段**（只有 created_at / updated_at），
         * 而 updated_at 会被之后任何一次改动刷新，拿它当发货时间会让单子永远不到期。
         */
        Map<String, Long> shippedAt = DataScopeContext.executeWithoutScope(() ->
                        statusLogMapper.selectList(Wrappers.<OrdStatusLog>lambdaQuery()
                                .in(OrdStatusLog::getSubOrderNo, subNos)
                                .eq(OrdStatusLog::getStatus, OrdSubOrder.FULFILLING)))
                .stream()
                .collect(Collectors.toMap(OrdStatusLog::getSubOrderNo,
                        x -> x.getAt() == null ? Long.MAX_VALUE : x.getAt(),
                        Math::max));

        java.util.Set<String> blocked = DataScopeContext.executeWithoutScope(() ->
                        afterSaleMapper.selectList(Wrappers.<ai.neargo.shop.trade.entity.OrdAfterSale>lambdaQuery()
                                .in(ai.neargo.shop.trade.entity.OrdAfterSale::getSubOrderNo, subNos)
                                .in(ai.neargo.shop.trade.entity.OrdAfterSale::getStatus, AFTER_SALE_OPEN)))
                .stream()
                .map(ai.neargo.shop.trade.entity.OrdAfterSale::getSubOrderNo)
                .collect(Collectors.toSet());

        /*
         * ★ 判据从「发货后 N 天」扩成「**签收后 N 天 或 发货后 M 天，先到者为准**」（批 C）。
         *
         * 行业惯例（淘宝/拼多多）是签收后 7 天，而签收回传此前没有，所以上一版退到发货后 15 天。
         * 现在 ful_shipment.signed_at 有了，但**不是直接换掉**：换掉的话
         * 「第 10 天才签收」的单会从第 15 天推迟到第 17 天 —— 状态机的改动把某些单的
         * 完成时间往后推，那是在动钱的到账时间。取先到者保证**没有任何一单比今天等得更久**。
         *
         * 查不到签收时间（自提已排除；快递里承运商没回传、或还没签收）一律走发货判据。
         */
        long shipDeadline = now - (long) shippedDays * 86_400_000L;
        long signDeadline = signedDays > 0 ? now - (long) signedDays * 86_400_000L : Long.MIN_VALUE;
        Map<String, Long> signedAt = signedDays > 0 && shipmentTracePort != null
                ? shipmentTracePort.signedAtOf(subNos) : Map.of();
        int n = 0;
        for (OrdSubOrder sub : shipped) {
            if (blocked.contains(sub.getSubOrderNo())) {
                continue;
            }
            /*
             * 查不到发货流水的按 MAX_VALUE 处理 = 永不到期。
             * 宁可多等：没有流水说明状态是被别的路径改的，那种单自动签收的风险更高。
             */
            Long at = shippedAt.get(sub.getSubOrderNo());
            Long signed = signedAt.get(sub.getSubOrderNo());
            boolean bySigned = signed != null && signed <= signDeadline;
            boolean byShipped = at != null && at <= shipDeadline;
            if (!bySigned && !byShipped) {
                continue;
            }
            sub.setStatus(OrdSubOrder.COMPLETED);
            DataScopeContext.executeWithoutScope(() -> subOrderMapper.updateById(sub));
            // 理由写清按哪条到期的 —— 对账时「为什么这单这天完成」只能看这一行
            appendStatusLog(sub.getSubOrderNo(), OrdSubOrder.COMPLETED,
                    bySigned ? "签收满 " + signedDays + " 天自动确认收货"
                            : "发货满 " + shippedDays + " 天自动确认收货",
                    OrdStatusLog.BY_SYSTEM, null);
            n++;
        }
        return n;
    }

    // ---------------------------------------------------------------- 拆单

    /** 预览与下单共用。**唯一的拆单实现**。 */
    private Split split(CreateOrderCommand cmd) {
        List<CreateOrderCommand.Item> requested = cmd.items() == null || cmd.items().isEmpty()
                ? selectedCartItems() : cmd.items();
        if (requested.isEmpty()) {
            return new Split(List.of(), List.of());
        }

        List<String> skuNos = requested.stream().map(CreateOrderCommand.Item::skuNo).toList();
        Map<String, GoodsQueryPort.SkuSnapshot> snapshots = goodsPort.snapshot(skuNos);

        /*
         * **门店价：预览与下单在这里一起拿到，不在别处各算一次**（批 C）。
         *
         * 两处各算一次的下场是「购物车/预览显示门店价、扣款按主体价」——
         * 与限时特价那条注释记的是同一个形状，也是同一个理由：
         * split() 是预览与下单唯一共用的入口，覆盖层只能落在这里。
         *
         * 先按主体价算一遍再决定要不要重算：绝大多数商家不分店定价，
         * 无条件走门店分支等于给每次下单加两条查询。
         */
        /*
         * **这里也要带件**（AC4/AC5）。拆单这一步已经按门店判在架与门店价了 ——
         * 而落店如果按「默认店」算出来，一件只在分店上架的货会在这一步被判成「已下架」，
         * 回 70076，我加在 storesOf 上的闸根本轮不到跑（实测：默认店下架、分店在架的单）。
         * 件数从请求/购物车来，与下面锁库存用的是同一批。
         */
        Map<String, Map<String, Integer>> itemsByMerchant = new HashMap<>();
        for (CreateOrderCommand.Item it : requested) {
            GoodsQueryPort.SkuSnapshot sn = snapshots.get(it.skuNo());
            /*
             * ★ 有归属门店的货不进落店解析（ADR-031）：它的门店就是它自己那家，没有可挑的。
             * 混进去的话，盐（粮油）+ 柿子（鲜果）这一单会因为「找不到一家两件都在架的店」整单被拒。
             */
            if (sn != null && (sn.storeNo() == null || sn.storeNo().isBlank())) {
                itemsByMerchant.computeIfAbsent(sn.merchantNo(), k -> new HashMap<>())
                        .merge(it.skuNo(), it.qty(), Integer::sum);
            }
        }
        Map<String, String> storeByEntity = itemsByMerchant.isEmpty() ? Map.of()
                : storesOfEntities(cmd, List.copyOf(itemsByMerchant.keySet()), itemsByMerchant);
        /*
         * **无条件重算，不再拿「有没有配门店价」当开关**（2026-09-30）。
         *
         * 那个 if 原本是省一次查询：不分店定价的商家走不到这一支。
         * 但快照里除了价格还有 `onSale`，而门店级上下架恰恰只在这一支里才读得到 ——
         * 于是「A 店把货下架了」对没配门店价的商家<b>完全无效</b>，
         * 分享链接照样下单成功。省下的那次查询，代价是卖出店主已经下掉的货。
         */
        snapshots = goodsPort.snapshot(skuNos, storeByEntity);

        List<Line> lines = new ArrayList<>();
        for (CreateOrderCommand.Item item : requested) {
            GoodsQueryPort.SkuSnapshot s = snapshots.get(item.skuNo());
            /*
             * **两件事拆开说**：真的没有这个 SKU，和它下架了。
             * 合成一个 NOT_FOUND 的话，买家正看着这件商品的详情页，
             * 却被告知「商品不存在」—— 他只会以为系统坏了然后反复重试。
             * 门店级上下架接进来之后更明显：货在别的门店还在卖，
             * 页面上一切正常，只有他要去的那家店不卖了。
             */
            if (s == null) {
                throw BizException.of(ErrorCode.NOT_FOUND);
            }
            if (!s.onSale()) {
                throw BizException.of(ErrorCode.GOODS_OFF_SALE);
            }
            lines.add(new Line(s, item.qty()));
        }
        /*
         * **仅活动的货，普通下单要有活动开着这条路**（TDD-商品仅活动可售 §4.3）。
         * 开团 / 参团不在这里判 —— 拼团规则自己会判活动在不在，这里放行。
         * 放在 split() 里而不是 create()：预览、下单、代客下单都经过这里，
         * 确认页就能拒，不必等到提交那一下。一单里的仅活动货一次批量问完。
         */
        if (!cmd.grouped()) {
            List<String> only = lines.stream().filter(l -> l.snapshot.activityOnly())
                    .map(l -> l.snapshot.goodsNo()).distinct().toList();
            if (!only.isEmpty()) {
                var open = saleGatePort == null ? java.util.Set.<String>of()
                        : saleGatePort.live(only, System.currentTimeMillis()).direct();
                if (!open.containsAll(only)) {
                    throw BizException.of(ErrorCode.GOODS_ACTIVITY_ONLY);
                }
            }
        }

        /*
         * ★ 按**门店**分组（ADR-031）—— 一组一张子单。门店 = 商品的归属门店；
         * 没有归属的（测试种子）落到上面按主体解析出的那家。保持插入序，让子单顺序稳定。
         */
        final Map<String, String> landed = storeByEntity;
        Map<String, List<Line>> byStore = lines.stream().collect(Collectors.groupingBy(
                l -> storeKeyOf(l, landed), LinkedHashMap::new, Collectors.toList()));

        Map<String, String> merchantNames = new HashMap<>();
        List<Group> groups = byStore.values().stream().map(ls -> {
            String merchantNo = ls.get(0).snapshot.merchantNo();
            String merchantName = merchantNames.computeIfAbsent(merchantNo, m -> merchantPort.find(m)
                    .map(MerchantQueryPort.MerchantBrief::merchantName).orElse(""));
            String own = ls.get(0).snapshot.storeNo();
            String storeNo = own != null && !own.isBlank() ? own : landed.get(merchantNo);
            // 运费在 withFreight 里按模板算；这里先给 0 而不是编一个假数字
            return new Group(merchantNo, merchantName, storeNo, ls, 0L);
        }).toList();

        return new Split(lines, groups);
    }

    /** 一行落在哪一组：归属门店；没有归属按主体落店的结果；都没有用主体号 */
    private static String storeKeyOf(Line l, Map<String, String> landedByEntity) {
        String own = l.snapshot.storeNo();
        if (own != null && !own.isBlank()) {
            return own;
        }
        String landed = landedByEntity.get(l.snapshot.merchantNo());
        return landed != null && !landed.isBlank() ? landed : l.snapshot.merchantNo();
    }

    /**
     * 这张单按哪个团、什么价；不是团单返回 null。
     *
     * <p>团单只能买<b>团的那一件货</b>（可以多件、可以选规格）：带着团号混进别的货，
     * 等于拿团价的名义下一张普通单，而成团、到期退款都按整张子单处理。
     */
    private ai.neargo.shop.spi.marketing.GroupJoinPort.GroupQuote groupQuoteOf(
            CreateOrderCommand cmd, Split split, String userNo) {
        if (!cmd.grouped()) {
            return null;
        }
        if (groupJoinPort == null || split.items.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        java.util.Set<String> goods = split.items.stream()
                .map(l -> l.snapshot.goodsNo()).collect(Collectors.toSet());
        if (goods.size() != 1) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        var q = groupJoinPort.quote(userNo, cmd.groupNo(), cmd.openGroup(), goods.iterator().next());
        for (Line l : split.items) {
            if (!q.merchantNo().equals(l.snapshot.merchantNo())
                    || (q.skuNo() != null && !q.skuNo().equals(l.skuNo()))) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
        }
        return q;
    }

    /**
     * 快递运费（TDD-快递100商家寄件 §8）：按商家分组，每组按这家店快递通道的模板（没配用平台默认）算。
     *
     * <p>计费重 = Σ(规格标称重量 × 件数)；**没填重量的件每件按首重** —— 不按 0 算：
     * 按 0 就是没填重量的货一律只收首重，商家越不填越便宜，填了反而贵。
     * 非快递单、没有任何模板时运费为 0（与改造前一致，不编一个数）。
     */
    private Split withFreight(Split split, CreateOrderCommand cmd, Map<String, String> stores, String userNo) {
        return applyFreight(split, freightQuotes(split, cmd, stores, userNo));
    }

    /**
     * 商品级限购地区拦截（#3/#4①）。收货地址的省 ∈ 某商品 {@code restricted_regions} → 抛
     * {@link ErrorCode#OUT_OF_DELIVERY_RANGE}。只在**有收货地址**时判（自提跳过）。
     *
     * <p>省的判法与运费模板同口径：按省名前缀匹配收货地址（{@code address.startsWith(省名)}），
     * 命中省的 regionCode 取**前两位**（省级国标码），与 {@code restricted_regions} 存的两位码比。
     * 认不出省就**放行**——宁可漏拦一单，也不要把认不出地址的正常单误杀。
     *
     * <p>门店级：经营范围里 {@code PROVINCE + EXCLUDE} 的省同样拒。只拦到省 —— 地址只存省市名字，
     * 市/区级排除在这儿认不准，只管可见性（TDD-经营范围排除地区 §3）。
     */
    private void requireNotRegionRestricted(CreateOrderCommand cmd, Split split,
                                            Map<String, String> storeOfMerchant, String userNo) {
        if (userNo == null) {
            return;
        }
        List<String> goodsNos = split.items.stream().map(l -> l.snapshot.goodsNo()).distinct().toList();
        Map<String, java.util.Set<String>> restricted = goodsPort.restrictedProvincesOf(goodsNos);
        Map<String, java.util.Set<String>> storeExcluded = new java.util.HashMap<>();
        for (Group g : split.groups) {
            var ex = merchantPort.excludedProvinces(g.merchantNo(), storeOfMerchant.get(g.key()));
            if (!ex.isEmpty()) {
                storeExcluded.put(g.key(), ex);
            }
        }
        if (restricted.isEmpty() && storeExcluded.isEmpty()) {
            return;   // 没有任何货设了限购地区、也没有门店排除省：这道闸整条跳过，不查地址
        }
        for (Group g : split.groups) {
            String addr = cmd.addressFor(g.storeNo(), g.merchantNo());
            if (addr == null || addr.isBlank()) {
                continue;   // 自提等无收货地址：限购地区针对「送到哪」，无地址无从谈起
            }
            String address = userPort.receiverOf(userNo, addr)
                    .map(ai.neargo.shop.spi.user.UserQueryPort.Receiver::address).orElse("");
            String provinceCode = ai.neargo.shop.common.Provinces.provinceCodeOf(address);
            if (provinceCode == null) {
                continue;   // 认不出省：放行（与 freight 的省名前缀同一套，认不出就不拦）
            }
            if (storeExcluded.getOrDefault(g.key(), java.util.Set.of()).contains(provinceCode)) {
                throw BizException.of(ErrorCode.OUT_OF_DELIVERY_RANGE);
            }
            for (Line l : g.lines()) {
                java.util.Set<String> codes = restricted.get(l.snapshot.goodsNo());
                if (codes != null && codes.contains(provinceCode)) {
                    throw BizException.of(ErrorCode.OUT_OF_DELIVERY_RANGE);
                }
            }
        }
    }

    private Map<String, FreightPort.Quote> freightQuotes(Split split, CreateOrderCommand cmd,
                                                         Map<String, String> stores, String userNo) {
        if (!Fulfillments.EXPRESS.equals(cmd.fulfillment()) || freightPort == null) {
            return Map.of();
        }
        Map<String, FreightPort.Quote> out = new LinkedHashMap<>();
        Map<String, String> goodsTemplates = goodsPort.freightTemplatesOf(
                split.items.stream().map(l -> l.snapshot().goodsNo()).distinct().toList());
        Map<String, Boolean> activeTemplate = new HashMap<>();
        for (Group g : split.groups) {
            String addr = cmd.addressFor(g.storeNo(), g.merchantNo());
            String address = addr == null || addr.isBlank() || userNo == null ? ""
                    : userPort.receiverOf(userNo, addr)
                            .map(ai.neargo.shop.spi.user.UserQueryPort.Receiver::address).orElse("");
            /*
             * ★ 模板逐行解析（ADR-031 §2.4，实时读、不缓存）：商品指定的 ＞ 所属门店快递通道的 ＞ 平台默认。
             * 商品指定的被运营归档了就跳过它（AC8）—— 回落门店，不回落成平台默认，更不是 0 元。
             * 同一家店的几行交给 FreightPort.merge 按淘宝式合并：只有一个模板时与改造前逐字相同。
             */
            String storeTemplate = merchantPort.expressTemplateNo(g.merchantNo(), stores.get(g.key())).orElse(null);
            List<FreightPort.FreightLine> lines = new ArrayList<>();
            for (Line l : g.lines()) {
                String own = goodsTemplates.get(l.snapshot().goodsNo());
                String template = own != null && activeTemplate.computeIfAbsent(own, freightPort::active)
                        ? own : storeTemplate;
                Integer w = l.snapshot().nominalGram();
                boolean weighed = w != null && w > 0;
                lines.add(new FreightPort.FreightLine(template, weighed ? w * l.qty() : 0,
                        weighed ? 0 : l.qty(), l.amount()));
            }
            freightPort.quoteMerged(lines, address).ifPresent(q -> out.put(g.key(), q));
        }
        return out;
    }

    private static Split applyFreight(Split split, Map<String, FreightPort.Quote> quotes) {
        if (quotes.isEmpty()) {
            return split;
        }
        List<Group> groups = split.groups.stream().map(g -> {
            FreightPort.Quote q = quotes.get(g.key());
            long fee = q == null || q.rejected() ? g.freight() : q.feeMinor();
            return new Group(g.merchantNo(), g.merchantName(), g.storeNo(), g.lines(), fee);
        }).toList();
        return new Split(split.items, groups);
    }

    /**
     * 团单按团价重算每一行。**替换快照上的单价**而不是事后打折：
     * 订单行的 price / amount、子单的商品金额、满减门槛全都读它，
     * 只在一处改，预览与下单、主单与子单就不会各算出一个数。
     */
    private static Split repriced(Split split,
                                  ai.neargo.shop.spi.marketing.GroupJoinPort.GroupQuote q) {
        if (q == null) {
            return split;
        }
        List<Line> lines = split.items.stream().map(l -> {
            var s = l.snapshot;
            return new Line(new GoodsQueryPort.SkuSnapshot(s.skuNo(), s.goodsNo(), s.merchantNo(),
                    s.title(), s.cover(), s.spec(), s.categoryType(), s.categoryNo(),
                    q.groupPriceMinor(), s.available(), s.onSale(), s.fulfillments(),
                    s.groupPriceMinor(), s.groupMinCount(), s.saleMode(), s.limitPerUser(), s.nominalGram()), l.qty);
        }).toList();
        // 团单只有一件货、一组：按组里的货号把重算后的行认回去（不按主体认 —— 同主体可能多组）
        List<Group> groups = split.groups.stream().map(g -> {
            java.util.Set<String> skus = g.lines.stream().map(Line::skuNo).collect(Collectors.toSet());
            return new Group(g.merchantNo, g.merchantName, g.storeNo,
                    lines.stream().filter(l -> skus.contains(l.skuNo())).toList(), g.freight);
        }).toList();
        return new Split(lines, groups);
    }

    private List<CreateOrderCommand.Item> selectedCartItems() {
        return cartMapper.selectList(Wrappers.<TrdCartItem>lambdaQuery()
                        .eq(TrdCartItem::getUserNo, SecurityUtils.currentUserNo())
                        .eq(TrdCartItem::getSelected, true)).stream()
                .map(c -> new CreateOrderCommand.Item(c.getGoodsNo(), c.getSkuNo(), c.getQty()))
                .toList();
    }

    private record Line(GoodsQueryPort.SkuSnapshot snapshot, int qty) {
        String skuNo() {
            return snapshot.skuNo();
        }

        long amount() {
            return snapshot.price() * qty;
        }
    }

    /**
     * 一组 = 一张子单 = <b>一家门店</b>（ADR-031）。{@code merchantNo} 是主体（结算、额度、营销按它），
     * {@code storeNo} 是这组由哪家店卖、哪家店发。同一主体两家店的货在一单里是两组。
     *
     * <p>{@link #key()} 是这一组在各张映射表里的键：有门店用门店号，没有（只有测试种子会这样）用主体号。
     */
    private record Group(String merchantNo, String merchantName, String storeNo, List<Line> lines, long freight) {

        String key() {
            return storeNo != null && !storeNo.isBlank() ? storeNo : merchantNo;
        }

        long goodsAmount() {
            return lines.stream().mapToLong(Line::amount).sum();
        }

        /** 下单件数。买赠送出的不在 {@code lines} 里，所以这里天然不含赠品 */
        int goodsQty() {
            return lines.stream().mapToInt(Line::qty).sum();
        }
    }

    private record Split(List<Line> items, List<Group> groups) {
        /** 这一单每件货买几件（多个规格合在一起 —— 限购按商品算） */
        Map<String, Integer> qtyByGoods() {
            Map<String, Integer> out = new HashMap<>();
            items.forEach(l -> out.merge(l.snapshot.goodsNo(), l.qty, Integer::sum));
            return out;
        }

        /** 设了每人限购的货 → 限购数 */
        Map<String, Integer> limitsByGoods() {
            Map<String, Integer> out = new HashMap<>();
            items.stream().filter(l -> l.snapshot.limited())
                    .forEach(l -> out.put(l.snapshot.goodsNo(), l.snapshot.limitPerUser()));
            return out;
        }

        /**
         * 这一行最多能买几件 + 是谁挡住的。
         *
         * <p>同一件货的其他规格已经在这一单里占掉的名额要扣掉 ——
         * 限购 5、A 规格买了 3，B 规格的步进器就只能到 2。
         */
        OrderVO.ItemVO itemOf(Line l, String merchantNo, Map<String, Quota> quotas) {
            int max = l.snapshot.available();
            String reason = OrderVO.ItemVO.LIMIT_STOCK;
            Quota q = quotas.get(l.snapshot.goodsNo());
            if (q != null) {
                int others = items.stream().filter(o -> o != l
                        && o.snapshot.goodsNo().equals(l.snapshot.goodsNo())).mapToInt(Line::qty).sum();
                int byLimit = Math.max(0, q.left() - others);
                if (byLimit < max) {
                    max = byLimit;
                    reason = OrderVO.ItemVO.LIMIT_PER_USER;
                }
            }
            return new OrderVO.ItemVO(
                    l.snapshot.goodsNo(), merchantNo, l.snapshot.skuNo(), l.snapshot.title(),
                    l.snapshot.cover(), l.snapshot.spec(), l.snapshot.price(), l.qty,
                    l.amount(), l.snapshot.categoryType(), false,
                    // 下单页的步进器要知道还能加到几 —— 只有后端算得准（见 ItemVO.maxQty）
                    max, reason,
                    q == null ? null : q.limit(), q == null ? null : q.bought());
        }

        long goodsAmount() {
            return groups.stream().mapToLong(Group::goodsAmount).sum();
        }

        long freightAmount() {
            return groups.stream().mapToLong(Group::freight).sum();
        }

        long payAmount() {
            return goodsAmount() + freightAmount();
        }

        /**
         * 预览走**支付视角**：结算页要看的是合计金额与按商家的分组。
         *
         * @param pickups 这一单每家商家配到的自提点（自提单才有）。
         *     确认页据它按取货点分组 —— 两家配到同一个点要合并成一组，
         *     按商家分会让人以为要跑两趟
         */
        OrderVO toVO(Discounts discounts, java.util.Map<String, PickupPick> pickups,
                     Map<String, Quota> quotas) {
            return toVO(discounts, pickups, quotas, Map.of());
        }

        /** @param storeNames 门店号 → 店名：子单段头显示门店名（ADR-031） */
        OrderVO toVO(Discounts discounts, java.util.Map<String, PickupPick> pickups,
                     Map<String, Quota> quotas, Map<String, String> storeNames) {
            List<OrderVO> children = groups.stream().map(g -> new OrderVO(
                    null, null, OrdOrder.WAIT_PAY, null, g.merchantNo, g.merchantName,
                    g.lines.stream().map(l -> itemOf(l, g.merchantNo, quotas)).toList(),
                    OrderVO.Amount.of(g.goodsAmount(), g.freight,
                            discounts.of(g.merchantNo, g.storeNo()), 0L, CURRENCY_CNY),
                    // 预览还没有单，收件人与预约时间自然也没有；自提点是**已经配好的那个**
                    null,
                    pickups.containsKey(g.key()) ? pickups.get(g.key()).pickupNo() : null,
                    pickups.containsKey(g.key()) ? pickups.get(g.key()).name() : null,
                    null, 0L, null, null, null, null, null, List.of(), null,
                // 买家昵称只在商家侧下发（B12）——C 端自己就是买家，不需要
                null,
                // 预览还没有单：评价、售后、支付分组三样都无从谈起
                false, null, 1)
                    .withPickupDistance(pickups.containsKey(g.key())
                            ? pickups.get(g.key()).distanceM() : null)
                    .withStore(g.storeNo() == null ? null
                            : new OrderVO.StoreBrief(g.storeNo(), storeNames.get(g.storeNo())))).toList();

            return new OrderVO(null, null, OrdOrder.WAIT_PAY, null, null, null,
                    children.stream().flatMap(c -> c.items().stream()).toList(),
                    OrderVO.Amount.of(goodsAmount(), freightAmount(),
                            discounts.total(), 0L, CURRENCY_CNY),
                    null, null, null, null, 0L, null, null, null, null, null, List.of(), children,
                // 买家昵称只在商家侧下发（B12）——C 端自己就是买家，不需要
                null,
                // 预览还没有单；分组数用得上，结算页要说「会生成几笔订单」
                false, null, children.size(),
                // 预览还没发货，无快递公司
                null, null, null, null, null);
        }
    }

    // ---------------------------------------------------------------- 装配

    private OrdOrder requireOwnOrder(String orderNo) {
        return requireOwnOrder(orderNo, SecurityUtils.currentUserNo());
    }

    private OrdOrder requireOwnOrder(String orderNo, String userNo) {
        // 属主鉴权：查询条件带 userNo，而不是查出来再判 —— 防 IDOR 的第一层
        OrdOrder order = orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, orderNo)
                .eq(OrdOrder::getUserNo, userNo)
                .last("limit 1"));
        if (order == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return order;
    }

    /** 接受主单号或子单号，统一解析成主单（属主校验同样在查询条件里）。 */
    private OrdOrder resolveOrder(String orderNo) {
        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, orderNo)
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        return requireOwnOrder(sub == null ? orderNo : sub.getOrderNo());
    }

    private List<OrdSubOrder> subOrders(String orderNo) {
        return subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getOrderNo, orderNo).orderByAsc(OrdSubOrder::getId));
    }

    private List<OrdItem> itemsOf(String subOrderNo) {
        return itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                .eq(OrdItem::getSubOrderNo, subOrderNo).orderByAsc(OrdItem::getId));
    }

    /** 订单视角（Q6）：单商家，有履约方式、核销码与时间线；团单挂上团号 */
    private OrderVO orderView(OrdSubOrder s, OrdOrder order) {
        return orderViewBase(s, order).withGroup(s.getGroupNo());
    }

    private OrderVO orderViewBase(OrdSubOrder s, OrdOrder order) {
        return new OrderVO(
                /*
                 * 下发**抽象状态**：`WAIT_FULFILL` 归一成契约的 `PAID`，`FULFILLING` 原样。
                 *
                 * 此前这里下发的是「状态 × 履约」的组合（`ARRIVED` / `SHIPPED`）——
                 * 那不是状态，是组合冒充状态，代价是**每加一种履约就要加一批状态**
                 * （服务类差点又加了 TO_USE / TO_SERVE）。
                 * 现在履约方式单独下发（`fulfillment` 字段本来就在），
                 * 由端上的 `orderView(status, fulfillment, info)` 决定显示什么。
                 */
                s.getSubOrderNo(), s.getOrderNo(),
                OrderStatusView.toContract(s.getStatus(), order == null ? null : order.getStatus()),
                s.getFulfillment(),
                s.getEntityNo(), s.getEntityName(),
                itemsOf(s.getSubOrderNo()).stream().map(this::toItemVO).toList(),
                OrderVO.Amount.of(nz(s.getGoodsAmount()), nz(s.getFreightAmount()),
                        nz(s.getDiscountAmount()),
                        order != null && order.getPaidAt() != null ? nz(s.getPayAmount()) : 0L,
                        // 积分快照从子单读 —— 此前这里传的是老工厂，
                        // 三个积分字段被写死成 0，端上永远看不到抵扣
                        nz(s.getPointsDeductMinor()),
                        s.getPointsDeduct() == null ? 0 : s.getPointsDeduct(),
                        order == null ? CURRENCY_CNY : order.getCurrency()),
                s.getVerifyCode(), s.getPickupNo(), s.getPickupName(),
                order == null ? null : order.getPayDeadlineAt(),
                millis(s.getCreatedAt()),
                order == null ? null : order.getPaidAt(),
                // 买家要靠它查物流；此前库里有这一列而 VO 里没有，发货对买家不可见
                s.getExpressNo(),
                s.getTrafficSource(),
                s.getAppointmentAt(),
                // 买家看自己的单：完整地址与完整手机号，那本来就是他填的
                receiverOf(s),
                timelineOf(s.getSubOrderNo()),
                null,
                // 买家昵称只在商家侧下发（B12）——C 端自己就是买家，不需要
                null,
                /*
                 * 评价、售后、支付分组三样**在这里一律留空**，由 `detailOf` 用
                 * `withDetail` 补上 —— 这个方法被列表与详情共用，
                 * 在这里查就是每条订单三次额外查询（N+1）。
                 */
                false, null, 1,
                // 集单四样这里不填；末位 expressCompany 要下发 ——
                // 买家查物流认的是「哪家快递 + 单号」，只给单号等于让他自己猜快递公司
                null, null, null, null, s.getExpressCompany())
                .withStore(storeBriefOf(s));
    }

    /** 子单的门店（ADR-031）：端上按它分段、显示段头。门店名要解域，理由见 {@link #storeNamesOf} */
    private OrderVO.StoreBrief storeBriefOf(OrdSubOrder s) {
        String no = s.getStoreNo();
        if (no == null || no.isBlank()) {
            return null;
        }
        String name = ai.neargo.common.data.scope.DataScopeContext.executeWithoutScope(
                () -> merchantPort.storeNames(List.of(no)).get(no));
        return new OrderVO.StoreBrief(no, name);
    }

    /** 子单上的收件人快照 → VO。三列都空（自提单）时给 null，让端上少判一层 */
    private static OrderVO.Receiver receiverOf(OrdSubOrder s) {
        if (s.getReceiverName() == null && s.getReceiverAddress() == null) {
            return null;
        }
        return new OrderVO.Receiver(s.getReceiverName(), s.getReceiverPhone(),
                s.getReceiverAddress());
    }

    /** 支付视角（Q6）：合计金额 + 各商家子单；**不给 fulfillment**，跨商家可能不同。 */
    private OrderVO payView(OrdOrder order, List<OrdSubOrder> subs) {
        List<OrderVO> children = subs.stream().map(s -> orderView(s, order)).toList();
        List<OrderVO.ItemVO> allItems = children.stream().flatMap(c -> c.items().stream()).toList();
        return new OrderVO(
                order.getOrderNo(), order.getOrderNo(), order.getStatus(), null,
                null, null, allItems,
                OrderVO.Amount.of(nz(order.getGoodsAmount()), nz(order.getFreightAmount()),
                        nz(order.getDiscountAmount()),
                        order.getPaidAt() == null ? 0L : nz(order.getPayAmount()),
                        // 积分**汇总子单**：主单没有这两列，而收银台读的就是这一层 ——
                        // 不汇总的话支付页显示的是未抵扣的价格，用户以为多扣了钱
                        subs.stream().mapToLong(x -> nz(x.getPointsDeductMinor())).sum(),
                        subs.stream().mapToInt(x -> x.getPointsDeduct() == null ? 0 : x.getPointsDeduct()).sum(),
                        order.getCurrency()),
                null, null, null,
                order.getPayDeadlineAt(), millis(order.getCreatedAt()), order.getPaidAt(),
                // 支付视角跨商家，没有单一快递号 —— 它在每个子单上。收件人与预约时间同理
                null, null, null, null, List.of(), children,
                // 买家昵称只在商家侧下发（B12）——C 端自己就是买家，不需要
                null,
                // 支付视角：分组数就是子单数，收银台那句「本次付款覆盖 N 笔」读它
                false, null, children.size(),
                // 支付视角跨商家，快递公司同快递号：它在每个子单上，主单这层没有
                null, null, null, null, null);
    }

    private OrderVO.ItemVO toItemVO(OrdItem i) {
        return new OrderVO.ItemVO(i.getGoodsNo(), null, i.getSkuNo(), i.getTitle(), i.getCover(),
                i.getSpec(), nz(i.getPrice()), i.getQty() == null ? 0 : i.getQty(),
                nz(i.getAmount()), i.getCategoryType(), Boolean.TRUE.equals(i.getIsGift()));
    }

    private List<OrderVO.TimelineNode> timelineOf(String subOrderNo) {
        return statusLogMapper.selectList(Wrappers.<OrdStatusLog>lambdaQuery()
                        .eq(OrdStatusLog::getSubOrderNo, subOrderNo)
                        .orderByAsc(OrdStatusLog::getAt).orderByAsc(OrdStatusLog::getId)).stream()
                .map(l -> new OrderVO.TimelineNode(l.getStatus(), l.getLabel(), nz(l.getAt())))
                .toList();
    }

    /**
     * 该商家<b>当日已成交额</b>（分）。
     *
     * <p>只统计已付款的单：未付款订单不构成平台的敞口，
     * 把它们算进去会让一批「下单不付」把正常商家的额度占满。
     * 同理排除已取消与已退款——钱退回去了，敞口也就没了。
     *
     * <p>只有本档位配了日累计限额时才会走到这里（见 {@code AdmissionPort} 的 supplier 说明）。
     */
    private long paidAmountToday(String merchantNo) {
        java.time.LocalDateTime dayStart = java.time.LocalDate.now().atStartOfDay();
        /*
         * **必须绕过数据域拦截器**，否则这个限额根本不是它看起来的意思。
         *
         * ord_sub_order 注册了 ScopeDim.SELF → user_no，而本方法是在下单请求里、
         * 以**买家身份**执行的 —— 不绕过的话 SQL 会被追加 user_no = 当前买家，
         * 于是「该商家当日成交额」变成「该买家在这家店的当日成交额」，
         * 日累计上限实际成了「每买家一份」：100 个买家各下 500，
         * 商家当天成交 5 万而限额一次都不触发。
         *
         * 这里要的是**平台对这个商家的当日敞口**，与谁在买无关。
         */
        List<OrdSubOrder> rows = DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getEntityNo, merchantNo)
                        .ge(OrdSubOrder::getCreatedAt, dayStart)
                        .notIn(OrdSubOrder::getStatus,
                                OrdSubOrder.WAIT_PAY, OrdSubOrder.CANCELLED, OrdSubOrder.REFUNDED)));
        return rows.stream().mapToLong(r -> r.getPayAmount() == null ? 0L : r.getPayAmount()).sum();
    }

    /**
     * 用户选的履约方式，购物车里<b>每一件</b>商品都得支持。
     *
     * <p>{@code GoodsQueryPort.SkuSnapshot#fulfillments} 的注释里早就写着
     * 「决定拆单后每个子单能选什么」——<b>但这个校验从来没写过</b>。
     * 于是一件只支持到店自提的商品可以被下成快递单，
     * 而它会一路走到商家的待发货列表里，直到商家打电话来问。
     *
     * <p>按「每一件都支持」而不是「有一件支持」判：履约方式是整单一个，
     * 只要有一件不支持，那一件就没法按用户选的方式送到。
     *
     * <p>快照里履约方式为空的商品放行——那是存量数据，
     * 不能因为补了这道校验就把一批老商品变成不可下单。
     */
    /**
     * 送到人手上的履约方式 —— 它们必须有地址，自提不需要。
     *
     * <p><b>上门预约也在里面</b>：师傅要知道去哪。它与快递/自送的差别在「送的是货还是人」，
     * 而「必须有地址」这件事三者一样 —— 按需要不需要地址分组，
     * 比按实物/服务分组更贴近这道闸真正要判的东西。
     */
    private static final java.util.Set<String> SHIPPED_FULFILLMENTS = java.util.Set.of(
            Fulfillments.EXPRESS, Fulfillments.MERCHANT_DELIVERY, Fulfillments.APPOINTMENT);

    /**
     * 快递 / 自送必须有**能解析出来**的收货地址。
     *
     * <p>此前没有这道闸，后果是一张**发不出去的订单**能一路下成功、付成功：
     * 商家侧订单详情的收货人是 null，界面上是「—」。系统全程没有任何异常，
     * 要等商家准备发货时才发现，而那时钱已经收了。
     * 实测：库里 55 张快递子单，有收货人的 0 张。
     *
     * <p><b>与下面那句「取不到不让下单失败」不矛盾</b>：那句说的是
     * 「给了 addressId 但此刻查不出来」——那是容错，不该让已付款的单卡住；
     * 这里挡的是「从头就没给过地址」，那是漏校验。两件事。
     *
     * <p>拦在**创建**这一步，不是支付后：付过钱再告诉他「地址没选」，
     * 他要先退款才能重下。
     */
    /**
     * 用户选的支付方式，购物车里<b>每一件</b>商品在<b>它所属的门店</b>都得支持。
     *
     * <p>与 {@link #requireFulfillmentSupported} 同一条判法：按「每一件都支持」而不是
     * 「有一件支持」—— 支付方式是整单一个，只要有一件不支持，这一单就付不成。
     *
     * <p><b>不传按 ONLINE</b>：存量端上没有这个字段，不能因为补了它就让老版本下不了单。
     *
     * @return 归一化后的支付方式，供落库使用
     */
    private String requirePayModeSupported(CreateOrderCommand cmd, Split split,
                                           Map<String, String> storeOfMerchant) {
        String payMode = cmd.payMode() == null || cmd.payMode().isBlank()
                ? PayModes.ONLINE : cmd.payMode();
        if (!PayModes.isValid(payMode)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (PayModes.ONLINE.equals(payMode)) {
            return payMode;   // 线上不受四层约束，见 PayModePort#availablePayModes
        }
        /*
         * 线下 × 履约方式：只有「当面能收到钱」的那几种。
         *
         * **排除快递**：货已经寄出去了，没有「当面收款」的那一刻。
         * **排除自提点自提**：自提点承接的是别家商家的货，让它代收货款
         * 立刻变成资金归集 —— 与 ADR-002 要避开的二清是同一件事。
         */
        if (!PayModes.OFFLINE_FULFILLMENTS.contains(cmd.fulfillment())
                // 快递测试模式：快递单也可线下付，只为在生产上跑通快递闭环（TDD-快递100商家寄件 §7 AC10）
                && !payModeService.expressUnderTest(cmd.fulfillment())) {
            throw BizException.of(ErrorCode.PAY_MODE_NOT_SUPPORTED);
        }
        for (Group g : split.groups()) {
            String storeNo = storeOfMerchant.get(g.key());
            for (Line line : g.lines()) {
                if (!payModeService.availablePayModes(line.snapshot().goodsNo(), storeNo, cmd.fulfillment())
                        .contains(payMode)) {
                    throw BizException.of(ErrorCode.PAY_MODE_NOT_SUPPORTED);
                }
            }
        }
        return payMode;
    }

    // 允许线下的履约方式挪到 PayModes.OFFLINE_FULFILLMENTS：结算页与建单共用一份

    private void requireReceiverWhenShipped(CreateOrderCommand cmd, String userNo) {
        if (cmd.fulfillment() == null || !SHIPPED_FULFILLMENTS.contains(cmd.fulfillment())) {
            return;
        }
        if (cmd.addressId() == null || cmd.addressId().isBlank()
                || userPort.receiverOf(userNo, cmd.addressId()).isEmpty()) {
            throw BizException.of(ErrorCode.RECEIVER_REQUIRED);
        }
    }

    /**
     * 自送单的收货地址要落在这家店的自送半径内。
     *
     * <p><b>这条闸此前不存在</b>：商家在「送货方式 › 商家自送」里填的半径
     * （`mch_store.delivery_radius_m`，默认 3000 米）全仓没有任何消费方 ——
     * 他以为自己限定了范围，实际上多远的单都会进来，等他准备送货时才发现送不到，
     * 那时钱已经收了，只能退款并向买家解释。
     *
     * <p><b>只在两边都有坐标时才判</b>：门店没在地图上标过点、或买家地址是手填的，
     * 一律放行 —— 拿缺失的数据去拦，会把本来正常的单挡在门外。
     * 半径 ≤ 0 也放行：那是「不限距离」的表达。
     *
     * <p>拦在**创建**这一步而不是支付后：付过钱再告诉他「超出范围」，他要先退款才能重下。
     */
    private void requireWithinDeliveryRadius(CreateOrderCommand cmd, Split split, String userNo) {
        if (!outOfRangeMerchants(cmd, split, userNo).isEmpty()) {
            throw BizException.of(ErrorCode.OUT_OF_DELIVERY_RANGE);
        }
    }

    /**
     * 自送送不到的那几家（商家名）。**建单与预览共用这一份判定**（待办设计 P6）：
     * 建单时非空就拒；预览只把名字带回去，确认页当场给「换地址 / 换配送方式」。
     */
    private List<String> outOfRangeMerchants(CreateOrderCommand cmd, Split split, String userNo) {
        List<String> out = new ArrayList<>();
        if (!Fulfillments.MERCHANT_DELIVERY.equals(cmd.fulfillment())) {
            return out;
        }
        /*
         * 逐商家判：购物车跨商家时会拆成多张子单，各家的圆心与半径都不同，
         * 且多地址模式下各家的收货地址也可能不同。
         * 只要有一家送不到，这一单就下不成 —— 让他先拆开或换送货方式，
         * 比下成之后由那一家单独退款要好解释。
         */
        Map<String, String> stores = storesOf(cmd, split);
        for (var g : split.groups) {
            String addr = cmd.addressFor(g.storeNo(), g.merchantNo());
            if (addr == null || addr.isBlank()) continue;
            var receiver = userPort.receiverOf(userNo, addr).orElse(null);
            if (receiver == null || receiver.latE6() == null || receiver.lngE6() == null) continue;
            var origin = merchantPort.deliveryOrigin(g.merchantNo(), stores.get(g.key())).orElse(null);
            if (origin == null || origin.radiusM() <= 0) {
                continue;
            }
            if (metersBetween(origin.latE6(), origin.lngE6(), receiver.latE6(), receiver.lngE6())
                    > origin.radiusM()) {
                out.add(g.merchantName());
            }
        }
        return out;
    }

    /**
     * 两点间距离（米）。与社区围栏判定同一套算法：经度间距随纬度收缩，
     * 不乘 cos 会让高纬度地区多算出几百米 —— 那正好是「送得到」与「送不到」的分界。
     */
    private static int metersBetween(int latE6, int lngE6, int otherLatE6, int otherLngE6) {
        double metersPerDegree = 111_320d;
        double dLat = (latE6 - otherLatE6) / 1e6 * metersPerDegree;
        double midLat = Math.toRadians((latE6 + otherLatE6) / 2e6);
        double dLng = (lngE6 - otherLngE6) / 1e6 * metersPerDegree * Math.cos(midLat);
        return (int) Math.round(Math.sqrt(dLat * dLat + dLng * dLng));
    }

    /**
     * 自提单<b>必须带自提点</b>，而且那个点得真的存在。
     *
     * <p><b>与上面「快递必须有地址」是同一形状的另一半</b>：送到人手上的要地址，
     * 去点上取的要点 —— 两者都是「不给就履约不了」的信息，所以都拦在创建这一步。
     *
     * <p>缺了它<b>不会在下单时报错</b>，而是让后面每一步都失败、且原因都指错：
     * <ul>
     *   <li>到货登记 {@code /biz/pickup/arrived} → 返回空列表，看着像「没有这单」</li>
     *   <li>核销 {@code /biz/pickup/verify} → {@code NOT_THIS_PICKUP}，
     *       看着像「顾客走错店了」—— 店员会让他去别的自提点，
     *       <b>而那单根本不属于任何自提点</b></li>
     * </ul>
     * 2026-08-17 B 端第二轮实测抓到（用例 TB-B-6-2）。
     *
     * <p>连「点存不存在」一起校：只判空的话，传一个不存在的点号照样落到同一个坑里，
     * 而那种请求恰恰是端上传错参数时最常见的样子。
     */
    /** 自提点名：**预览就要给名字**，只给点号的话确认页只能显示一串 PP0001 */
    private String pickupNameOf(String pickupNo) {
        return pickupNo == null ? null : pickupPort.find(pickupNo).map(p -> p.name()).orElse(null);
    }

    /**
     * 配到的那个自提点：点号、名字、离买家多远（米）。
     *
     * <p>此前这三样是一个 {@code String[]}，加第三样时才发现下标 0/1 谁也不认得 ——
     * 而距离是个数，塞进字符串数组还要再解析回来。
     *
     * @param distanceM 米；{@code -1} = 点没标坐标（排不出远近，但不代表不能用）；
     *                  null = 这一次没算（没坐标、或不是自提单）
     */
    record PickupPick(String pickupNo, String name, Integer distanceM) {
    }

    /**
     * 这一单配到的点各自离买家多远。**确认页要把它说出来** ——
     * 点是后端按地址配的，买家没得挑；不说距离的话，他要到取货那天才知道有多远。
     *
     * <p>与 {@link #resolvePickups} 用同一套候选（`pickupOptions` 已按距离排序），
     * 所以这里不重新挑点，只是把那一批候选里的距离取出来。
     * 拿不到坐标就返回空表 —— 缺距离比编一个数强。
     */
    private java.util.Map<String, Integer> pickupDistances(CreateOrderCommand cmd, Split split,
                                                           String userNo) {
        if (cmd.fulfillment() == null || !Fulfillments.isPickup(cmd.fulfillment())) {
            return java.util.Map.of();
        }
        var point = userPort.buyerPoint(userNo, cmd.addressId()).orElse(null);
        if (point == null) {
            return java.util.Map.of();
        }
        java.util.Map<String, Integer> out = new java.util.LinkedHashMap<>();
        for (Group g : split.groups) {
            for (var o : communityQueryPort.pickupOptions(point.latE6(), point.lngE6(),
                    merchantPort.allowedPickupNos(g.merchantNo))) {
                out.putIfAbsent(o.pickupNo(), o.distanceM());
            }
        }
        return out;
    }

    private java.util.Map<String, String> resolvePickups(CreateOrderCommand cmd, Split split,
                                                        String userNo) {
        return resolvePickups(cmd, split, userNo, true);
    }

    /**
     * @param strict 下单是 true（配不出来就拦住）；<b>预览是 false</b> ——
     *     预览是信息，不是闸门。还没填地址的人连价格都看不到，
     *     比「看到了但下不了单」糟得多；而真正拦住他的地方在 create。
     */
    private java.util.Map<String, String> resolvePickups(CreateOrderCommand cmd, Split split,
                                                        String userNo, boolean strict) {
        if (cmd.fulfillment() == null || !Fulfillments.isPickup(cmd.fulfillment())) {
            return java.util.Map.of();
        }
        /*
         * 端上显式传了点：**行为与改造前逐字相同** —— 连「点存不存在」一起校，
         * 只判空的话，传一个不存在的点号照样落到同一个坑里（原 TB-B-6-2 抓到的那个），
         * 而那种请求恰恰是端上传错参数时最常见的样子。
         */
        if (cmd.pickupNo() != null && !cmd.pickupNo().isBlank()) {
            if (pickupPort.find(cmd.pickupNo()).isEmpty()) {
                throw BizException.of(ErrorCode.PICKUP_POINT_REQUIRED);
            }
            return split.groups.stream().collect(java.util.stream.Collectors.toMap(
                    g -> g.key(), g -> cmd.pickupNo(), (a, b) -> a));
        }
        /*
         * 没传：按买家坐标逐个商家配。坐标取「这一单的地址，没有就用生效地址」
         * （回落规则在 UserQueryPort#buyerPoint 一处）。
         *
         * **拿不到坐标就退回原来的话**：没有位置就配不出点，而这时候让他
         * 「先选个地址」是他真正能做的下一步 —— 比「没有可用取货点」准确。
         */
        var point = userPort.buyerPoint(userNo, cmd.addressId()).orElse(null);
        if (point == null) {
            if (!strict) {
                return java.util.Map.of();
            }
            throw BizException.of(ErrorCode.PICKUP_POINT_REQUIRED);
        }
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        for (Group g : split.groups) {
            var options = communityQueryPort.pickupOptions(point.latE6(), point.lngE6(),
                    merchantPort.allowedPickupNos(g.merchantNo));
            if (options.isEmpty()) {
                if (!strict) {
                    // 预览：这家就是配不出来，留空让确认页把它标出来，别把整页打死
                    continue;
                }
                /*
                 * **只挡这一家，并点名。** 车里有三家店时，只说「没有可用取货点」
                 * 的话，他不知道该换履约方式还是该把哪件商品拿出来。
                 */
                throw BizException.of(ErrorCode.PICKUP_POINT_NONE_FOR_MERCHANT,
                        merchantPort.find(g.merchantNo).map(m -> m.merchantName()).orElse(g.merchantNo));
            }
            out.put(g.key(), options.get(0).pickupNo());
        }
        return out;
    }

    /**
     * 预约类履约<b>必须带预约时间</b>。
     *
     * <p>缺了不是「稍后再约」——订单会直接进商家的待服务列表，
     * 而商家不知道该几点去，买家也不知道自己约了没有。两边都只能打电话。
     * 与收货地址那道闸同理：**下得成的单必须是履约得了的单**。
     */
    private void requireAppointmentWhenNeeded(CreateOrderCommand cmd, Split split,
                                              Map<String, String> storeOfMerchant) {
        if (cmd.fulfillment() == null
                || !Fulfillments.NEEDS_APPOINTMENT.contains(cmd.fulfillment())) {
            return;
        }
        /*
         * 这家店开了时段就必须挑一个。**没开时段的照旧按老路走** ——
         * 与门店渠道「一行都没有 = 还没迁过来，按旧口径放行」同一条兼容规矩。
         * 不留这条后路的话，这批代码一上线，所有做上门服务的商家
         * 在开出时段之前一单都接不了，而他们不会收到任何提示。
         */
        if (anyStoreHasSlots(split, storeOfMerchant)) {
            if (cmd.appointmentSlotNo() == null || cmd.appointmentSlotNo().isBlank()) {
                throw BizException.of(ErrorCode.APPOINTMENT_SLOT_UNAVAILABLE);
            }
            /*
             * 一个时段只属于一家店，所以带时段的单只能有一个商家。
             * 放行的话，另外那几家的子单会挂着一个**不属于自己**的时段号 ——
             * 名额扣在别人头上，而他们的待服务列表里什么都没有。
             * 现实中跨商家的上门服务本来也约不到一起去。
             */
            if (split.groups.size() > 1) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            return;
        }
        Long at = cmd.appointmentAt();
        // 过去的时间点与没填一样没用 —— 商家没法回到昨天上门
        if (at == null || at <= System.currentTimeMillis()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }

    private boolean anyStoreHasSlots(Split split, Map<String, String> storeOfMerchant) {
        for (Group g : split.groups) {
            if (appointmentSlotPort.hasOpenSlots(storeOfMerchant.get(g.key()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 真正抢名额。<b>放在建子单这一步，不放进前面那段前置校验</b> ——
     * 那一段的约定是「全部前置且只读」，而这是一次写。
     *
     * <p>整个 create 在一个事务里，所以后面任何一步抛异常，这次占位会一起回滚。
     *
     * @return 抢到的时段，没走时段这条路时返回 null
     */
    private ai.neargo.shop.spi.user.AppointmentSlotPort.BookResult bookAppointmentSlot(
            CreateOrderCommand cmd, Split split, Map<String, String> storeOfMerchant) {
        if (cmd.fulfillment() == null
                || !Fulfillments.NEEDS_APPOINTMENT.contains(cmd.fulfillment())
                || cmd.appointmentSlotNo() == null || cmd.appointmentSlotNo().isBlank()
                || !anyStoreHasSlots(split, storeOfMerchant)) {
            return null;
        }
        String storeNo = storeOfMerchant.get(split.groups.get(0).key());
        var r = appointmentSlotPort.tryBook(cmd.appointmentSlotNo(), storeNo);
        /*
         * 两种失败分开报，因为**给买家看的话不一样**：
         *   FULL        这一档满了 → 换个时间
         *   UNAVAILABLE 不存在/已停约/不是这家店的 → 重新挑一个
         * 合成一个码的话，端上只能说「约不了」，而用户不知道下一步该做什么。
         */
        if (r.outcome() == ai.neargo.shop.spi.user.AppointmentSlotPort.BookOutcome.FULL) {
            throw BizException.of(ErrorCode.APPOINTMENT_SLOT_FULL);
        }
        if (!r.booked()) {
            throw BizException.of(ErrorCode.APPOINTMENT_SLOT_UNAVAILABLE);
        }
        return r;
    }

    /**
     * 还名额。<b>先条件 UPDATE 打标记，打上了才减</b>。
     *
     * <p>取消会被重放：超时关闭与用户手动取消可能同时到达，两条路都走这里。
     * 顺序反过来（先减再打标记）的话，两个并发线程可能都先减成功，
     * 互斥就白做了 —— booked 减成负数，此后这个时段能卖出比 capacity 更多的单，
     * 而且不会有任何报错。
     */
    private void releaseAppointmentSlot(OrdSubOrder sub) {
        if (sub.getAppointmentSlotNo() == null || sub.getAppointmentSlotNo().isBlank()) {
            return;
        }
        int mine = subOrderMapper.markAppointmentReleased(
                sub.getSubOrderNo(), System.currentTimeMillis());
        if (mine == 1) {
            appointmentSlotPort.release(sub.getAppointmentSlotNo());
        }
    }

    private void requireFulfillmentSupported(String fulfillment, Split split,
                                             Map<String, String> storeOfMerchant, String userNo) {
        // 买家所在社区：范围子集（P2）按它裁；取不到就按不限判
        String communityNo = userPort.communityOf(userNo).orElse(null);
        if (fulfillment == null || fulfillment.isBlank()) {
            return;
        }
        for (Line line : split.items) {
            List<String> supported = line.snapshot.fulfillments();
            if (supported == null || supported.isEmpty()) {
                continue;
            }
            if (!supported.contains(fulfillment)) {
                throw BizException.of(ErrorCode.FULFILLMENT_NOT_SUPPORTED);
            }
        }
        /*
         * 门店这一路开没开（方案 v4）：商品说支持只是必要条件，
         * 履约的是**具体那家门店** —— 文三路店只做自提，仓库店才发快递。
         * 空集 = 该店还没迁移到 channel 模型，按旧口径放行（只读兼容期约定）。
         */
        /*
         * ⚠️ **服务类履约不受门店渠道表约束**（到店核销 / 上门预约）。
         *
         * `mch_fulfillment_channel` 只覆盖四条**实体配送**线（自提、邻里自提、
         * 自送、快递）—— `StoreFulfillmentServiceImpl.CONFIGURABLE` 就是那四个，
         * 服务类**永远不会有行**。而这里的规则是「集合非空就要求命中」，
         * 于是一旦商家保存过任何一次送货方式配置，集合不再为空，
         * **他的服务类商品从此一单也卖不出去** —— 买家看到的是
         * 「所选商品不支持该配送方式」，与真实原因毫无关系。
         *
         * 空集 = 该店还没迁到 channel 模型（兼容期放行），
         * 服务类不在集合里 = **这张表压根不表达它**，两者不是一回事。
         * 2026-08-25 接预约排期时撞出来：种子店被别的用例配过渠道之后，
         * 所有 APPOINTMENT 单一律 70013。
         */
        if (Fulfillments.SERVICE_LIKE.contains(fulfillment)) {
            return;
        }
        for (Group g : split.groups) {
            String store = storeOfMerchant.get(g.key());
            /*
             * **「没配过」与「配过、但没有一路送得到这里」是两件事**，不能都用空集表达。
             * 此前只问 enabledFulfillmentsFor：某几路选了 SUBSET、买家又不在任何一路的子集里时，
             * 它把几路全裁掉、返回空集 —— 被这里当成「没配过」放行，商家没框的地方照样下得了单。
             */
            if (merchantPort.enabledFulfillments(g.merchantNo, store).isEmpty()) {
                continue;
            }
            if (!merchantPort.enabledFulfillmentsFor(g.merchantNo, store, communityNo).contains(fulfillment)) {
                throw BizException.of(ErrorCode.FULFILLMENT_NOT_SUPPORTED);
            }
        }
    }

    /**
     * 买家选的自提点这家店送不送（P1）：点 ∈ 门店引用的取货点 ∪ 门店自己的点。
     *
     * <p>空集 = 这家店没配过取货点，按兼容期放行 —— 与 {@link #requireFulfillmentSupported}
     * 同一约定。否则存量商家（只开了自提、从没进过取货点配置）在发布当天一单都下不了。
     */
    private void requirePickupServed(CreateOrderCommand cmd, Split split) {
        if (cmd.fulfillment() == null || !Fulfillments.isPickup(cmd.fulfillment())
                || cmd.pickupNo() == null || cmd.pickupNo().isBlank()) {
            return;
        }
        for (Group g : split.groups) {
            java.util.Set<String> allowed = merchantPort.allowedPickupNos(g.merchantNo);
            if (!allowed.isEmpty() && !allowed.contains(cmd.pickupNo())) {
                throw BizException.of(ErrorCode.PICKUP_POINT_NOT_SERVED);
            }
        }
    }

    private void appendStatusLog(String subOrderNo, String status, String label,
                                 String operatorType, String operatorNo) {
        OrdStatusLog log = new OrdStatusLog();
        log.setSubOrderNo(subOrderNo);
        log.setStatus(status);
        log.setLabel(label);
        log.setOperatorType(operatorType);
        log.setOperatorNo(operatorNo);
        log.setAt(System.currentTimeMillis());
        log.setTenantNo("MAIN");
        log.setCreatedAt(java.time.LocalDateTime.now());
        statusLogMapper.insert(log);
    }

    /**
     * 取一个**库里还没用过**的 6 位核销码。
     *
     * <p><b>为什么不能只靠唯一索引兜底</b>（原来就是这么写的）：撞号时那条
     * {@code updateById} 会抛唯一约束冲突，而它跑在**支付回调**里 ——
     * 后果不是「这个码换一个」，是<b>这一笔支付回调失败</b>，
     * 子单停在待发码的状态、核销码是 null。用户已经付了钱。
     *
     * <p>而且这个索引是**全表、永久**的：历史订单的码一直占着号段，
     * 订单越多越容易撞。2026-08-28 全量测试里就撞出来了 ——
     * 一个共用的 H2 库累积上千条码之后，约 2/3 的跑次会撞一次，
     * 每次砸中不同的用例，表现成一条「会飘的失败」，查了很久才落到这里。
     * 生产上它同样成立，只是订单还少，没轮到。
     *
     * <p>所以先查后写、撞了换一个。索引<b>仍然保留</b>作最后兜底 ——
     * 查与写之间有并发窗口，那时宁可失败也不能发出两个一样的码：
     * 核销台扫出两单是比支付回调失败更坏的事。
     *
     * <p><b>试完仍然撞不出来就抛</b>，不静默用最后一个：那等于把一次必然的
     * 唯一冲突推到下一行，而错误信息会指向毫不相干的地方。
     * 真到了这一步，说明号段快用满了，该做的是把唯一性收窄到「未核销的单」
     * 或按门店分段，而不是把重试次数调大。
     */
    private static final int VERIFY_CODE_TRIES = 8;

    private String newUnusedVerifyCode() {
        for (int i = 0; i < VERIFY_CODE_TRIES; i++) {
            String code = newVerifyCode();
            Long used = subOrderMapper.selectCount(Wrappers.<OrdSubOrder>lambdaQuery()
                    .eq(OrdSubOrder::getVerifyCode, code));
            if (used == null || used == 0L) {
                return code;
            }
        }
        throw new IllegalStateException(
                "连续 %d 次都撞上已用的核销码 —— 号段接近用满，"
                        .formatted(VERIFY_CODE_TRIES)
                        + "该收窄唯一性范围（只对未核销的单唯一，或按门店分段），不是加大重试次数");
    }

    /** 6 位核销码。人要在核销台上念出来，所以不加长；唯一性由 {@link #newUnusedVerifyCode} 负责。 */
    private String newVerifyCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    private static long millis(java.time.LocalDateTime t) {
        return t == null ? 0L : t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    /**
     * 把子单的计分基数<b>按行金额比例分摊</b>到各行，供按类目发放积分。
     *
     * <p><b>为什么要分摊而不是每行直接用自己的 amount</b>：基数是「实付减运费」——
     * 它已经扣掉了券与积分抵扣，而那些优惠记在子单上、不分行。
     * 直接拿行金额当基数，等于**给已经打过折的那部分钱也发分**。
     *
     * <p><b>赠品不参与</b>（ADR-006：否则「买赠 + 积分」可叠出套利）。
     * 它们 amount=0，比例分摊天然给 0，这里再显式跳过一次 ——
     * 让读代码的人不必自己去推导。
     *
     * <p>分摊余数补给<b>金额最大的那一行</b>，保证各行之和恰好等于子单基数：
     * 逐行 floor 会少掉几分，而积分要与结算对账，差几分就是账对不平。
     */
    private List<ai.neargo.shop.spi.settle.PointsPort.EarnLine> earnLines(
            String subOrderNo, long base) {
        List<OrdItem> items = DataScopeContext.executeWithoutScope(() ->
                        itemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                                .eq(OrdItem::getSubOrderNo, subOrderNo)))
                .stream().filter(i -> !Boolean.TRUE.equals(i.getIsGift())).toList();
        long total = items.stream().mapToLong(i -> nz(i.getAmount())).sum();
        if (items.isEmpty() || total <= 0 || base <= 0) {
            return List.of();
        }
        int biggest = 0;
        for (int i = 1; i < items.size(); i++) {
            if (nz(items.get(i).getAmount()) > nz(items.get(biggest).getAmount())) {
                biggest = i;
            }
        }
        List<ai.neargo.shop.spi.settle.PointsPort.EarnLine> out = new ArrayList<>();
        long allocated = 0;
        for (int i = 0; i < items.size(); i++) {
            OrdItem it = items.get(i);
            long share = i == biggest ? 0 : base * nz(it.getAmount()) / total;
            allocated += share;
            out.add(new ai.neargo.shop.spi.settle.PointsPort.EarnLine(
                    it.getGoodsNo(), it.getCategoryNo(), share,
                    ruleOf(it.getGoodsNo(), it.getCategoryNo())));
        }
        OrdItem big = items.get(biggest);
        out.set(biggest, new ai.neargo.shop.spi.settle.PointsPort.EarnLine(
                big.getGoodsNo(), big.getCategoryNo(), base - allocated,
                ruleOf(big.getGoodsNo(), big.getCategoryNo())));
        return out;
    }

    /** 见 {@link PointsRuleHandoff} —— 转换单独成类是因为它是这条链上唯一会静默出错的一步 */
    private ai.neargo.shop.spi.settle.PointsPort.EarnRule ruleOf(String goodsNo, String categoryNo) {
        return PointsRuleHandoff.toPayRule(pointsRulePort.ruleFor(goodsNo, categoryNo));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    /**
     * 这一单走哪个通道。
     *
     * <p>端上指定优先；没指定时取该商家<b>按其市场筛出来的</b>第一个可用通道。
     * 取不到就抛 —— <b>不回退到某个默认通道</b>：回退等于把钱发到
     * 一个这家商户可能根本没进件的通道，那笔钱收不到而系统显示成功。
     *
     * <p>今天只用主单的第一个商家算。多商家单的通道交集在结算台
     * （{@code checkoutCapability}）已经算过一次，这里不重复那套逻辑 ——
     * <b>两处算出不同结果的话，用户在结算页看到的和实际用的就不是一个通道。</b>
     * 端上应当把结算台给的那个通道传进来，这一支是它没传时的兜底。
     */
    private String resolvePayChannel(OrdOrder order, String requested) {
        /*
         * **应付为 0 → 免支付通道，且优先于端上指定的通道。**
         *
         * 放在最前面（连 requested 都盖掉）是有意的：0 元的单送进任何真通道
         * 都会被「金额必须大于 0」拒掉，而那时订单已经建好了 ——
         * 用户看到的是「下单成功但永远付不了」，线上实测卡住过一笔
         * （SO202609201740370006341，见 TDD-零元订单支付 §2）。
         *
         * 优惠、券、积分任何一种都能把应付打到 0，所以这不是某个活动配错了，
         * 是这条链本来就缺一个出口。
         */
        if (order.getPayAmount() != null && order.getPayAmount() == 0L) {
            return ai.neargo.shop.common.PayChannels.FREE;
        }
        if (requested != null && !requested.isBlank()) {
            return requested;
        }
        String entityNo = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectList(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getOrderNo, order.getOrderNo()).last("LIMIT 1")))
                .stream().findFirst().map(OrdSubOrder::getEntityNo).orElse(null);
        List<String> available = entityNo == null ? List.of()
                : payChannelMasterPort.payableChannels(merchantPort.marketOf(entityNo));
        if (available.isEmpty()) {
            throw BizException.of(ErrorCode.PAY_CHANNEL_UNAVAILABLE);
        }
        return available.getFirst();
    }
}
