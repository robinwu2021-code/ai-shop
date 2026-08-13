package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.user.PickupQueryPort;
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
import ai.neargo.shop.common.BizKey;
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

    /** 15 分钟未支付关单。与端上倒计时一致，两边都从 {@code expireAt} 读，不各自算。 */
    private static final Duration PAY_TTL = Duration.ofMinutes(15);
    private static final String CURRENCY_CNY = "CNY";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final OrderMapper orderMapper;
    private final SubOrderMapper subOrderMapper;
    private final ai.neargo.shop.spi.user.AdmissionPort admissionPort;
    private final OrderItemMapper itemMapper;
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
    private final IdempotencyService idempotency;
    private final OutboxEventBus eventBus;

    public OrderServiceImpl(OrderMapper orderMapper, SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                            CartItemMapper cartMapper, GoodsQueryPort goodsPort, StockPort stockPort,
                            MerchantQueryPort merchantPort,
                            ai.neargo.shop.spi.user.MerchantAdminPort merchantAdminPort,
                            AttributionPort attributionPort,
                            CouponPort couponPort, CampaignPort campaignPort, PointsPort pointsPort,
                            SettlePort settlePort,
                            StatusLogMapper statusLogMapper,
                            PickupQueryPort pickupPort,
                            ai.neargo.shop.spi.user.UserQueryPort userPort,
                            IdempotencyService idempotency, OutboxEventBus eventBus,
                            ai.neargo.shop.spi.user.AdmissionPort admissionPort) {
        this.orderMapper = orderMapper;
        this.subOrderMapper = subOrderMapper;
        this.admissionPort = admissionPort;
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
        this.idempotency = idempotency;
        this.eventBus = eventBus;
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
        return new ai.neargo.shop.trade.dto.CheckoutCapabilityVO(
                usable == null ? null : new ArrayList<>(usable), anyNoInvoice, rows);
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
        Map<String, String> out = new HashMap<>();
        String pickupStoreNo = pickupPort.find(cmd.pickupNo())
                .map(ai.neargo.shop.spi.user.PickupQueryPort.PickupBrief::ownerStoreNo)
                .filter(no -> no != null && !no.isBlank())
                .orElse(null);
        for (Group g : split.groups) {
            // 一次订单可以拆给多家商家，自提点只可能属于其中一家（或谁都不属于）
            boolean mine = pickupStoreNo != null
                    && merchantPort.storeNos(g.merchantNo).contains(pickupStoreNo);
            if (mine) {
                out.put(g.merchantNo, pickupStoreNo);
            } else {
                merchantPort.defaultStoreNo(g.merchantNo).ifPresent(no -> out.put(g.merchantNo, no));
            }
        }
        return out;
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
        requireFulfillmentSupported(cmd.fulfillment(), split);

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

        /*
         * 履约门店：**在锁库存之前算好，并与写进子单的那个值同一个来源**。
         *
         * 两处各算一次的话，迟早会出现「扣了 A 店的库存、订单却记在 B 店」——
         * 那种错不会报错，只会在盘点时表现成两家店的账都对不上。
         */
        Map<String, String> storeOfMerchant = storesOf(cmd, split);

        // ⑤ 锁库存 —— 放在落库之前：库存不足就整单失败，不留半张订单
        try {
            List<StockPort.SkuQty> lock = new ArrayList<>();
            for (Group g : split.groups) {
                String storeNo = storeOfMerchant.get(g.merchantNo);
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
                    String storeNo = storeOfMerchant.get(g.merchantNo);
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
                        .toList());

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
        order.setStatus(OrdOrder.WAIT_PAY);
        /*
         * 社区固化到主单上。**运营按社区做数据域隔离** —— 不写的话，
         * 平台端按社区筛订单永远是空的，而列表本身是好的，看起来只是「这个社区没单」。
         *
         * 固化而不是每次现查用户当前绑定：用户搬家换社区后，历史订单仍属于当时那个社区，
         * 否则昨天的单会跳到新社区的报表里。
         */
        userPort.communityOf(userNo).ifPresent(order::setCommunityNo);
        order.setPayDeadlineAt(now + PAY_TTL.toMillis());

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
                    gift.setIsGift(true);
                    itemMapper.insert(gift);
                }
            }
        }

        // 清掉已下单的购物车行
        cartMapper.delete(Wrappers.<TrdCartItem>lambdaQuery()
                .eq(TrdCartItem::getUserNo, userNo)
                .in(TrdCartItem::getSkuNo, split.items.stream().map(Line::skuNo).toList()));

        // 核销券。放在落库之后：券核销失败要能连订单一起回滚
        couponPort.markUsed(userNo, cmd.couponNo(), orderNo);

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
            OrderStateMachine.assertSubOrderTransit(sub.getStatus(), OrdSubOrder.WAIT_FULFILL);
            sub.setStatus(OrdSubOrder.WAIT_FULFILL);
            // 核销码在支付成功后生成：未付款的订单不该有能核销的码。
            // 全局唯一由 uk_verify_code 兜底 —— 撞码时插入失败总比核销台扫出两单强
            sub.setVerifyCode(newVerifyCode());
            subOrderMapper.updateById(sub);
            appendStatusLog(sub.getSubOrderNo(), OrdSubOrder.WAIT_FULFILL, "支付成功，待备货",
                    OrdStatusLog.BY_SYSTEM, null);

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
                long base = sub.getPayAmount() == null ? 0L
                        : sub.getPayAmount() - (sub.getFreightAmount() == null ? 0L : sub.getFreightAmount());
                var g = pointsPort.grant(order.getUserNo(), sub.getEntityNo(),
                        base, sub.getSubOrderNo());
                if (g.points() > 0) {
                    sub.setPointsGranted(true);
                    /*
                     * 费用金落在子单上，**结算时才真的扣**（发分即付，从货款里出）。
                     *
                     * 此前这一列全库零写入 —— 于是 stl_bill 也拿不到值，
                     * B 端「本期积分支出」永远是 0，而池子只出不进：
                     * 用户花分时 MERCHANT_PAY 出账，发分时却没有对应的入账，
                     * 恒等式 2 会随发放量单调失衡。
                     */
                    sub.setPointsFeeMinor(g.feeMinor());
                    subOrderMapper.updateById(sub);
                }
            }
        }

        // 结算单与支付状态同事务生成（理由见 SettlePort#generateForOrder）
        settlePort.generateForOrder(orderNo);

        eventBus.publish(new OrderEvents.OrderPaid(orderNo, order.getUserNo(),
                order.getPayAmount(), payChannel));
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
    public PageData<OrderVO> list(String status, long page, long size) {
        // Q6：列表是子单粒度
        var w = Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo());
        // 同 B 端：端上传的是展示状态（PAID / SHIPPED / ARRIVED），库里存的是 WAIT_FULFILL / FULFILLING
        List<String> stored = OrderStatusView.toStored(status);
        if (!stored.isEmpty()) {
            w.in(OrdSubOrder::getStatus, stored);
            Boolean pickupOnly = OrderStatusView.pickupOnly(status);
            if (Boolean.TRUE.equals(pickupOnly)) {
                w.in(OrdSubOrder::getFulfillment, PICKUP_FULFILLMENTS);
            } else if (Boolean.FALSE.equals(pickupOnly)) {
                w.and(x -> x.notIn(OrdSubOrder::getFulfillment, PICKUP_FULFILLMENTS)
                        .or().isNull(OrdSubOrder::getFulfillment));
            }
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
        // 积分按子单退。券是整单一张、积分是每个子单一条 ——
        // 所以这里逐个子单退，而不是像券那样传 orderNo
        for (OrdSubOrder sub : subOrders(order.getOrderNo())) {
            pointsPort.reverse(sub.getSubOrderNo(), "订单已取消");
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
        // 同上，积分逐子单退。reverse 只认 PENDING 的 USE 流水，重复跑不会退两次
        for (OrdSubOrder sub : subOrders(order.getOrderNo())) {
            pointsPort.reverse(sub.getSubOrderNo(), reason + "，订单关闭");
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

        Map<String, GoodsQueryPort.SkuSnapshot> snapshots =
                goodsPort.snapshot(requested.stream().map(CreateOrderCommand.Item::skuNo).toList());

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
                    // 预览还没有单，收件人自然也没有
                    null, null, null, null, 0L, null, null, null, null, List.of(), null,
                // 买家昵称只在商家侧下发（B12）——C 端自己就是买家，不需要
                null)).toList();

            return new OrderVO(null, null, OrdOrder.WAIT_PAY, null, null, null,
                    children.stream().flatMap(c -> c.items().stream()).toList(),
                    OrderVO.Amount.of(goodsAmount(), freightAmount(),
                            discounts.total(), 0L, CURRENCY_CNY),
                    null, null, null, null, 0L, null, null, null, null, List.of(), children,
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
                // 下发**展示状态**而不是库状态：端上的标签页按前者筛（见 OrderStatusView 的注释）
                s.getSubOrderNo(), s.getOrderNo(),
                OrderStatusView.of(s.getStatus(), s.getFulfillment()), s.getFulfillment(),
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
                // 支付视角跨商家，没有单一快递号 —— 它在每个子单上。收件人同理
                null, null, null, List.of(), children,
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
    private void requireFulfillmentSupported(String fulfillment, Split split) {
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

    /** 6 位核销码。撞码由 uk_verify_code 兜底 —— 插入失败总比核销台扫出两单强。 */
    private String newVerifyCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    private static long millis(java.time.LocalDateTime t) {
        return t == null ? 0L : t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
