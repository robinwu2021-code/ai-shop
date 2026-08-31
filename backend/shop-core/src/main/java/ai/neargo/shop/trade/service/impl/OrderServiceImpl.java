package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.user.PickupQueryPort;
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
import ai.neargo.shop.trade.dto.OrderVO;
import ai.neargo.shop.trade.entity.OrdItem;
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
    private final SettlePort settlePort;
    private final StatusLogMapper statusLogMapper;
    private final PickupQueryPort pickupPort;
    /** 取买家绑定的社区，下单时固化到主单 —— 运营按社区做数据域隔离 */
    private final ai.neargo.shop.spi.user.UserQueryPort userPort;
    /** 挑「服务这个社区的最近门店」时要它给社区坐标 */
    private final ai.neargo.shop.spi.user.CommunityQueryPort communityPort;
    private final IdempotencyService idempotency;
    private final OutboxEventBus eventBus;
    /** 支付成功后告诉会员域「他买了一单」。trade 不认识会员表，也不该认识 */
    private final ai.neargo.shop.spi.member.MemberEventPort memberEventPort;
    /** 买家的人档号。没有（微信登录未授权手机号）就不入会 */
    private final ai.neargo.shop.spi.user.PersonPort personPort;
    private final ai.neargo.shop.spi.user.AppointmentSlotPort appointmentSlotPort;

    public OrderServiceImpl(ai.neargo.shop.spi.user.AppointmentSlotPort appointmentSlotPort,
                            OrderMapper orderMapper, SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                            ai.neargo.shop.spi.product.PayModePort payModeService,
                            CartItemMapper cartMapper, GoodsQueryPort goodsPort, StockPort stockPort,
                            MerchantQueryPort merchantPort,
                            ai.neargo.shop.spi.user.MerchantAdminPort merchantAdminPort,
                            AttributionPort attributionPort,
                            CouponPort couponPort, CampaignPort campaignPort, PointsPort pointsPort,
                            SettlePort settlePort,
                            StatusLogMapper statusLogMapper,
                            PickupQueryPort pickupPort,
                            ai.neargo.shop.spi.user.UserQueryPort userPort,
                            ai.neargo.shop.spi.user.CommunityQueryPort communityPort,
                            IdempotencyService idempotency, OutboxEventBus eventBus,
                            ai.neargo.shop.spi.user.AdmissionPort admissionPort,
                            ai.neargo.shop.spi.member.MemberEventPort memberEventPort,
                            ai.neargo.shop.spi.user.PersonPort personPort,
                            CloseRuleService closeRuleService) {
        this.payModeService = payModeService;
        this.appointmentSlotPort = appointmentSlotPort;
        this.orderMapper = orderMapper;
        this.subOrderMapper = subOrderMapper;
        this.admissionPort = admissionPort;
        this.closeRuleService = closeRuleService;
        this.itemMapper = itemMapper;
        this.cartMapper = cartMapper;
        this.goodsPort = goodsPort;
        this.stockPort = stockPort;
        this.merchantPort = merchantPort;
        this.merchantAdminPort = merchantAdminPort;
        this.attributionPort = attributionPort;
        this.couponPort = couponPort;
        this.pointsPort = pointsPort;
        this.campaignPort = campaignPort;
        this.settlePort = settlePort;
        this.statusLogMapper = statusLogMapper;
        this.pickupPort = pickupPort;
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
        Split split = split(cmd);
        Map<String, String> stores = storesOf(cmd, split);

        List<ai.neargo.shop.trade.dto.CheckoutCapabilityVO.MerchantCapability> rows =
                new ArrayList<>();
        java.util.Set<String> usable = null;
        boolean anyNoInvoice = false;

        for (Group g : split.groups) {
            var cap = merchantPort.payCapabilityOf(g.merchantNo, stores.get(g.merchantNo));
            long amount = g.goodsAmount() + g.freight;
            boolean noInvoice = !cap.invoiceCapable();
            anyNoInvoice = anyNoInvoice || noInvoice;

            rows.add(new ai.neargo.shop.trade.dto.CheckoutCapabilityVO.MerchantCapability(
                    g.merchantNo, g.merchantName, cap.invoiceCapable(),
                    new ArrayList<>(cap.payMethods()),
                    cap.quotaExhausted(), cap.wouldExceed(amount)));

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
        for (Line line : split.items) {
            var modes = payModeService.availablePayModes(
                    line.snapshot.goodsNo(), stores.get(line.snapshot.merchantNo()));
            payModes = payModes == null ? new java.util.LinkedHashSet<>(modes)
                    : intersect(payModes, modes);
        }
        return new ai.neargo.shop.trade.dto.CheckoutCapabilityVO(
                usable == null ? null : new ArrayList<>(usable), anyNoInvoice, rows,
                payModes == null ? List.of(ai.neargo.shop.common.PayModes.ONLINE)
                        : new ArrayList<>(payModes));
    }

    private static java.util.Set<String> intersect(java.util.Set<String> a,
                                                   java.util.Set<String> b) {
        java.util.Set<String> out = new java.util.LinkedHashSet<>(a);
        out.retainAll(b);
        return out;
    }

    @Override
    public OrderVO preview(CreateOrderCommand cmd) {
        Split split = split(cmd);
        // 预览不落库、不锁库存：用户可能在结算页反复改地址与履约方式。
        // 但**优惠要按下单时同一套规则算**，否则结算页显示的金额和实付对不上
        return split.toVO(discountsOf(cmd, split));
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
        return storesOfEntities(cmd, split.groups.stream().map(Group::merchantNo).toList());
    }

    /**
     * 同一套解析，但只要主体号 —— <b>拆单前就要用它取门店价</b>，
     * 而那时 {@link Split} 还没建出来。
     */
    private Map<String, String> storesOfEntities(CreateOrderCommand cmd, List<String> merchantNos) {
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
        for (String merchantNo : merchantNos) {
            // 一次订单可以拆给多家商家，自提点只可能属于其中一家（或谁都不属于）
            boolean mine = pickupStoreNo != null
                    && merchantPort.storeNos(merchantNo).contains(pickupStoreNo);
            if (mine) {
                out.put(merchantNo, pickupStoreNo);
                continue;
            }
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
            if (defaultStore != null && (communityNo == null
                    || merchantPort.reachableCommunities(merchantNo, defaultStore).contains(communityNo))) {
                out.put(merchantNo, defaultStore);
                continue;
            }
            // 默认店服务不了：挑一家真的服务这个社区的（多家都行时取最近，理由见方法注释）
            String served = communityNo == null ? null
                    : nearestServingStore(merchantNo, communityNo);
            out.put(merchantNo, served != null ? served : defaultStore);
        }
        return out;
    }

    /**
     * 这个主体名下**服务该社区**的门店里离得最近的那家；一家都没有时返回 null。
     *
     * <p><b>只在默认店服务不了时才会走到这里</b>（见调用处）—— 所以「取最近」
     * 影响的是一个原先必然出错的场合，不会去动本来就正确的那些单。
     *
     * <p>「服务」的判据与可见性同一个出口（{@code reachableCommunities(entityNo, storeNo)}）——
     * 另写一套迟早分岔，而分岔的表现是「他看得见却下不了单」或者反过来。
     */
    private String nearestServingStore(String merchantNo, String communityNo) {
        List<String> stores = merchantPort.storeNos(merchantNo);
        if (stores.isEmpty()) {
            return null;
        }
        List<String> serving = stores.stream()
                .filter(st -> merchantPort.reachableCommunities(merchantNo, st).contains(communityNo))
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
    private Discounts discountsOf(CreateOrderCommand cmd, Split split) {
        if (split.groups.isEmpty()) {
            return Discounts.none();
        }
        // 门店级满减只对这单出货的那家店生效 —— 预览与下单走同一个解析，
        // 否则会出现「确认页减了 8 块、提交后没减」
        Map<String, String> stores = storesOf(cmd, split);
        CampaignPort.Discount auto = campaignPort.autoDiscount(split.groups.stream()
                .map(g -> new CampaignPort.MerchantAmount(
                        g.merchantNo, g.goodsAmount(), stores.get(g.merchantNo)))
                .toList());
        if (cmd.couponNo() == null || cmd.couponNo().isBlank()) {
            return new Discounts(auto, CouponPort.Allocation.none());
        }
        CouponPort.Allocation coupon = couponPort.allocate(SecurityUtils.currentUserNo(), cmd.couponNo(),
                split.groups.stream()
                        .map(g -> new CouponPort.MerchantAmount(
                                g.merchantNo, g.goodsAmount() - auto.of(g.merchantNo)))
                        .toList());
        return new Discounts(auto, coupon);
    }

    /**
     * 一笔订单上的全部优惠。
     *
     * <p>把两种优惠合成一个对象，而不是让下面的落库代码分别问两次 ——
     * 分别问的话，每加一种优惠就要在主单、子单、VO 三处各改一遍，
     * 而漏改一处的症状是「金额对不上」，最难查的那种。
     */
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

        /** 商家出资部分。活动**恒为商家出资**（店铺级活动平台不掏这个钱） */
        long merchantFunded(String merchantNo) {
            return auto.of(merchantNo) + (coupon.byMerchant() ? coupon.discountOf(merchantNo) : 0L);
        }

        long platformFunded(String merchantNo) {
            return coupon.byMerchant() ? 0L : coupon.discountOf(merchantNo);
        }
    }

    @Override
    @Transactional
    public OrderVO create(CreateOrderCommand cmd, String idempotencyKey) {
        String userNo = SecurityUtils.currentUserNo();
        return idempotency.execute(idempotencyKey, "POST /mp/order", userNo, OrderVO.class,
                () -> doCreate(cmd, userNo));
    }

    /**
     * ⚠️ 事务注解放在 {@link #create} 上而不是这里：本方法是被同类的 lambda 调用的，
     * 自调用不走代理，写在这里的 {@code @Transactional} 完全不生效 ——
     * 那样「锁了库存但订单没落库」会变成常态，且测试很难发现。
     */
    private OrderVO doCreate(CreateOrderCommand cmd, String userNo) {
        Split split = split(cmd);
        if (split.items.isEmpty()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
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
         * 支付方式的三道校验。**全部前置且只读**，不改上面任何分支的顺序 ——
         * create 是全站最要害的方法，加东西的正确姿势是「在它之前挡住」，
         * 不是「在它中间插一脚」。
         */
        String payMode = requirePayModeSupported(cmd, split, storeOfMerchant);
        requireReceiverWhenShipped(cmd, userNo);
        requireWithinDeliveryRadius(cmd, split, userNo);
        requirePickupPointWhenPickup(cmd);
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
                String storeNo = storeOfMerchant.get(g.merchantNo());
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
                    String storeNo = storeOfMerchant.get(g.merchantNo());
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
        Discounts discounts = discountsOf(cmd, split);

        /*
         * 子单号**提前生成**：积分抵扣要落到各个子单上（三家里退了一家时才退得准），
         * 而扣减必须在落库之前完成 —— 余额不足要能降级成不抵扣，不能让订单先落库再回滚。
         */
        Map<String, String> subOrderNoOf = new LinkedHashMap<>();
        for (Group g : split.groups) {
            subOrderNoOf.put(g.merchantNo, BizKey.next(BizKey.SUB_ORDER));
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
                                g.goodsAmount() - discounts.of(g.merchantNo),
                                subOrderNoOf.get(g.merchantNo)))
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
                && split.groups().stream().anyMatch(g -> discounts.platformFunded(g.merchantNo()) > 0)) {
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
        order.setPayDeadlineAt(now + closeRuleService.unpaidMinutes() * 60_000L);

        /*
         * 弱主体限额（F-6）。**按拆单后的每个商家分别判**，不是按整单总额：
         * 限额是平台对单个商家的敞口上限，跨商家合单再按总额判，
         * 会因为同车买了别家的东西而误拦这一家。
         *
         * 放在 insert 之前：虽然事务回滚也能收拾，但先落库再抛会让
         * 订单号消耗掉、日志里留下一条永远查不到的单。
         */
        Map<String, Boolean> needsConfirm = new java.util.HashMap<>();
        for (Group g : split.groups) {
            long merchantPay = g.goodsAmount() + g.freight - discounts.of(g.merchantNo);
            admissionPort.requireOrderAllowed(g.merchantNo, merchantPay,
                    () -> paidAmountToday(g.merchantNo));
            /*
             * 准入矩阵（§7.7）：这个主体能不能用这种履约方式。
             * 结论在下单这一刻定死并落进子单 —— 商家事后换自提点运营者，
             * 历史单的判定不该跟着变。
             */
            needsConfirm.put(g.merchantNo, admissionPort.requireFulfillmentAllowed(
                    g.merchantNo, cmd.fulfillment(), cmd.pickupNo()));

            /*
             * 收款额度（P2-3）。**在下单这一步拦，而不是等付款时通道拒绝**：
             * 通道拒绝表现为「支付失败」四个字，买家不知道该换一家买，
             * 商家不知道该去升主体，运营不知道该去核对额度口径 —— 三方都卡住。
             *
             * 用 wouldExceed 而不是 quotaExhausted：正好卡在额度边缘的那一单，
             * 放过去仍然会在通道侧失败。
             */
            var cap = merchantPort.payCapabilityOf(g.merchantNo, storeOfMerchant.get(g.merchantNo));
            if (cap.wouldExceed(merchantPay)) {
                throw BizException.of(ErrorCode.MERCHANT_QUOTA_EXHAUSTED);
            }
        }

        orderMapper.insert(order);

        List<String> subOrderNos = new ArrayList<>();
        for (Group g : split.groups) {
            String subOrderNo = subOrderNoOf.get(g.merchantNo);
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
            sub.setStoreNo(storeOfMerchant.get(g.merchantNo));
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
            sub.setPickupNo(cmd.pickupNo());
            // 自提点名称快照（C6）：页面要显示名字，且自提点改名不该影响历史订单
            sub.setPickupName(pickupPort.find(cmd.pickupNo()).map(p -> p.name()).orElse(null));
            sub.setAddressId(cmd.addressId());
            /*
             * 收件人快照（V69）：与上面的 pickupName 同一个理由 ——
             * usr_address 可改可删，买家下完单改成新家，商家看到的就跟着变了，
             * 而货已经按旧地址在路上。
             *
             * **取不到不让下单失败**：自提单本来就没有 addressId；
             * 快递/自送单万一取不到，宁可让商家看到「地址：—」去问一句，
             * 也不该把已经付过钱的单挡在这里。
             */
            userPort.receiverOf(userNo, cmd.addressId()).ifPresent(r -> {
                sub.setReceiverName(r.name());
                sub.setReceiverPhone(r.phone());
                sub.setReceiverAddress(r.address());
            });
            // ★ 归因在下单这一刻固化，不是结算时回查（TDD-backend §7.4）
            sub.setTrafficSource(attributionPort.resolveTrafficSource(userNo, g.merchantNo));
            sub.setGoodsAmount(g.goodsAmount());
            sub.setFreightAmount(g.freight);
            long discount = discounts.of(g.merchantNo);
            sub.setDiscountAmount(discount);
            // 出资方分列（Q9）：合成一列的话 M7 分账无法判断该扣谁的钱
            sub.setDiscountPlatform(discounts.platformFunded(g.merchantNo));
            sub.setDiscountMerchant(discounts.merchantFunded(g.merchantNo));
            // 积分快照：结算与售后直接读这两列，不用回查积分流水
            long pointsAmount = points.amountOf(subOrderNo);
            sub.setPointsDeduct((int) points.pointsOf(subOrderNo));
            sub.setPointsDeductMinor(pointsAmount);
            sub.setPayAmount(g.goodsAmount() + g.freight - discount - pointsAmount);
            sub.setRequireBuyerConfirm(
                    Boolean.TRUE.equals(needsConfirm.get(g.merchantNo)) ? 1 : 0);
            sub.setStatus(OrdSubOrder.WAIT_PAY);
            sub.setRemark(cmd.remark());
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

        return detail(orderNo);   // 下单返回支付视角：端上下一步就是去收银台
    }

    // ---------------------------------------------------------------- 支付

    @Override
    public PayResult pay(String orderNo) {
        OrdOrder order = requireOwnOrder(orderNo);
        if (!OrdOrder.WAIT_PAY.equals(order.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        if (order.getPayDeadlineAt() != null && order.getPayDeadlineAt() < System.currentTimeMillis()) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        // S2 是 stub 通道：返回的参数结构与微信 JSAPI 一致，S4 换真通道时端上不用改
        return new PayResult(orderNo, "STUB", Map.of(
                "prepayId", "stub_" + orderNo,
                "amount", String.valueOf(order.getPayAmount())));
    }

    @Override
    public OrderVO payResult(String orderNo) {
        return detail(orderNo);
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
                AfterCommit.run("发放积分 subOrderNo=" + subNo,
                        () -> grantPointsAfterPay(subNo, userNo, entityNo, base));
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
    private void grantPointsAfterPay(String subOrderNo, String userNo, String entityNo, long base) {
        var g = pointsPort.grant(userNo, entityNo, earnLines(subOrderNo, base), subOrderNo);
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
    public OrderVO detail(String orderNo) {
        // Q6：先按子单号查（C 端绝大多数请求是订单视角），查不到再按主单号
        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, orderNo)
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (sub != null) {
            OrdOrder order = orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                    .eq(OrdOrder::getOrderNo, sub.getOrderNo()).last("limit 1"));
            return orderView(sub, order);
        }
        OrdOrder order = requireOwnOrder(orderNo);
        return payView(order, subOrders(orderNo));
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
        return detail(order.getOrderNo());
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
        return detail(subOrderNo);
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
        Map<String, String> storeByEntity = storesOfEntities(cmd,
                snapshots.values().stream().map(GoodsQueryPort.SkuSnapshot::merchantNo).distinct().toList());
        if (!goodsPort.storePrices(storeByEntity, skuNos).isEmpty()) {
            snapshots = goodsPort.snapshot(skuNos, storeByEntity);
        }

        List<Line> lines = new ArrayList<>();
        for (CreateOrderCommand.Item item : requested) {
            GoodsQueryPort.SkuSnapshot s = snapshots.get(item.skuNo());
            if (s == null || !s.onSale()) {
                throw BizException.of(ErrorCode.NOT_FOUND);   // 下架商品不允许进入结算
            }
            lines.add(new Line(s, item.qty()));
        }

        // 按商家分组 —— 保持插入序，让预览与订单详情里子单的顺序稳定
        Map<String, List<Line>> byMerchant = lines.stream().collect(Collectors.groupingBy(
                l -> l.snapshot.merchantNo(), LinkedHashMap::new, Collectors.toList()));

        List<Group> groups = byMerchant.entrySet().stream().map(e -> {
            String merchantName = merchantPort.find(e.getKey())
                    .map(MerchantQueryPort.MerchantBrief::merchantName).orElse("");
            // S2 运费恒 0：运费模板属于 P-5.2.3（运营端配置），S3 履约一起做。
            // 先给 0 而不是编一个假数字 —— 假数字会被端上当真的展示给用户
            return new Group(e.getKey(), merchantName, e.getValue(), 0L);
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

    private record Group(String merchantNo, String merchantName, List<Line> lines, long freight) {
        long goodsAmount() {
            return lines.stream().mapToLong(Line::amount).sum();
        }
    }

    private record Split(List<Line> items, List<Group> groups) {
        long goodsAmount() {
            return groups.stream().mapToLong(Group::goodsAmount).sum();
        }

        long freightAmount() {
            return groups.stream().mapToLong(Group::freight).sum();
        }

        long payAmount() {
            return goodsAmount() + freightAmount();
        }

        /** 预览走**支付视角**：结算页要看的是合计金额与按商家的分组。 */
        OrderVO toVO(Discounts discounts) {
            List<OrderVO> children = groups.stream().map(g -> new OrderVO(
                    null, null, OrdOrder.WAIT_PAY, null, g.merchantNo, g.merchantName,
                    g.lines.stream().map(l -> new OrderVO.ItemVO(
                            l.snapshot.goodsNo(), g.merchantNo, l.snapshot.skuNo(), l.snapshot.title(),
                            l.snapshot.cover(), l.snapshot.spec(), l.snapshot.price(), l.qty,
                            l.amount(), l.snapshot.categoryType(), false)).toList(),
                    OrderVO.Amount.of(g.goodsAmount(), g.freight,
                            discounts.of(g.merchantNo), 0L, CURRENCY_CNY),
                    // 预览还没有单，收件人与预约时间自然也没有
                    null, null, null, null, 0L, null, null, null, null, null, List.of(), null,
                // 买家昵称只在商家侧下发（B12）——C 端自己就是买家，不需要
                null)).toList();

            return new OrderVO(null, null, OrdOrder.WAIT_PAY, null, null, null,
                    children.stream().flatMap(c -> c.items().stream()).toList(),
                    OrderVO.Amount.of(goodsAmount(), freightAmount(),
                            discounts.total(), 0L, CURRENCY_CNY),
                    null, null, null, null, 0L, null, null, null, null, null, List.of(), children,
                // 买家昵称只在商家侧下发（B12）——C 端自己就是买家，不需要
                null);
        }
    }

    // ---------------------------------------------------------------- 装配

    private OrdOrder requireOwnOrder(String orderNo) {
        // 属主鉴权：查询条件带 userNo，而不是查出来再判 —— 防 IDOR 的第一层
        OrdOrder order = orderMapper.selectOne(Wrappers.<OrdOrder>lambdaQuery()
                .eq(OrdOrder::getOrderNo, orderNo)
                .eq(OrdOrder::getUserNo, SecurityUtils.currentUserNo())
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

    /** 订单视角（Q6）：单商家，有履约方式、核销码与时间线。 */
    private OrderVO orderView(OrdSubOrder s, OrdOrder order) {
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
                null);
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
                null);
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
        if (!OFFLINE_PAYABLE.contains(cmd.fulfillment())) {
            throw BizException.of(ErrorCode.PAY_MODE_NOT_SUPPORTED);
        }
        for (Group g : split.groups()) {
            String storeNo = storeOfMerchant.get(g.merchantNo());
            for (Line line : g.lines()) {
                if (!payModeService.availablePayModes(line.snapshot().goodsNo(), storeNo)
                        .contains(payMode)) {
                    throw BizException.of(ErrorCode.PAY_MODE_NOT_SUPPORTED);
                }
            }
        }
        return payMode;
    }

    /**
     * 允许线下支付的履约方式 —— 判据是「<b>有没有当面收钱的那一刻</b>」。
     *
     * <p>货到付款（{@code MERCHANT_DELIVERY}）在列，但它另有一道门店级开关
     * （{@code mch_store.cod_enabled}）：它是整张组合表里风险最高的一格，
     * 拒收跑单的损失全在商家，所以要商家自己打开。
     */
    private static final java.util.Set<String> OFFLINE_PAYABLE = java.util.Set.of(
            Fulfillments.STORE_PICKUP, Fulfillments.MERCHANT_DELIVERY,
            Fulfillments.STORE_VERIFY, Fulfillments.APPOINTMENT);

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
        if (!Fulfillments.MERCHANT_DELIVERY.equals(cmd.fulfillment())
                || cmd.addressId() == null || cmd.addressId().isBlank()) {
            return;
        }
        var receiver = userPort.receiverOf(userNo, cmd.addressId()).orElse(null);
        if (receiver == null || receiver.latE6() == null || receiver.lngE6() == null) {
            return;
        }
        /*
         * 逐商家判：购物车跨商家时会拆成多张子单，各家的圆心与半径都不同。
         * 只要有一家送不到，这一单就下不成 —— 让他先拆开或换送货方式，
         * 比下成之后由那一家单独退款要好解释。
         */
        for (var g : split.groups) {
            var origin = merchantPort.deliveryOrigin(g.merchantNo()).orElse(null);
            if (origin == null || origin.radiusM() <= 0) {
                continue;
            }
            if (metersBetween(origin.latE6(), origin.lngE6(), receiver.latE6(), receiver.lngE6())
                    > origin.radiusM()) {
                throw BizException.of(ErrorCode.OUT_OF_DELIVERY_RANGE);
            }
        }
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
    private void requirePickupPointWhenPickup(CreateOrderCommand cmd) {
        if (cmd.fulfillment() == null || !Fulfillments.isPickup(cmd.fulfillment())) {
            return;
        }
        if (cmd.pickupNo() == null || cmd.pickupNo().isBlank()
                || pickupPort.find(cmd.pickupNo()).isEmpty()) {
            throw BizException.of(ErrorCode.PICKUP_POINT_REQUIRED);
        }
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
            if (appointmentSlotPort.hasOpenSlots(storeOfMerchant.get(g.merchantNo))) {
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
        String storeNo = storeOfMerchant.get(split.groups.get(0).merchantNo);
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
            java.util.Set<String> enabled = merchantPort.enabledFulfillmentsFor(
                    g.merchantNo, storeOfMerchant.get(g.merchantNo), communityNo);
            if (!enabled.isEmpty() && !enabled.contains(fulfillment)) {
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
                    it.getGoodsNo(), it.getCategoryNo(), share));
        }
        OrdItem big = items.get(biggest);
        out.set(biggest, new ai.neargo.shop.spi.settle.PointsPort.EarnLine(
                big.getGoodsNo(), big.getCategoryNo(), base - allocated));
        return out;
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
