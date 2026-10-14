package ai.neargo.shop.trade.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.ExpressCompanies;
import ai.neargo.shop.common.PayModes;
import ai.neargo.shop.spi.fulfillment.ExpressPickupPort;
import ai.neargo.shop.spi.platform.PlatformSwitchPort;
import ai.neargo.shop.spi.user.MerchantDebtPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.trade.entity.OrdExpressPickup;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.ExpressPickupMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.service.ExpressPickupService;
import ai.neargo.shop.trade.service.MerchantOrderService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class ExpressPickupServiceImpl implements ExpressPickupService {

    private static final Logger log = LoggerFactory.getLogger(ExpressPickupServiceImpl.class);

    /** 申报重量上下限（克）。上限跟着快递大件口径（德邦大件 30kg），再重的走快运，不是这条路 */
    static final int MIN_WEIGHT_G = 100;
    static final int MAX_WEIGHT_G = 30_000;
    /** 物品名给快递员看，京东、圆通必填。太长的商品名截短 */
    private static final int CARGO_MAX = 20;

    /**
     * 我们的状态只进不退：晚到的旧推送不能把「已取件」拉回「已接单」。
     * 取消与失败不在这条链上 —— 它们只能从取件前进入（见 {@link #nextStatus}）。
     */
    private static final Map<String, Integer> RANK = Map.of(
            OrdExpressPickup.CREATED, 0, OrdExpressPickup.ACCEPTED, 1,
            OrdExpressPickup.PICKED, 2, OrdExpressPickup.DONE, 3);

    private final ExpressPickupPort port;
    private final ExpressPickupMapper pickupMapper;
    private final SubOrderMapper subOrderMapper;
    private final OrderItemMapper itemMapper;
    private final MerchantQueryPort merchantPort;
    private final MerchantDebtPort debtPort;
    private final MerchantOrderService merchantOrderService;
    private final PlatformSwitchPort switchPort;

    public ExpressPickupServiceImpl(ExpressPickupPort port, ExpressPickupMapper pickupMapper,
                                    SubOrderMapper subOrderMapper, OrderItemMapper itemMapper,
                                    MerchantQueryPort merchantPort, MerchantDebtPort debtPort,
                                    MerchantOrderService merchantOrderService, PlatformSwitchPort switchPort) {
        this.port = port;
        this.pickupMapper = pickupMapper;
        this.subOrderMapper = subOrderMapper;
        this.itemMapper = itemMapper;
        this.merchantPort = merchantPort;
        this.debtPort = debtPort;
        this.merchantOrderService = merchantOrderService;
        this.switchPort = switchPort;
    }

    // ── 商家侧 ───────────────────────────────────────────────────────────

    @Override
    public List<QuoteVO> quotes(String merchantNo, String storeNo, String subOrderNo, BigDecimal weightKg) {
        requireChannel();
        int weightG = grams(weightKg);
        OrdSubOrder sub = requireShippable(merchantNo, storeNo, subOrderNo);
        ExpressPickupPort.Party sender = sender(sub);
        ExpressPickupPort.Party receiver = receiver(sub);
        boolean sandbox = testMode();
        /*
         * 各家并发查：一家 1–3 秒，八家串行就是十几秒，商家早关页面了。
         * 虚拟线程：这是纯等网络的活，不该占公共线程池。
         */
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Optional<ExpressPickupPort.Quote>>> fs = port.carriers().stream()
                    .map(c -> CompletableFuture.supplyAsync(
                            () -> port.quote(c, sender.address(), receiver.address(), weightG, sandbox), pool))
                    .toList();
            return fs.stream()
                    .map(CompletableFuture::join)
                    .flatMap(Optional::stream)
                    .sorted(Comparator.comparingLong(ExpressPickupPort.Quote::priceMinor))
                    .map(q -> new QuoteVO(q.carrier(), ExpressCompanies.nameOf(q.carrier()),
                            q.priceMinor(), q.listPriceMinor()))
                    .toList();
        }
    }

    @Override
    public PickupVO create(String merchantNo, String storeNo, String subOrderNo, String carrier,
                           BigDecimal weightKg) {
        requireChannel();
        if (carrier == null || !port.carriers().contains(carrier)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        int weightG = grams(weightKg);
        OrdSubOrder sub = requireShippable(merchantNo, storeNo, subOrderNo);
        if (open(sub.getSubOrderNo()).isPresent()) {
            throw BizException.of(ErrorCode.EXPRESS_PICKUP_EXISTS);
        }
        ExpressPickupPort.Party sender = sender(sub);
        ExpressPickupPort.Party receiver = receiver(sub);

        /*
         * **先落行、再下单**。反过来的话，通道那边已经派了快递员，而我们这边一行都没有 ——
         * 回调来了认不出、运费也记不上。先落的这行在下单失败时改成 FAILED 留着，排查用。
         */
        OrdExpressPickup p = new OrdExpressPickup();
        p.setPickupNo(BizKey.next(BizKey.EXPRESS_PICKUP));
        p.setSubOrderNo(sub.getSubOrderNo());
        p.setOrderNo(sub.getOrderNo());
        p.setEntityNo(sub.getEntityNo());
        p.setStoreNo(senderStoreNo(sub));
        p.setProvider(OrdExpressPickup.PROVIDER_KUAIDI100);
        p.setCarrier(carrier);
        p.setStatus(OrdExpressPickup.CREATED);
        p.setWeightG(weightG);
        p.setFreightBookedMinor(0L);
        // 环境在下单这一刻定、并落库：之后开关怎么拨，这一单的取消都回到它下单的那个环境
        p.setSandbox(testMode());
        stamp(p, true);
        DataScopeContext.executeWithoutScope(() -> pickupMapper.insert(p));

        ExpressPickupPort.Booked b = port.create(new ExpressPickupPort.CreateCmd(
                p.getPickupNo(), carrier, weightG, cargo(sub), sender, receiver, Boolean.TRUE.equals(p.getSandbox())));
        if (!b.ok()) {
            p.setStatus(OrdExpressPickup.FAILED);
            p.setFailReason(truncate(b.message(), 255));
            save(p);
            throw BizException.of(ErrorCode.EXPRESS_PROVIDER_REJECTED, nz(b.message()));
        }
        p.setTaskId(b.taskId());
        p.setProviderOrderId(b.orderId());
        if (b.trackingNo() != null) {
            // 下单即出单号的快递（电子面单先出）：先记着，**取件后才回填到子单** —— 取件前还可能取消
            p.setTrackingNo(b.trackingNo());
        }
        save(p);
        log.info("[express] 代下单 {} sub={} carrier={} weight={}g taskId={}",
                p.getPickupNo(), sub.getSubOrderNo(), carrier, weightG, b.taskId());
        return toVO(p);
    }

    @Override
    public PickupVO latest(String merchantNo, String storeNo, String subOrderNo) {
        OrdSubOrder sub = requireOwned(merchantNo, storeNo, subOrderNo);
        OrdExpressPickup p = DataScopeContext.executeWithoutScope(() ->
                pickupMapper.selectOne(Wrappers.<OrdExpressPickup>lambdaQuery()
                        .eq(OrdExpressPickup::getSubOrderNo, sub.getSubOrderNo())
                        .orderByDesc(OrdExpressPickup::getId)
                        .last("limit 1")));
        return p == null ? null : toVO(p);
    }

    @Override
    public PickupVO cancel(String merchantNo, String storeNo, String subOrderNo) {
        OrdSubOrder sub = requireOwned(merchantNo, storeNo, subOrderNo);
        OrdExpressPickup p = open(sub.getSubOrderNo()).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
        if (OrdExpressPickup.PICKED.equals(p.getStatus())) {
            throw BizException.of(ErrorCode.EXPRESS_NOT_CANCELLABLE);
        }
        if (p.getTaskId() != null) {
            ExpressPickupPort.Booked b = port.cancel(p.getTaskId(), p.getProviderOrderId(), "商家取消",
                    Boolean.TRUE.equals(p.getSandbox()));
            if (!b.ok()) {
                throw BizException.of(ErrorCode.EXPRESS_PROVIDER_REJECTED, nz(b.message()));
            }
        }
        p.setStatus(OrdExpressPickup.CANCELLED);
        p.setFailReason("商家取消");
        save(p);
        log.info("[express] 商家取消 {} sub={}", p.getPickupNo(), sub.getSubOrderNo());
        return toVO(p);
    }

    // ── 回调 ─────────────────────────────────────────────────────────────

    /*
     * **不开外层事务**，是有意的：里面调的 ship() 自带事务，它一旦抛错会把外层标成只能回滚，
     * catch 住也没用 —— 整条回调提交失败，运费也跟着没记上。
     * 三步各自幂等（ship 同号重发是空操作、欠款按来源号幂等、本行按状态只进不退），
     * 中途断了，通道重推一次就补齐。
     */
    @Override
    public boolean onCallback(String taskId, String sign, String param) {
        Optional<ExpressPickupPort.Callback> parsed = port.parseCallback(taskId, sign, param);
        if (parsed.isEmpty()) {
            log.warn("[express] 回调验签不过或报文坏了 taskId={}", taskId);
            return false;
        }
        ExpressPickupPort.Callback cb = parsed.get();
        String tid = cb.taskId() != null ? cb.taskId() : taskId;
        OrdExpressPickup p = DataScopeContext.executeWithoutScope(() ->
                pickupMapper.selectOne(Wrappers.<OrdExpressPickup>lambdaQuery()
                        .eq(OrdExpressPickup::getTaskId, tid)
                        .last("limit 1")));
        if (p == null) {
            // 下单那一侧还没写回 taskId（回调比下单返回还快）：回失败让通道重推，不丢这条
            log.warn("[express] 回调认不出取件单 taskId={} status={}", tid, cb.providerStatus());
            return false;
        }

        p.setProviderStatus(cb.providerStatus());
        if (cb.trackingNo() != null) {
            p.setTrackingNo(cb.trackingNo());
        }
        if (cb.courierName() != null) {
            p.setCourierName(cb.courierName());
        }
        if (cb.courierMobile() != null) {
            p.setCourierMobile(cb.courierMobile());
        }
        if (cb.chargedWeightG() != null) {
            p.setChargedWeightG(cb.chargedWeightG());
        }
        if (cb.freightMinor() != null) {
            p.setFreightMinor(cb.freightMinor());
        }
        if (cb.listPriceMinor() != null) {
            p.setListPriceMinor(cb.listPriceMinor());
        }
        String next = nextStatus(p.getStatus(), cb.providerStatus());
        if (!next.equals(p.getStatus())) {
            log.info("[express] {} {} → {}（通道 {}）", p.getPickupNo(), p.getStatus(), next, cb.providerStatus());
            p.setStatus(next);
            if (OrdExpressPickup.CANCELLED.equals(next) || OrdExpressPickup.FAILED.equals(next)) {
                p.setFailReason(truncate(cb.message(), 255));
            }
        }
        boolean handedOver = OrdExpressPickup.PICKED.equals(p.getStatus())
                || OrdExpressPickup.DONE.equals(p.getStatus());
        if (handedOver) {
            shipIfNeeded(p);
            bookFreight(p);
        }
        save(p);
        return true;
    }

    /**
     * 通道状态码 → 我们的状态（TDD §2 状态映射）。
     *
     * <p>取消 / 失败只在取件前生效：已取件之后通道再推「取消」，货已经在快递手里了，
     * 不能让运单号回填过的单子显示成「已取消」。{@code 166 订单复活}反过来 —— 取消了但包裹其实发出去了。
     */
    static String nextStatus(String cur, int providerStatus) {
        String mapped = switch (providerStatus) {
            case 0 -> OrdExpressPickup.CREATED;
            case 1, 2, 200, 302 -> OrdExpressPickup.ACCEPTED;
            case 10, 101, 400, 166 -> OrdExpressPickup.PICKED;
            case 13, 14, 15 -> OrdExpressPickup.DONE;
            case 9, 99 -> OrdExpressPickup.CANCELLED;
            case 11, 12, 201, 610 -> OrdExpressPickup.FAILED;
            default -> null;   // 155 修改重量等：只更新数，不动状态
        };
        if (mapped == null || mapped.equals(cur)) {
            return cur;
        }
        boolean curEnded = OrdExpressPickup.CANCELLED.equals(cur) || OrdExpressPickup.FAILED.equals(cur);
        if (curEnded) {
            return providerStatus == 166 ? OrdExpressPickup.PICKED : cur;
        }
        if (OrdExpressPickup.CANCELLED.equals(mapped) || OrdExpressPickup.FAILED.equals(mapped)) {
            return RANK.get(cur) < RANK.get(OrdExpressPickup.PICKED) ? mapped : cur;
        }
        return RANK.get(mapped) > RANK.get(cur) ? mapped : cur;
    }

    /**
     * 取件后回填运单号 —— **走原发货链路**（状态迁移、留痕、微信发货上报都在那里）。
     *
     * <p>商家已经手动填过运单号的不覆盖：那是他自己的决定，两个单号打架时以人为准，
     * 这一张取件单照样记运费（快递员确实上门了）。
     */
    private void shipIfNeeded(OrdExpressPickup p) {
        if (p.getTrackingNo() == null) {
            log.warn("[express] {} 已取件但通道没给运单号，等下一条推送", p.getPickupNo());
            return;
        }
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, p.getSubOrderNo())
                        .last("limit 1")));
        if (sub == null || !OrdSubOrder.WAIT_FULFILL.equals(sub.getStatus())) {
            if (sub != null && !p.getTrackingNo().equals(sub.getExpressNo())) {
                log.warn("[express] {} 取件时子单 {} 已是 {}（运单号 {}），不覆盖", p.getPickupNo(),
                        p.getSubOrderNo(), sub.getStatus(), sub.getExpressNo());
            }
            return;
        }
        try {
            merchantOrderService.ship(sub.getEntityNo(), null, sub.getSubOrderNo(), p.getTrackingNo(), p.getCarrier());
            log.info("[express] {} 取件回填运单号 sub={} no={}", p.getPickupNo(), sub.getSubOrderNo(), p.getTrackingNo());
        } catch (BizException e) {
            // 回填失败不能让回调整体失败：运费照记，运营看得到这张取件单，商家也能手填
            log.error("[express] {} 回填运单号失败 sub={}：{}", p.getPickupNo(), sub.getSubOrderNo(), e.getMessage());
        }
    }

    /**
     * 运费记商家欠款。按「累计运费」记差额：改重之后运费变高，补记差价；变低不冲回（欠款只增不减，
     * 多记的由运营在欠款页处理）。来源号带上累计额，重复推送同一个数不会多记。
     */
    private void bookFreight(OrdExpressPickup p) {
        if (Boolean.TRUE.equals(p.getSandbox())) {
            // 测试环境的运费是假的，平台也没付这笔钱 —— 记进商家欠款就是凭空让他欠一笔（§7 AC11）
            return;
        }
        long freight = p.getFreightMinor() == null ? 0L : p.getFreightMinor();
        long booked = p.getFreightBookedMinor() == null ? 0L : p.getFreightBookedMinor();
        if (freight <= booked) {
            if (freight < booked) {
                log.warn("[express] {} 运费从 {} 降到 {} 分，已记欠款不冲回", p.getPickupNo(), booked, freight);
            }
            return;
        }
        String reason = "快递运费 · " + ExpressCompanies.nameOf(p.getCarrier())
                + (p.getTrackingNo() == null ? "" : " " + p.getTrackingNo());
        debtPort.incur(p.getEntityNo(), freight - booked, MerchantDebtPort.SOURCE_EXPRESS,
                p.getPickupNo() + "#" + freight, reason);
        p.setFreightBookedMinor(freight);
    }

    // ── 校验与取数 ───────────────────────────────────────────────────────

    private boolean testMode() {
        return switchPort.bool(PayModes.EXPRESS_TEST_MODE_FLAG, false);
    }

    private void requireChannel() {
        if (!port.enabled()) {
            throw BizException.of(ErrorCode.EXPRESS_CHANNEL_OFF);
        }
    }

    private OrdSubOrder requireOwned(String merchantNo, String storeNo, String subOrderNo) {
        OrdSubOrder sub = DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo)
                        .eq(OrdSubOrder::getEntityNo, merchantNo)
                        .eq(storeNo != null && !storeNo.isBlank(), OrdSubOrder::getStoreNo, storeNo)
                        .last("limit 1")));
        if (sub == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return sub;
    }

    /** 快递单、已付款待发货。其它状态叫快递没有意义（没付钱 / 已发出 / 已取消） */
    private OrdSubOrder requireShippable(String merchantNo, String storeNo, String subOrderNo) {
        OrdSubOrder sub = requireOwned(merchantNo, storeNo, subOrderNo);
        if (!OrdSubOrder.EXPRESS.equals(sub.getFulfillment())
                || !OrdSubOrder.WAIT_FULFILL.equals(sub.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        return sub;
    }

    private String senderStoreNo(OrdSubOrder sub) {
        if (sub.getStoreNo() != null && !sub.getStoreNo().isBlank()) {
            return sub.getStoreNo();
        }
        return merchantPort.defaultStoreNo(sub.getEntityNo()).orElse(null);
    }

    private ExpressPickupPort.Party sender(OrdSubOrder sub) {
        MerchantQueryPort.StoreSender s = merchantPort.storeSender(sub.getEntityNo(), senderStoreNo(sub))
                .orElseThrow(() -> BizException.of(ErrorCode.EXPRESS_SENDER_INCOMPLETE));
        if (isBlank(s.address()) || isBlank(s.mobile())) {
            throw BizException.of(ErrorCode.EXPRESS_SENDER_INCOMPLETE);
        }
        String name = isBlank(s.name()) ? nz(sub.getEntityName()) : s.name();
        return new ExpressPickupPort.Party(name, s.mobile(), s.address());
    }

    private static ExpressPickupPort.Party receiver(OrdSubOrder sub) {
        if (isBlank(sub.getReceiverName()) || isBlank(sub.getReceiverPhone()) || isBlank(sub.getReceiverAddress())) {
            // 快递单下单时取不到收件地址是允许的（见 OrderServiceImpl 的收件人快照注释），到这一步就叫不了快递
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return new ExpressPickupPort.Party(sub.getReceiverName(), sub.getReceiverPhone(), sub.getReceiverAddress());
    }

    private String cargo(OrdSubOrder sub) {
        OrdItem first = DataScopeContext.executeWithoutScope(() ->
                itemMapper.selectOne(Wrappers.<OrdItem>lambdaQuery()
                        .eq(OrdItem::getSubOrderNo, sub.getSubOrderNo())
                        .orderByAsc(OrdItem::getId)
                        .last("limit 1")));
        String title = first == null || isBlank(first.getTitle()) ? "日用品" : first.getTitle().trim();
        return truncate(title, CARGO_MAX);
    }

    private Optional<OrdExpressPickup> open(String subOrderNo) {
        return Optional.ofNullable(DataScopeContext.executeWithoutScope(() ->
                pickupMapper.selectOne(Wrappers.<OrdExpressPickup>lambdaQuery()
                        .eq(OrdExpressPickup::getSubOrderNo, subOrderNo)
                        .in(OrdExpressPickup::getStatus, OrdExpressPickup.OPEN)
                        .orderByDesc(OrdExpressPickup::getId)
                        .last("limit 1"))));
    }

    static int grams(BigDecimal kg) {
        if (kg == null) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        int g = kg.movePointRight(3).setScale(0, RoundingMode.HALF_UP).intValue();
        if (g < MIN_WEIGHT_G || g > MAX_WEIGHT_G) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        return g;
    }

    private void save(OrdExpressPickup p) {
        stamp(p, false);
        DataScopeContext.executeWithoutScope(() -> pickupMapper.updateById(p));
    }

    private static void stamp(OrdExpressPickup p, boolean isNew) {
        LocalDateTime now = LocalDateTime.now();
        if (isNew) {
            p.setTenantNo("MAIN");
            p.setCreatedAt(now);
            p.setCreatedBy("MERCHANT");
            p.setVersion(0L);
            p.setDeleted(0);
        }
        p.setUpdatedAt(now);
        p.setUpdatedBy(isNew ? "MERCHANT" : "EXPRESS");
    }

    private static PickupVO toVO(OrdExpressPickup p) {
        long created = p.getCreatedAt() == null ? 0L
                : p.getCreatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        return new PickupVO(p.getPickupNo(), p.getCarrier(), ExpressCompanies.nameOf(p.getCarrier()),
                p.getStatus(), p.getTrackingNo(), Objects.requireNonNullElse(p.getWeightG(), 0),
                p.getChargedWeightG(), p.getFreightMinor(), p.getCourierName(), p.getCourierMobile(),
                p.getFailReason(), created, Boolean.TRUE.equals(p.getSandbox()));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
