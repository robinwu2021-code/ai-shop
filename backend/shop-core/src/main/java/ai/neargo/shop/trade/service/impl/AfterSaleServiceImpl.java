package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.promotion.service.CoreMarketingDocumentNumbers;

import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import ai.neargo.shop.trade.order.refund.service.PointsRefundResourceService;
import ai.neargo.shop.trade.order.refund.service.BasicRefundPolicy;
import ai.neargo.shop.trade.order.refund.service.RefundableQuery;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;
import ai.neargo.shop.trade.order.refund.service.RefundExecutionService;
import ai.neargo.shop.trade.order.refund.policy.LineShares;
import ai.neargo.shop.trade.order.refund.model.RefundProgress;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.LinkedHashSet;

import ai.neargo.shop.trade.service.AfterSaleRuleService;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleContext;
import ai.neargo.shop.trade.order.machine.aftersale.AfterSaleEvent;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.policy.AfterSaleRules;
import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.order.policy.FulfillStage;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.AfterCommit;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.trade.dto.AfterSaleVO;
import ai.neargo.shop.trade.dto.OpsAfterSaleVO;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.refund.port.AfterSaleLineStore;
import ai.neargo.shop.trade.order.mapper.AfterSaleMapper;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.OrderStatusLogMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 售后。
 *
 * <p><b>本类只有一条真正重要的规则</b>：{@link #doRefund} 里，
 * 「回退分账」永远在「退款」之前，且回退失败就**停在 REFUNDING 等重试**，绝不继续退款。
 * 钱可以晚退给用户（他会催），但不能退了之后收不回分账（没人会还）。
 */
@Service
public class AfterSaleServiceImpl implements AfterSaleService {
    private CoreMarketingDocumentNumbers documentNumbers;
    @org.springframework.beans.factory.annotation.Autowired
    public void setDocumentNumbers(CoreMarketingDocumentNumbers documentNumbers) {
        this.documentNumbers = documentNumbers;
    }

    private final ai.neargo.shop.trade.order.refund.service.RefundObligationService refundObligations;
    private final PointsRefundResourceService pointsResources;

    /** 不用 @Slf4j：下面 appendLog 里有局部变量叫 {@code log}（OrdStatusLog），会撞名。 */
    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(AfterSaleServiceImpl.class);

    /** 三种售后类型。与 {@link OrdAfterSale} 的常量同源，不在这里另写字符串 */
    private static final java.util.Set<String> AFTER_SALE_TYPES = java.util.Set.of(
            OrdAfterSale.REFUND_ONLY, OrdAfterSale.RETURN_REFUND, OrdAfterSale.EXCHANGE);


    /*
     * 售后原因已收编到 {@link OrdAfterSale#REASONS}（与它的 status / type 两组取值域同处），
     * 并登记进按字段对账（`ord_after_sale.reason`）。这里不再另存一份 ——
     * 「下发的是码不是文案」「两侧曾经漂过」的来龙去脉写在实体那边。
     */

    private final AfterSaleMapper afterSaleMapper;
    private final StoreOrderMapper subOrderMapper;
    private final OrderStatusLogMapper statusLogMapper;
    private final SettlePort settlePort;
    private final OutboxEventBus eventBus;
    private final ObjectMapper json;
    /** 仲裁台要看「谁的店、谁买的」——消费者自己的售后单不需要这两个 Port */
    private final MerchantQueryPort merchantPort;
    private final UserQueryPort userPort;
    private final StockPort stockPort;
    private final OrderItemMapper orderItemMapper;
    private AfterSaleLineStore afterSaleLines;
    /**
     * 极速退的门槛与各环节时效（§3）。
     *
     * <p>此前这里读的是本类自己的 {@code @Value} 默认值，而运营端那一屏
     * （{@code aftersale.fast-refund-rule}）写进参数表之后**没有任何代码读** ——
     * 运营改阈值、关开关、设「下单 N 小时内」，三样都是白配。
     */
    private final AfterSaleRuleService ruleService;
    /** 申请：售后期守卫 + 按行出方案（第 5 步） */
    private final ai.neargo.shop.trade.order.refund.service.AfterSaleRequestPlanner requestPlanner;
    /** Source plans reserve capacity before the durable worker starts any money operation. */
    private final ai.neargo.shop.trade.order.refund.service.AfterSaleRefundExecutor refundExecutor;
    private final RefundExecutionService refundExecutions;
    private final ai.neargo.shop.trade.order.mapper.OrderPointsRefundRetryMapper retryRows;
    private final ai.neargo.shop.trade.order.mapper.OrderPointsBindingMapper pointsBindings;
    private final TransactionTemplate refundTransactions;
    private ai.neargo.shop.spi.points.PointsPaymentSnapshotPort pointsPayments;
    @Autowired(required=false)
    private ai.neargo.shop.trade.order.points.OrderPointsRefundRouting pointsRefundRouting;
    private ai.neargo.shop.spi.points.PointsRefundPort pointsRefundPort(String afterSaleNo) {
        return pointsRefundRouting==null?pointsRefunds:pointsRefundRouting.route(afterSaleNo,pointsRefunds);
    }
    private ai.neargo.shop.spi.points.PointsRefundPort pointsRefunds;
    private ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator pointsRefundSource;
    private ai.neargo.shop.trade.order.points.OrderPointsOutboxConsumer pointsSourceDelivery;
    private ai.neargo.shop.trade.order.points.OrderPointsClosureFlowService pointsClosure;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPointsRefundIntegration(ai.neargo.shop.spi.points.PointsPaymentSnapshotPort payments,
            ai.neargo.shop.spi.points.PointsRefundPort refunds,
            ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator source,
            ai.neargo.shop.trade.order.points.OrderPointsOutboxConsumer delivery) {
        this.pointsPayments=payments;
        this.pointsRefunds=refunds;
        this.pointsRefundSource=source;
        this.pointsSourceDelivery=delivery;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPointsClosure(ai.neargo.shop.trade.order.points.OrderPointsClosureFlowService closure) {
        this.pointsClosure = closure;
    }
    /** 订单状态经门（CAS + 流水） */
    private final ai.neargo.shop.trade.order.transition.TradeStatusWriter statusWriter;
    /** 一张订单同时只有一张进行中售后（V406 库内 CAS 占位） */
    private final ai.neargo.shop.trade.order.aftersale.AfterSaleSlots slots;
    /**
     * 整单退款时把券退回券包（执行计划 B6）。
     * **setter 注入**：切片测试里没有它时，退款其余行为一字不差。
     */
    private ai.neargo.shop.spi.marketing.CouponPort couponPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCouponPort(ai.neargo.shop.spi.marketing.CouponPort couponPort) {
        this.couponPort = couponPort;
    }

    /** 退券 / 退积分 / 收回赠送积分各一个开关（运营端功能开关，默认开） */
    private ai.neargo.shop.spi.platform.PlatformSwitchPort switchPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSwitchPort(ai.neargo.shop.spi.platform.PlatformSwitchPort switchPort) {
        this.switchPort = switchPort;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setAfterSaleLines(AfterSaleLineStore afterSaleLines) { this.afterSaleLines = afterSaleLines; }

    static final String FLAG_RETURN_COUPON = "refund.return-coupon";

    /** 开关取不到按默认开 —— 这几条是本次拍板的口径，关掉才是例外 */
    private boolean on(String flag) {
        return switchPort == null || switchPort.bool(flag, true);
    }

    public AfterSaleServiceImpl(AfterSaleMapper afterSaleMapper, StoreOrderMapper subOrderMapper,
                                OrderStatusLogMapper statusLogMapper, SettlePort settlePort,
                                OutboxEventBus eventBus, ObjectMapper json,
                                MerchantQueryPort merchantPort, UserQueryPort userPort,
                                StockPort stockPort, OrderItemMapper orderItemMapper,
                                AfterSaleRuleService ruleService,
                                ai.neargo.shop.trade.order.refund.service.AfterSaleRequestPlanner requestPlanner,
                                ai.neargo.shop.trade.order.refund.service.AfterSaleRefundExecutor refundExecutor,
                                ai.neargo.shop.trade.order.transition.TradeStatusWriter statusWriter,
                                ai.neargo.shop.trade.order.aftersale.AfterSaleSlots slots,
                                RefundExecutionService refundExecutions,
                                ai.neargo.shop.trade.order.mapper.OrderPointsRefundRetryMapper retryRows,
                                ai.neargo.shop.trade.order.mapper.OrderPointsBindingMapper pointsBindings,
                                @Qualifier("transactionManager") PlatformTransactionManager sourceTransactions,
                                ai.neargo.shop.trade.order.refund.service.RefundObligationService refundObligations,
                                PointsRefundResourceService pointsResources) {
        this.pointsResources=pointsResources;
        this.refundObligations=refundObligations;
        this.refundExecutions = refundExecutions;
        this.retryRows = retryRows;
        this.pointsBindings = pointsBindings;
        this.refundTransactions = new TransactionTemplate(sourceTransactions);
        this.refundTransactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.slots = slots;
        this.requestPlanner = requestPlanner;
        this.refundExecutor = refundExecutor;
        this.statusWriter = statusWriter;
        this.ruleService = ruleService;
        this.afterSaleMapper = afterSaleMapper;
        this.subOrderMapper = subOrderMapper;
        this.statusLogMapper = statusLogMapper;
        this.settlePort = settlePort;
        this.eventBus = eventBus;
        this.json = json;
        this.merchantPort = merchantPort;
        this.userPort = userPort;
        this.stockPort = stockPort;
        this.orderItemMapper = orderItemMapper;
    }

    private BasicRefundExecutionService basicRefunds;
    private RefundableQuery refundQuery;

    @Autowired
    public void setBasicRefundExecution(BasicRefundExecutionService basicRefunds, RefundableQuery refundQuery) {
        this.basicRefunds = basicRefunds;
        this.refundQuery = refundQuery;
    }

    @Override
    public List<String> reasons() {
        return OrdAfterSale.REASONS;
    }

    /**
     * 读运单判履约进度用。setter 注入（{@code required=false}）：物流模块缺席时为 null，
     * 手工构造本类的测试也不必跟着改（同 {@code MerchantOrderServiceImpl}）。
     */
    private ai.neargo.shop.spi.logistics.LogisticsPort logisticsPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setLogisticsPort(ai.neargo.shop.spi.logistics.LogisticsPort port) {
        this.logisticsPort = port;
    }

    /**
     * 这单的履约进度（货还拦不拦得住）。快递单才查运单；运单状态与建单时刻来自物流域，
     * 其余按子单状态 + 备货时刻判（{@link FulfillStage}）。
     */
    private FulfillStage stageOf(OrdSubOrder sub) {
        return stageOf(sub, false);
    }

    private FulfillStage stageOf(OrdSubOrder sub, boolean refresh) {
        String waybillStatus = null;
        Long waybillCreatedAt = null;
        boolean recordedWaybill = OrdSubOrder.EXPRESS.equals(sub.getFulfillment())
                && sub.getExpressNo() != null && !sub.getExpressNo().isBlank();
        if (recordedWaybill) {
            var tv = logisticsPort == null ? null : logisticsPort.track(
                    ai.neargo.shop.spi.logistics.LogisticsPort.TrackQuery.subOrder(
                            sub.getSubOrderNo(), "BIZ", refresh)).orElse(null);
            // An order-side waybill without a current logistics binding is not proof that goods stayed in store.
            if (tv == null || tv.status() == null || tv.status().isBlank()) return FulfillStage.SHIPPED;
            waybillStatus = tv.status();
            waybillCreatedAt = tv.createdAt();
        }
        return FulfillStage.of(sub.getStatus(), sub.getPreparedAt(),
                waybillStatus, waybillCreatedAt, System.currentTimeMillis());
    }

    /** 这单是不是生鲜（任一行品类为 FRESH）。生鲜发货后非质量退款要受限。 */
    private boolean isFreshSub(String subOrderNo) {
        List<OrdItem> items = DataScopeContext.executeWithoutScope(() ->
                orderItemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                        .eq(OrdItem::getSubOrderNo, subOrderNo)));
        return items.stream().anyMatch(
                it -> ai.neargo.shop.product.entity.PrdGoods.TYPE_FRESH.equals(it.getCategoryType()));
    }

    // ---------------------------------------------------------------- C 端

    @Override
    public AfterSaleVO apply(String subOrderNo, ApplyCommand cmd) {
        return applyForBuyer(subOrderNo, SecurityUtils.currentUserNo(), cmd);
    }

    @Override
    public AfterSaleVO applyForBuyer(String subOrderNo, String buyerNo, ApplyCommand cmd) {
        return applyForBuyer(subOrderNo, buyerNo, cmd, null);
    }

    @Override
    public AfterSaleVO applyForBuyer(String subOrderNo, String buyerNo, ApplyCommand cmd, SourceRequest source) {
        requireBuyer(buyerNo);
        Supplier<AfterSaleVO> action=() -> documentNumbers.withReservation(
                Map.of(DocumentType.AFTER_SALE,1),
                () -> refundTransactions.execute(status -> applyNumbered(subOrderNo,buyerNo,cmd,source)));
        return source==null ? action.get() : AfterCommit.withDeferredActions(action);
    }

    private AfterSaleVO applyNumbered(String subOrderNo, String buyerNo, ApplyCommand cmd, SourceRequest source) {
        // 售后定位日志（2026-10-11 店主反馈退款"数据不存在"）：先把"谁在退哪一单、带没带图"记下来
        LOG.info("[after-sale] apply 进入 subOrderNo={} currentUser={} type={} hasReason={} imageCount={}",
                subOrderNo, buyerNo,
                cmd == null ? null : cmd.type(),
                cmd != null && cmd.reason() != null && !cmd.reason().isBlank(),
                cmd == null || cmd.images() == null ? 0 : cmd.images().size());
        OrdSubOrder sub = ownSubOrder(subOrderNo, buyerNo);
        if (source != null) {
            if (source.sourceSystem() == null || source.sourceSystem().isBlank() || source.sourceSystem().length() > 32
                    || source.requestKey() == null || !source.requestKey().matches("[a-f0-9]{64}")
                    || source.requestHash() == null || !source.requestHash().matches("[a-f0-9]{64}"))
                throw BizException.of(ErrorCode.BAD_REQUEST);
            // Recheck under the same original child lock before active-case and mutable policy checks.
            OrdAfterSale prior = DataScopeContext.executeWithoutScope(() -> afterSaleMapper.selectOne(
                    Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getSourceSystem, source.sourceSystem())
                            .eq(OrdAfterSale::getSourceRequestKey, source.requestKey())));
            if (prior != null) {
                if (!Objects.equals(prior.getSourceRequestHash(), source.requestHash())
                        || !Objects.equals(prior.getSubOrderNo(), subOrderNo)
                        || !Objects.equals(prior.getUserNo(), buyerNo)) throw BizException.of(ErrorCode.CONFLICT);
                return detailOf(prior);
            }
        }

        /*
         * 售后类型与原因都是**必填**，在入口就挡住。
         *
         * 不挡的后果是实测出来的：type 为空时一路走到 insert，
         * 库上 `type VARCHAR(16) NOT NULL` 抛「Field 'type' doesn't have a default value」，
         * 被包成通用 500「系统开小差了」—— 而真正的问题是少传了一个字段。
         * 与「未过审商品上架报订单状态错误」同一形状：**报错与实际问题无关**，
         * 排查的人会去看服务器日志，而答案本该在响应里。
         *
         * H2 上这条不会红（测试库对 NOT NULL 的空串处理不同），所以四层测试全绿。
         */
        if (cmd == null || cmd.reason() == null || cmd.reason().isBlank()
                || cmd.type() == null || !AFTER_SALE_TYPES.contains(cmd.type())
                || OrdAfterSale.EXCHANGE.equals(cmd.type())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        // 带了码就必须是认得的码：认不得时回落到 reason 会让规则判错方向，且无从察觉
        if (cmd.reasonCode() != null && !cmd.reasonCode().isBlank()
                && !OrdAfterSale.REASONS.contains(cmd.reasonCode())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        // 未支付的单没有可退的钱 —— 直接取消订单即可，不该走售后
        if (OrdSubOrder.WAIT_PAY.equals(sub.getStatus()) || OrdSubOrder.CANCELLED.equals(sub.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        // 同一子单同时只能有一个进行中的售后。用应用层判定而不是唯一索引：
        // 唯一索引做不到「只对进行中的行唯一」，而终态之后必须允许再申请
        if (activeOf(subOrderNo) != null) {
            throw BizException.of(ErrorCode.CONFLICT);
        }

        /*
         * 分档校验（TDD-售后规则 §2.2）：能不能退取决于**履约进度**（货还拦不拦得住），
         * 不是订单状态。生鲜已揽收/已签收 + 非质量原因 → 拒；备货中/已出单未揽收是灰色地带，
         * 可建单但要商家确认（needMerchant，归 approve 那步处理）。进度快照落库留痕。
         */
        FulfillStage stage = stageOf(sub);
        // 行锁由 ownSubOrder 持有：与商家「开始备货」写入串行，不能用两次无锁查询做决定。
        boolean beforePreparation = OrdSubOrder.WAIT_FULFILL.equals(sub.getStatus())
                && sub.getPreparedAt() == null
                && (sub.getExpressNo() == null || sub.getExpressNo().isBlank())
                && stage == FulfillStage.NOT_PREPARED;
        boolean fresh = isFreshSub(subOrderNo);
        AfterSaleRules.Gate gate = AfterSaleRules.applyGate(stage, fresh, cmd.ruleReason());
        if (!gate.allow()) {
            throw BizException.of(ErrorCode.AFTERSALE_FRESH_QUALITY_ONLY);
        }
        /*
         * 退什么、退多少（第 5 步）：售后期守卫 → 按行 × 件数出方案（不传行 = 整单剩余）→ 只给金额就按额分回
         * → 渠道不支持部分退则拒。上限是剩余可退额，不再是实付（第二张售后只能退剩下的）。
         */
        RefundPlan plan = requestPlanner.planOriginal(sub, refundQuery.lockItems(sub),
                cmd.items() == null ? null : cmd.items().stream()
                        .map(i -> new RefundLine(i.itemId(), i.qty(), 0L)).toList(),
                cmd.refundMinor(), cmd.ruleReason(), System.currentTimeMillis());
        long refund = plan.totalMinor();
        if (refund != sub.getPayAmount() || (sub.getRefundedMinor() != null && sub.getRefundedMinor() > 0)) {
            if (source == null || pointsIntegrated(sub) || (couponPort != null && couponPort.usedTitleOf(sub.getOrderNo()) != null))
                throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
            var latest = latestOf(subOrderNo);
            if (latest.isPresent()) {
                var previous = basicRefunds.find(latest.get().getAfterSaleNo());
                if (previous != null && (!OrdRefundExecution.DONE.equals(previous.getResourceStatus())
                        || (Integer.valueOf(2).equals(previous.getClosureVersion()) && !"DONE".equals(previous.getClosureStatus()))))
                    throw BizException.of(ErrorCode.CONFLICT);
            }
        }

        OrdAfterSale as = new OrdAfterSale();
        as.setAfterSaleNo(BizKey.next(BizKey.AFTER_SALE));
        if (source != null) {
            as.setSourceSystem(source.sourceSystem());
            as.setSourceRequestKey(source.requestKey());
            as.setSourceRequestHash(source.requestHash());
        }
        as.setSubOrderNo(subOrderNo);
        as.setOrderNo(sub.getOrderNo());
        as.setUserNo(sub.getUserNo());
        as.setEntityNo(sub.getEntityNo());
        // 货尚未动时无需买家寄回；即便端上误选「退货退款」，也按仅退款原路处理。
        as.setType(beforePreparation ? OrdAfterSale.REFUND_ONLY : cmd.type());
        as.setReason(cmd.reason());
        as.setImages(writeJson(cmd.images()));
        as.setRefundMinor(refund);
        /*
         * **自营单的售后直接进平台仲裁，不派给商家。**
         *
         * 归集路径下平台是法律上的销售主体 —— 合同相对方是平台、票是平台开的、
         * 钱在平台账户。ADR-017 §3.4 条件 3 写得很直白：
         * <b>平台对消费者承担商品与售后责任，再向商家追偿</b>。
         * 做不到这一条，「自营」就不成立，整条资金链退回第三方模式。
         *
         * 而此前自营单同样派给「商家」—— 而那个商家就是平台自己：
         * 消费者申请退款 → 等平台自己审 → 驳回后再升级给平台仲裁。
         * 一条本该一步的路走了两段，中间那段还是平台审自己。
         *
         * 判据用 funds_mode 而不是门店的 business_mode：**责任跟着钱走** ——
         * 钱在谁账户，谁就是那个要先赔的人。
         */
        boolean platformIsSeller = MerchantQueryPort.FUNDS_AGGREGATED
                .equals(merchantPort.fundsModeOf(sub.getEntityNo()));
        as.setStatus(beforePreparation ? OrdAfterSale.APPLIED
                : platformIsSeller ? OrdAfterSale.ARBITRATING : OrdAfterSale.APPLIED);
        as.setSplitReversed(false);
        // 申请当时的履约进度快照（审核、灰色地带判定、留痕都读它）
        as.setFulfillStage(stage.name());

        // 不是按金额/时限的旧「极速退」；仅凭尚未备货的履约事实决定免审核。
        as.setInstant(false);
        // 库内占位：并发两次申请只一张成功（上面的 activeOf 是先查后插，挡不住两个实例同时到）
        slots.occupy(subOrderNo, as.getAfterSaleNo());
        documentNumbers.bind(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", as, as.getAfterSaleNo(), afterSaleMapper::insert, OrdAfterSale::setDocumentNo);
        requestPlanner.save(as.getAfterSaleNo(), plan);
        // 日志要写**真实去向**：自营单没有「等商家处理」这一步，
        // 写成一样的话，用户看时间线会以为在等商家，而实际在等平台
        appendLog(subOrderNo, as.getStatus(),
                beforePreparation ? "未备货，申请后直接发起原路退款"
                        : platformIsSeller ? "已申请售后，由平台直接处理" : "已申请售后",
                OrdStatusLog.BY_USER, sub.getUserNo());

        if (beforePreparation) {
            doRefund(as, "未备货，买家申请原路退款", AfterSaleEvent.PREPARATION_NOT_STARTED_REFUND,
                    Actor.BUYER, sub.getUserNo());
        } else {
            // 只有真正需要商家处理的单才发「售后待处理」；自动退款走退款结果通知。
            eventBus.publish(new OrderEvents.AfterSaleApplied(as.getAfterSaleNo(), subOrderNo,
                    sub.getEntityNo(), sub.getStoreNo(), sub.getUserNo(), cmd.type(), refund));
        }

        return detailOf(as);
    }

    @Override
    public java.util.Optional<AfterSaleVO> systemRefund(String subOrderNo, String reason, String label) {
        return documentNumbers.withReservation(java.util.Map.of(DocumentType.AFTER_SALE, 1), () -> refundTransactions.execute(status -> systemRefundNumbered(subOrderNo, reason, label)));
    }

    private java.util.Optional<AfterSaleVO> systemRefundNumbered(String subOrderNo, String reason, String label) {
        lockRefundChild(subOrderNo);
        OrdSubOrder sub = subOrderOf(subOrderNo);
        if (sub == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 没付过钱的单无钱可退：调用方该走关单 / 取消，不是售后
        if (OrdSubOrder.WAIT_PAY.equals(sub.getStatus()) || OrdSubOrder.CANCELLED.equals(sub.getStatus())) {
            return java.util.Optional.empty();
        }
        OrdAfterSale active = activeOf(subOrderNo);
        if (active != null) {
            // 已有一张在路上：退款中的交给重试任务，买家自己的交给它自己的流程。不建第二张
            return java.util.Optional.of(detailOf(active));
        }
        if (OrdSubOrder.REFUNDED.equals(sub.getStatus())) {
            return latestOf(subOrderNo).map(this::detailOf);
        }

        OrdAfterSale as = new OrdAfterSale();
        as.setAfterSaleNo(BizKey.next(BizKey.AFTER_SALE));
        as.setSubOrderNo(subOrderNo);
        as.setOrderNo(sub.getOrderNo());
        as.setUserNo(sub.getUserNo());
        as.setEntityNo(sub.getEntityNo());
        as.setType(OrdAfterSale.REFUND_ONLY);
        as.setRefundMode(OrdAfterSale.REFUND_MODE_CASH);
        as.setReason(reason);
        as.setImages(writeJson(null));
        as.setRefundMinor(sub.getPayAmount() == null ? 0L : sub.getPayAmount());
        /*
         * 交易不成立（A3 TRADE_VOID）：系统唯一能退钱的入口，**建单即「退款中」**。
         * 不能先建 APPLIED 再「同意」—— 同意是商家的决定，系统走那条边就违反了「退款永不自动」（不变式 I1）。
         */
        as.setStatus(OrdAfterSale.REFUNDING);
        as.setSplitReversed(false);
        as.setInstant(false);
        if (!slots.tryOccupy(subOrderNo, as.getAfterSaleNo())) {
            // 另一个实例刚建了一张：交给它，不建第二张（与上面「已有一张在路上」同一个口径）
            return java.util.Optional.ofNullable(activeOf(subOrderNo)).map(this::detailOf);
        }
        DataScopeContext.executeWithoutScope(() -> documentNumbers.bind(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", as, as.getAfterSaleNo(), afterSaleMapper::insert, OrdAfterSale::setDocumentNo));
        appendLog(subOrderNo, OrdAfterSale.REFUNDING, label, OrdStatusLog.BY_SYSTEM, null);
        doRefund(as, label, AfterSaleEvent.TRADE_VOID, Actor.SYSTEM, null);
        return java.util.Optional.of(detailOf(as));
    }

    private java.util.Optional<OrdAfterSale> latestOf(String subOrderNo) {
        return java.util.Optional.ofNullable(DataScopeContext.executeWithoutScope(() ->
                documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getSubOrderNo, subOrderNo)
                        .orderByDesc(OrdAfterSale::getId).last("limit 1")), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo)));
    }

    @Override
    public List<AfterSaleVO> myList() {
        return documentNumbers.load(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectList(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getUserNo, SecurityUtils.currentUserNo())
                        .orderByDesc(OrdAfterSale::getId)), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo).stream()
                .map(this::detailOf).toList();
    }

    @Override
    public AfterSaleVO detail(String afterSaleNo) {
        return detailForBuyer(afterSaleNo, SecurityUtils.currentUserNo());
    }

    @Override
    public AfterSaleVO detailForBuyer(String afterSaleNo, String buyerNo) {
        return detailOf(own(afterSaleNo, buyerNo));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO cancel(String afterSaleNo) {
        return cancelForBuyer(afterSaleNo, SecurityUtils.currentUserNo());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO cancelForBuyer(String afterSaleNo, String buyerNo) {
        OrdAfterSale hint = own(afterSaleNo, buyerNo);
        lockRefundChild(hint.getSubOrderNo());
        OrdAfterSale as = own(afterSaleNo, buyerNo);
        // 状态机判：买家只能在待处理 / 待寄回 / 被驳回时撤销 —— 钱已在路上、平台已介入都不能撤
        transit(as, AfterSaleEvent.WITHDRAW, Actor.BUYER, as.getUserNo(), "用户撤销申请",
                AfterSaleContext.builder().build());
        return detailOf(as);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO shipBack(String afterSaleNo, String company, String expressNo) {
        OrdAfterSale as = ownForMutation(afterSaleNo);
        // 只在「待寄回」时能填：此前不校验状态，终态单也能写入，且每次写都刷新 updated_at 把超时计时重置
        boolean waybill = expressNo != null && !expressNo.isBlank();
        if (waybill) {
            as.setExpressCompany(company);
            as.setExpressNo(expressNo.trim());
        }
        transit(as, AfterSaleEvent.RETURN_SHIPPED, Actor.BUYER, as.getUserNo(),
                "买家已寄回：" + (waybill ? expressNo.trim() : ""),
                AfterSaleContext.builder().waybillValid(waybill).build());
        return detailOf(as);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO escalate(String afterSaleNo, String appeal) {
        OrdAfterSale as = ownForMutation(afterSaleNo);
        // 入口：被驳回 / 在途待签收 / 商家超时未处理 / 退货寄回后商家超时未确认（TDD-订单状态机 AC10）
        boolean open = escalationOpen(as);
        // 申诉理由此前只拼进了日志文案，实体字段 dispute_reason 从未写入
        as.setDisputeReason(appeal == null ? null : appeal.trim());
        transit(as, AfterSaleEvent.ESCALATE, Actor.BUYER, as.getUserNo(), "已申请平台介入：" + appeal,
                AfterSaleContext.builder().escalationOpen(open).build());
        return detailOf(as);
    }

    // ---------------------------------------------------------------- B 端

    @Override
    public List<AfterSaleVO> merchantList(String merchantNo, String status) {
        var w = Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getEntityNo, merchantNo);
        if (status != null && !status.isBlank()) {
            w.eq(OrdAfterSale::getStatus, status);
        }
        w.orderByDesc(OrdAfterSale::getId);
        return DataScopeContext.executeWithoutScope(() -> documentNumbers.load(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectList(w), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo))
                .stream().map(as -> {
                    AfterSaleVO vo = detailOf(as);
                    // 待处理的才算「同意之后会退回什么」—— 已经处理完的，说了也改变不了什么
                    return OrdAfterSale.APPLIED.equals(as.getStatus())
                            || OrdAfterSale.WAIT_RETURN.equals(as.getStatus())
                            || OrdAfterSale.RETURNING.equals(as.getStatus())
                            ? vo.withImpact(impactOf(as)) : vo;
                }).toList();
    }

    @Override
    public ai.neargo.shop.common.PageData<AfterSaleVO> merchantPage(String merchantNo, String storeNo,
                                                                     boolean pending, long page, long size) {
        if (storeNo == null || storeNo.isBlank()) {
            return ai.neargo.shop.common.PageData.empty(page, size);
        }
        var query = Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getEntityNo, merchantNo)
                .apply("EXISTS (SELECT 1 FROM ord_sub_order so WHERE so.sub_order_no = ord_after_sale.sub_order_no "
                        + "AND so.store_no = {0} AND so.deleted = 0)", storeNo);
        if (pending) {
            query.in(OrdAfterSale::getStatus, OrdAfterSale.APPLIED, OrdAfterSale.WAIT_RECEIPT,
                    OrdAfterSale.RETURNING);
        }
        query.orderByDesc(OrdAfterSale::getId);
        var rows = DataScopeContext.executeWithoutScope(() -> afterSaleMapper.selectPage(
                com.baomidou.mybatisplus.extension.plugins.pagination.Page.of(page, size), query));
        return ai.neargo.shop.common.PageData.of(documentNumbers.load(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", rows.getRecords(), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo).stream().map(as -> {
            AfterSaleVO vo = detailOf(as);
            return pending ? vo.withImpact(impactOf(as)) : vo;
        }).toList(), rows.getTotal(), page, size);
    }

    @Override
    public AfterSaleVO merchantDetail(String merchantNo, String storeNo, String afterSaleNo) {
        OrdAfterSale as = DataScopeContext.executeWithoutScope(() -> documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectOne(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)
                        .eq(OrdAfterSale::getEntityNo, merchantNo).last("limit 1")), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo));
        OrdSubOrder sub = as == null ? null : subOrderOf(as.getSubOrderNo());
        if (sub == null || storeNo == null || !storeNo.equals(sub.getStoreNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        AfterSaleVO vo = detailOf(as);
        return OrdAfterSale.APPLIED.equals(as.getStatus()) || OrdAfterSale.RETURNING.equals(as.getStatus())
                ? vo.withImpact(impactOf(as)) : vo;
    }

    @Override
    public AfterSaleVO merchantActiveForOrder(String merchantNo, String storeNo, String subOrderNo) {
        OrdSubOrder sub = subOrderOf(subOrderNo);
        if (sub == null || storeNo == null || !storeNo.equals(sub.getStoreNo())
                || !merchantNo.equals(sub.getEntityNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        OrdAfterSale as = DataScopeContext.executeWithoutScope(() -> documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectOne(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getSubOrderNo, subOrderNo)
                        .eq(OrdAfterSale::getEntityNo, merchantNo)
                        .notIn(OrdAfterSale::getStatus, OrdAfterSale.REFUNDED,
                                OrdAfterSale.REJECTED, OrdAfterSale.CLOSED)
                        .orderByDesc(OrdAfterSale::getId).last("limit 1")), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo));
        return as == null ? null : detailOf(as);
    }

    /**
     * 同意这一笔之后会一并退回什么（待办设计 P2c · B 端售后单）。
     *
     * <p>判据与真正退的那一刻（{@link #returnBenefitsIfWholeOrderRefunded}）同一条：
     * 这是主单下<b>最后一笔</b>还在履约中的子单才算整单退；开关关掉的那一项不说。
     * 券看它此刻是不是还占在这一单上。积分金额必须等新退款确认结果，
     * 审批前不从旧流水猜测可退或可收回数量。
     */
    private AfterSaleVO.RefundImpact impactOf(OrdAfterSale as) {
        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        if (sub == null || sub.getOrderNo() == null) {
            return null;
        }
        var siblings = DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectList(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getOrderNo, sub.getOrderNo())));
        boolean last = siblings.stream().filter(x -> !x.getSubOrderNo().equals(sub.getSubOrderNo()))
                .allMatch(x -> OrdSubOrder.REFUNDED.equals(x.getStatus())
                        || OrdSubOrder.CANCELLED.equals(x.getStatus()));
        if (!last) {
            return null;
        }
        String coupon = couponPort != null && on(FLAG_RETURN_COUPON)
                ? couponPort.usedTitleOf(sub.getOrderNo()) : null;

        return new AfterSaleVO.RefundImpact(coupon, 0, 0);

    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO approve(String merchantNo, String afterSaleNo, String remark,
                               Long refundMinor, String refundMode) {
        if (OrdAfterSale.REFUND_MODE_POINTS.equals(refundMode)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);

        if (refundMode != null && !OrdAfterSale.REFUND_MODE_CASH.equals(refundMode)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (OrdAfterSale.REFUNDING.equals(as.getStatus()) || OrdAfterSale.REFUNDED.equals(as.getStatus())) {
            long fixed = as.getApprovedMinor() == null ? as.getRefundMinor() : as.getApprovedMinor();
            if (refundMinor != null && refundMinor != fixed) { throw BizException.of(ErrorCode.CONFLICT); }
            return detailOf(as);
        }

        // 说明可空；给了就留下来 —— C 端订单页那句「商家回复」读的就是它
        if (remark != null && !remark.isBlank()) {
            as.setMerchantRemark(remark.trim());
        }

        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        FulfillStage stage = stageOf(sub, true);

        // 当前仅支持整单全退；商家不能缩减核定金额，积分返还仍由原支付协议处理。
        if (refundMinor != null) {
            if (!refundMinor.equals(as.getRefundMinor())) {
                throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
            }
            as.setApprovedMinor(refundMinor);
        }

        if (OrdAfterSale.RETURN_REFUND.equals(as.getType())) {
            // 退货退款：同意 ≠ 立刻退钱，要等收到货。这里只推进到「等待寄回」（V403 起独立状态 WAIT_RETURN）
            transit(as, AfterSaleEvent.APPROVE, Actor.MERCHANT, merchantNo, "商家已同意，待买家寄回",
                    AfterSaleContext.builder().returnRefund(true).build());
            /*
             * **待寄回这条有时限**：不寄会被 AfterSaleTimeoutJob 自动关单。
             * 不通知的话，买家是在毫不知情的情况下错过那个时限的。
             */
            eventBus.publish(new OrderEvents.AfterSaleDecided(
                    as.getAfterSaleNo(), as.getSubOrderNo(), as.getUserNo(),
                    OrderEvents.AfterSaleDecided.RETURN_WAIT, as.getMerchantRemark()));
            return detailOf(as);
        }
        /*
         * 仅退款（AC7/AC8）：同意 ≠ 立刻退钱。货还在路上（已揽收未签收 SHIPPED）就退钱，
         * 等于钱货两失 —— 此时转「待收货后退款」WAIT_RECEIPT，等买家签收（或包裹退回商家）再退。
         * 未发货（可拦）或已签收（到手）则立即退（TDD-售后规则 §2.3）。
         */
        if (OrdAfterSale.REFUND_ONLY.equals(as.getType())
                && (stage == FulfillStage.SHIPPED || stage == FulfillStage.LABELED)) {
            transit(as, AfterSaleEvent.APPROVE, Actor.MERCHANT, merchantNo,
                    stage == FulfillStage.LABELED
                            ? "商家已同意，已出单待核实货物仍在店或签收后确认退款"
                            : "商家已同意，货在途——签收后由商家确认退款",
                    AfterSaleContext.builder().inTransit(true).build());
            return detailOf(as);
        }
        doRefund(as, "商家同意退款", AfterSaleEvent.APPROVE, Actor.MERCHANT, merchantNo);
        return detailOf(as);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO reject(String merchantNo, String afterSaleNo, String remark) {
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);
        // 极速退已经是终态，商家只可见不可拒 —— 状态机在这里挡住
        as.setMerchantRemark(remark);
        // 驳回理由必填（状态机 REMARK 守卫）此前未生效；行为不变，留给端上收紧时一起开
        transit(as, AfterSaleEvent.REJECT, Actor.MERCHANT, merchantNo, "商家驳回：" + remark,
                AfterSaleContext.builder().build());
        eventBus.publish(new OrderEvents.AfterSaleDecided(
                as.getAfterSaleNo(), as.getSubOrderNo(), as.getUserNo(),
                OrderEvents.AfterSaleDecided.REJECTED, remark));
        return detailOf(as);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO confirmReturn(String merchantNo, String afterSaleNo) {
        return confirmReturn(merchantNo, afterSaleNo, false);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO confirmReturn(String merchantNo, String afterSaleNo, boolean restockApproved) {
        return confirmReturnInternal(merchantNo, afterSaleNo, null, null, restockApproved);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO confirmReturn(String merchantNo, String afterSaleNo, String receiptNo,
                                     List<AfterSaleService.ReturnReceiveLine> receivedLines) {
        return confirmReturnInternal(merchantNo, afterSaleNo, receiptNo, receivedLines, false);
    }

    private AfterSaleVO confirmReturnInternal(String merchantNo, String afterSaleNo, String receiptNo,
                                               List<AfterSaleService.ReturnReceiveLine> receivedLines,
                                               boolean legacyRestockApproved) {
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);
        if (!OrdAfterSale.RETURNING.equals(as.getStatus())) {
            // A retry after the platform transaction committed is a read, never a second receipt.
            if (as.getReturnReceivedAt() != null) return detailOf(as);
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        boolean lifecycleV2 = sub != null && Integer.valueOf(2).equals(sub.getInventoryLifecycleVersion());
        boolean restockApproved = legacyRestockApproved;
        if (lifecycleV2) {
            if (receiptNo == null || receiptNo.isBlank() || receivedLines == null || receivedLines.isEmpty()) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            List<StockPort.ReturnLine> accepted = acceptedReturnLines(as, receivedLines);
            restockApproved = accepted.stream().anyMatch(line -> line.quality() == StockPort.ReturnQuality.SALEABLE);
            // V2 physical receipt is durable independently of the refund callback/resource worker.
            stockPort.receiveReturn(receiptNo, as.getAfterSaleNo(), as.getOrderNo(), as.getSubOrderNo(), accepted);
        }
        if (as.getReturnReceivedAt() == null) {
            as.setReturnReceivedAt(System.currentTimeMillis());
            as.setRestockApproved(restockApproved);
        }
        doRefund(as, "商家已收到退货", AfterSaleEvent.RETURN_RECEIVED, Actor.MERCHANT, merchantNo);
        return detailOf(as);
    }

    private List<StockPort.ReturnLine> acceptedReturnLines(OrdAfterSale as,
            List<AfterSaleService.ReturnReceiveLine> receivedLines) {
        if (afterSaleLines == null) throw new IllegalStateException("AFTERSALE_LINES_UNAVAILABLE");
        List<RefundLine> planned = afterSaleLines.lines(as.getAfterSaleNo());
        java.util.Map<Long, Integer> limits = planned.stream().collect(java.util.stream.Collectors.toMap(
                RefundLine::itemId, RefundLine::qty, Math::addExact));
        java.util.Set<Long> seen = new java.util.HashSet<>();
        List<StockPort.ReturnLine> accepted = new java.util.ArrayList<>();
        int total = 0;
        for (AfterSaleService.ReturnReceiveLine line : receivedLines) {
            if (line == null || line.itemId() == null || line.receivedQty() == null || line.receivedQty() < 0
                    || !seen.add(line.itemId()) || !limits.containsKey(line.itemId())
                    || !Objects.equals(line.receivedQty(), limits.get(line.itemId()))) {
                // This release only supports full-child cash refunds. Do not refund the whole child
                // after accepting fewer physical units; partial cash allocation needs its own policy.
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            if (line.receivedQty() == 0) continue;
            StockPort.ReturnQuality quality;
            try {
                quality = StockPort.ReturnQuality.valueOf(line.quality());
            } catch (RuntimeException invalid) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            OrdItem item = orderItemMapper.selectById(line.itemId());
            if (item == null || !Objects.equals(item.getSubOrderNo(), as.getSubOrderNo())) {
                throw BizException.of(ErrorCode.BAD_REQUEST);
            }
            accepted.add(new StockPort.ReturnLine(item.getSkuNo(), line.receivedQty(), quality));
            total = Math.addExact(total, line.receivedQty());
        }
        if (total <= 0 || !seen.containsAll(limits.keySet())) throw BizException.of(ErrorCode.BAD_REQUEST);
        return List.copyOf(accepted);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO confirmRefund(String merchantNo, String afterSaleNo) {
        return confirmRefund(merchantNo, afterSaleNo, null);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO confirmRefund(String merchantNo, String afterSaleNo, String reason) {
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);
        if (reason != null && reason.length() > 200) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        FulfillStage stage = stageOf(sub, true);
        boolean needsException = stage == FulfillStage.SHIPPED || stage == FulfillStage.LABELED;
        if (needsException && (reason == null || reason.isBlank())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        String label = needsException ? "商家核实货物后例外确认退款：" + reason.trim() : "商家确认退款";
        if (needsException) as.setMerchantRemark(reason.trim());
        doRefund(as, label, AfterSaleEvent.CONFIRM_REFUND, Actor.MERCHANT, merchantNo);
        return detailOf(as);
    }

    /**
     * 签收触发「待商家确认退款」提醒：子单签收/完成时调用，提醒商家核对后确认。
     * 无 WAIT_RECEIPT 售后单时为空操作。系统身份调用，绕数据域。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void settleOnReceipt(String subOrderNo) {
        OrdAfterSale as = DataScopeContext.executeWithoutScope(() -> documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectOne(
                Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getSubOrderNo, subOrderNo)
                        .eq(OrdAfterSale::getStatus, OrdAfterSale.WAIT_RECEIPT)
                        .last("limit 1")), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo));
        if (as == null) {
            return;
        }
        // 退款永远不自动（《退货退款逻辑》v2）：签收只提醒商家一键确认，钱由商家放
        remind(as, REMIND_SIGNED, "买家已签收，待商家确认退款");
    }


    /**
     * 下单时间（毫秒）。{@code withinHours} 的措辞就是「下单后多少小时内」，所以取的是
     * 子单的创建时间，而不是主单的 {@code paidAt} —— 后者要多一个 mapper，
     * 而两者在这条规则的量级（小时）上没有区别。
     */
    private static Long placedAtOf(OrdSubOrder sub) {
        return sub.getCreatedAt() == null ? null
                : sub.getCreatedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    // ---------------------------------------------------------------- 退款（唯一入口）

    private boolean pointsIntegrated(OrdSubOrder sub) {
        // Refund participation is frozen on each child, not on the mixed-store root.
        return sub != null && sub.getSubOrderNo() != null
                && DataScopeContext.executeWithoutScope(
                        () -> pointsBindings.binding(sub.getSubOrderNo()) != null);
    }

    /** Decision transaction only: authorize REFUNDING and persist an immutable capacity reservation. */
    private void doRefund(OrdAfterSale as, String label, AfterSaleEvent event, Actor actor, String operatorNo) {

        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        if (pointsIntegrated(sub)) {
            if (OrdAfterSale.REFUNDED.equals(as.getStatus())) return;
            if (!OrdAfterSale.REFUNDING.equals(as.getStatus())) {
                AfterSaleContext.Builder context = AfterSaleContext.builder();
                if (event == AfterSaleEvent.PREPARATION_NOT_STARTED_REFUND) context.preparationNotStarted(true);
                transit(as, event, actor, operatorNo, label, context.build());
            }
            AfterCommit.run("resume points refund " + as.getAfterSaleNo(),
                    () -> resumeRefund(as.getAfterSaleNo(), operatorNo));
            return;
        }
        OrdRefundExecution previous = basicRefunds.find(as.getAfterSaleNo());
        if (previous != null) {
            AfterCommit.run("recover original refund " + previous.getExecutionNo(),
                    () -> basicRefunds.recover(previous.getExecutionNo()));
            return;
        }
        if (OrdAfterSale.REFUNDED.equals(as.getStatus())) {
            return;
        }
        if (!OrdAfterSale.REFUNDING.equals(as.getStatus())) {
            AfterSaleContext.Builder context = AfterSaleContext.builder();
            if (event == AfterSaleEvent.PREPARATION_NOT_STARTED_REFUND) {
                // apply 已持有子单行锁并完成履约状态检查；这里把该判断传给状态机守卫。
                context.preparationNotStarted(true);
            }
            transit(as, event, actor, operatorNo, label, context.build());
        }
        prepareOrdinaryAfterDecision(as, operatorNo);

    }

    /**
     * 退款落终态（现金与积分两条分支的公共收尾）：售后单转 {@code REFUNDED}、子单转
     * {@code REFUNDED}、整单退完退券退积分、留痕、发 {@code AfterSaleRefunded}。
     *
     * @param cashMinor 本次实际退回的**现金**（分）；积分形式为 0
     */
    private void settleRefundTerminal(OrdAfterSale as, String label, long cashMinor, boolean fullyRefunded) {
        // 落终态。**回补的标记与状态一起写** —— 分两次写的话，
        // 中间挂掉会让重试再补一次，而多出来的那几件不会有任何地方报错
        as.setRefundedAt(System.currentTimeMillis());
        transit(as, cashMinor==0?AfterSaleEvent.BENEFITS_SETTLED:AfterSaleEvent.REFUND_SUCCEEDED, Actor.SYSTEM, null, label,
                AfterSaleContext.builder().refundProof(new ai.neargo.shop.trade.order.machine.aftersale.RefundTerminalProof(
                        true,cashMinor,cashMinor>0,true,false)).build());

        /*
         * **部分退款不改订单状态**（第 5 步）：只有账本说「全部退完」才经门迁到 REFUNDED（O10）。
         * 此前任何一笔退款落定子单就 REFUNDED —— 退一件，买家看到「已退款」。
         */
        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        if (sub != null && fullyRefunded && canFullyRefund(sub)) {
            statusWriter.order(sub, ai.neargo.shop.trade.order.machine.order.OrderEvent.FULLY_REFUNDED,
                    Actor.SYSTEM, null, label, fullRefundContext());
        }
        if(!refundObligations.versioned("PRF-"+as.getAfterSaleNo())) returnBenefitsIfWholeOrderRefunded(sub);
        eventBus.publish(new OrderEvents.AfterSaleRefunded(as.getAfterSaleNo(), as.getSubOrderNo(),
                as.getUserNo(), cashMinor));
    }

    /** Coupons return only after all children are refunded or cancelled; points use per-refund confirmation. */
    private void returnBenefitsIfWholeOrderRefunded(OrdSubOrder sub) {
        if (sub == null || sub.getOrderNo() == null) {
            return;
        }
        final var siblings = DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getOrderNo, sub.getOrderNo())));
        boolean whole = siblings.stream().allMatch(x ->
                OrdSubOrder.REFUNDED.equals(x.getStatus())
                        || OrdSubOrder.CANCELLED.equals(x.getStatus()));
        if (!whole) {
            return;
        }
        if (couponPort != null && on(FLAG_RETURN_COUPON)) {
            couponPort.release(sub.getOrderNo());
        }


    }

    /**
     * 退货入库（V256）。
     *
     * <p><b>判据是售后类型，不是「退款成功」</b>：
     * {@code REFUND_ONLY} 货根本没回来，补了就是凭空多出几件；
     * {@code RETURN_REFUND} 货回到店里了，不补的话库里当它卖掉了，
     * 这一件会被再卖一次，且要等到发货那天才发现；
     * {@code EXCHANGE} 一出一入净变动为零，平台侧没有库存流水，这一期不动。
     *
     * <p>此前这条路径**从来没有实现过** —— 注释说「发事件，下游据此回补库存」，
     * 而商品域一个消费者都没有。这里改成由 trade 直接调 Port：
     * 与 {@code OrderServiceImpl} 里 {@code stockPort.release()} 同一个形状，
     * 不新增跨域依赖，也不需要事件带上它本来没有的行明细。
     */
    private void restoreStockIfReturned(OrdAfterSale as, ai.neargo.shop.trade.order.refund.model.RefundPlan plan) {
        if (!OrdAfterSale.RETURN_REFUND.equals(as.getType())
                || Integer.valueOf(1).equals(as.getStockRestored())) {
            return;
        }
        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        java.util.Map<Long, OrdItem> byId = new java.util.HashMap<>();
        DataScopeContext.executeWithoutScope(() -> orderItemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                .eq(OrdItem::getSubOrderNo, as.getSubOrderNo()))).forEach(it -> byId.put(it.getId(), it));
        // 只补本张售后退回的那几行、那几件 —— 整单补的话，退 1 件库里会多出 2 件
        List<StockPort.SkuQty> lines = new ArrayList<>();
        for (var l : plan.lines()) {
            OrdItem it = byId.get(l.itemId());
            if (it != null && l.qty() > 0) {
                lines.add(new StockPort.SkuQty(it.getSkuNo(), l.qty(), sub == null ? null : sub.getStoreNo()));
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        stockPort.restore(as.getAfterSaleNo(), lines);
        as.setStockRestored(1);
    }

    private static boolean canFullyRefund(OrdSubOrder sub) {
        try {
            var from = ai.neargo.shop.trade.order.machine.order.OrderStatus.valueOf(sub.getStatus());
            return ai.neargo.shop.trade.order.machine.order.OrderMachine.MACHINE
                    .allowed(from, Actor.SYSTEM, fullRefundContext())
                    .contains(ai.neargo.shop.trade.order.machine.order.OrderEvent.FULLY_REFUNDED);
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    private static ai.neargo.shop.trade.order.machine.order.OrderContext fullRefundContext() {
        return ai.neargo.shop.trade.order.machine.order.OrderContext.authorizedOnly().withFullyRefunded(true);
    }

    // ---------------------------------------------------------------- 装配

    private OrdAfterSale own(String afterSaleNo) {
        return own(afterSaleNo, SecurityUtils.currentUserNo());
    }

    private OrdAfterSale own(String afterSaleNo, String buyerNo) {
        requireBuyer(buyerNo);
        OrdAfterSale as = documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)
                .eq(OrdAfterSale::getUserNo, buyerNo)
                .last("limit 1")), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo);
        if (as == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return as;
    }

    private OrdAfterSale ownForMutation(String no) {
        OrdAfterSale hint = own(no);
        lockRefundChild(hint.getSubOrderNo());
        return own(no);
    }

    /** 商家侧：属主是 merchantNo。查不到即 404 —— 不区分「不存在」与「不是你的」。 */
    private OrdAfterSale ofMerchant(String merchantNo, String afterSaleNo) {
        OrdAfterSale as = DataScopeContext.executeWithoutScope(() ->
                documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)
                        .eq(OrdAfterSale::getEntityNo, merchantNo)
                        .last("limit 1")), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo));
        if (as == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        lockRefundChild(as.getSubOrderNo());
        return DataScopeContext.executeWithoutScope(() -> documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectOne(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)
                        .eq(OrdAfterSale::getEntityNo, merchantNo)), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo));
    }

    private OrdAfterSale activeOf(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getSubOrderNo, subOrderNo)
                        .notIn(OrdAfterSale::getStatus,
                                List.of(OrdAfterSale.CLOSED, OrdAfterSale.REJECTED, OrdAfterSale.REFUNDED))
                        .last("limit 1")), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo));
    }

    private static void requireBuyer(String buyerNo) {
        if (buyerNo == null || buyerNo.isBlank()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private OrdSubOrder ownSubOrder(String subOrderNo, String currentUser) {
        requireBuyer(currentUser);
        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, subOrderNo)
                .eq(OrdSubOrder::getUserNo, currentUser)
                .last("FOR UPDATE"));
        if (sub == null) {
            /*
             * **定位日志**：这单是真不存在，还是存在却属于别的账号？
             * 后者 = 当前登录账号不是下单人（店主换了微信登录就会这样），
             * 是"数据不存在"最常见的原因。绕数据域查全局真实归属（只在失败路径多一次查）。
             */
            String owner = DataScopeContext.executeWithoutScope(() -> {
                OrdSubOrder any = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1"));
                return any == null ? null : any.getUserNo();
            });
            LOG.warn("[after-sale] 子单找不到：subOrderNo={} currentUser={} 该单真实归属={}"
                    + "（归属非空却找不到 = 登录账号≠下单人）", subOrderNo, currentUser, owner);
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return sub;
    }

    private OrdSubOrder lockRefundChild(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() -> subOrderMapper.selectOne(
                Wrappers.<OrdSubOrder>lambdaQuery().eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("FOR UPDATE")));
    }

    private OrdSubOrder subOrderOf(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                        .eq(OrdSubOrder::getSubOrderNo, subOrderNo).last("limit 1")));
    }

    /** 商家侧写入同样要豁免：售后单的属主是买家，商家改它天然跨属主（R11 的同类）。 */
    private void update(OrdAfterSale as) {
        DataScopeContext.executeWithoutScope(() -> afterSaleMapper.updateById(as));
    }

    /** 售后自己的状态集合：时间线按**属于哪个聚合**过滤，不按时间窗口切。 */
    private static final List<String> AFTER_SALE_STATUSES = List.of(
            OrdAfterSale.APPLIED, OrdAfterSale.WAIT_RETURN, OrdAfterSale.RETURNING, OrdAfterSale.WAIT_RECEIPT,
            OrdAfterSale.REFUNDING, OrdAfterSale.REFUNDED,
            OrdAfterSale.REJECTED, OrdAfterSale.ARBITRATING, OrdAfterSale.CLOSED);

    @Override
    public java.util.Optional<AfterSaleVO> ofSubOrder(String subOrderNo) {
        if (subOrderNo == null || subOrderNo.isBlank()) {
            return java.util.Optional.empty();
        }
        /*
         * **取最新的一张**：一张子单可以先被驳回、再申请一次。
         * 取第一张的话，订单详情会一直显示那张已经作废的驳回单，
         * 而用户正在等的是新提的那张。
         */
        OrdAfterSale as = documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getSubOrderNo, subOrderNo)
                .orderByDesc(OrdAfterSale::getId)
                .last("limit 1")), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo);
        return java.util.Optional.ofNullable(as).map(this::detailOf);
    }

    private AfterSaleVO detailOf(OrdAfterSale as) {
        // 订单与售后共用 ord_status_log。早先按「at >= 售后创建时间」切分，
        // 但两者的时间戳精度与写入顺序都不保证 —— 时间窗口是猜，状态集合是判定。
        List<AfterSaleVO.TimelineNode> timeline = statusLogMapper.selectList(
                        Wrappers.<OrdStatusLog>lambdaQuery()
                                .eq(OrdStatusLog::getSubOrderNo, as.getSubOrderNo())
                                .in(OrdStatusLog::getStatus, AFTER_SALE_STATUSES)
                                .orderByAsc(OrdStatusLog::getId)).stream()
                .map(l -> new AfterSaleVO.TimelineNode(l.getStatus(), l.getLabel(),
                        l.getAt() == null ? 0L : l.getAt()))
                .toList();

        List<AfterSaleVO.ReturnItem> returnItems = returnItems(as);
        return new AfterSaleVO(as.getAfterSaleNo(), as.getSubOrderNo(), as.getOrderNo(),
                as.getType(), as.getStatus(), as.getReason(), readList(as.getImages()),
                as.getRefundMinor() == null ? 0L : as.getRefundMinor(),
                Boolean.TRUE.equals(as.getInstant()), as.getMerchantRemark(),
                as.getExpressNo(), as.getLiability(), millis(as.getCreatedAt()),
                // 更新时间：库里有 updated_at，此前没往外发 —— 两个端都按它显示「最后动了什么时候」
                millis(as.getUpdatedAt()), timeline, null,
                as.getRefundMode(), as.getRefundPoints(), as.getApprovedMinor(), as.getFulfillStage(),
                OrdAfterSale.WAIT_RECEIPT.equals(as.getStatus()), escalationOpen(as), refundProgress(as), returnItems,
                as.getReturnReceivedAt() == null ? null
                        : stockPort.returnReceiptStatus("RR-" + as.getAfterSaleNo())).withDocumentNo(as.getDocumentNo());
    }

    private List<AfterSaleVO.ReturnItem> returnItems(OrdAfterSale as) {
        if (afterSaleLines == null) return List.of();
        return afterSaleLines.lines(as.getAfterSaleNo()).stream().map(line -> {
            OrdItem item = orderItemMapper.selectById(line.itemId());
            return new AfterSaleVO.ReturnItem(line.itemId(), item == null ? null : item.getSkuNo(),
                    item == null ? "商品" : item.getTitle(), line.qty());
        }).toList();
    }

    private AfterSaleVO.RefundExecutionProgress refundProgress(OrdAfterSale as) {
        OrdRefundExecution row = basicRefunds.find(as.getAfterSaleNo());
        if(row!=null) {
            var progress=refundObligations.progress(row.getExecutionNo());
            return new AfterSaleVO.RefundExecutionProgress(row.getExecutionNo(),row.getPaymentNo(),row.getStatus(),
                    row.getPhase(),row.getErrorCode(),row.getResourceStatus(),row.getResourceErrorCode(),
                    row.getSucceededAt(),row.getNextAttemptAt(),progress.version(),progress.closureStatus(),
                    progress.cashStatus(),progress.benefitsStatus());
        }
        var progress=refundObligations.progress("PRF-"+as.getAfterSaleNo());
        return progress==null?null:new AfterSaleVO.RefundExecutionProgress("PRF-"+as.getAfterSaleNo(),progress.paymentNo(),
                progress.financialState(),"BENEFITS",null,progress.resourceStatus(),progress.resourceError(),
                as.getRefundedAt(),null,progress.version(),progress.closureStatus(),progress.cashStatus(),progress.benefitsStatus());
    }

    private void appendLog(String subOrderNo, String status, String label,
                           String operatorType, String operatorNo) {
        OrdStatusLog log = new OrdStatusLog();
        log.setSubOrderNo(subOrderNo);
        log.setStatus(status);
        log.setLabel(label);
        log.setOperatorType(operatorType);
        log.setOperatorNo(operatorNo);
        log.setAt(System.currentTimeMillis());
        log.setTenantNo("MAIN");
        log.setCreatedAt(LocalDateTime.now());
        statusLogMapper.insert(log);
    }

    private String writeJson(List<String> images) {
        try {
            return json.writeValueAsString(images == null ? List.of() : images);
        } catch (Exception e) {
            return "[]";
        }
    }

    private List<String> readList(String jsonArray) {
        if (jsonArray == null || jsonArray.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(jsonArray, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            return List.of();
        }
    }

    private static long millis(LocalDateTime t) {
        return t == null ? 0L : t.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }

    @Override
    public int merchantPendingCount(String merchantNo) {
        return merchantPendingCount(merchantNo, null);
    }

    @Override
    public int merchantPendingCount(String merchantNo, String storeNo) {
        var q = Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getEntityNo, merchantNo)
                .in(OrdAfterSale::getStatus, OrdAfterSale.APPLIED, OrdAfterSale.RETURNING,
                        OrdAfterSale.WAIT_RECEIPT);
        if (storeNo != null && !storeNo.isBlank()) {
            q.apply("EXISTS (SELECT 1 FROM ord_sub_order so WHERE so.sub_order_no = "
                    + "ord_after_sale.sub_order_no AND so.store_no = {0} AND so.deleted = 0)", storeNo);
        }
        Long n = DataScopeContext.executeWithoutScope(() ->
                afterSaleMapper.selectCount(q));
        return n == null ? 0 : n.intValue();
    }

    @Override
    public java.util.Set<String> merchantPendingNos(String merchantNo, String storeNo,
                                                     java.util.Collection<String> afterSaleNos) {
        if (storeNo == null || storeNo.isBlank() || afterSaleNos == null || afterSaleNos.isEmpty()) {
            return java.util.Set.of();
        }
        return DataScopeContext.executeWithoutScope(() -> documentNumbers.load(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectList(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getEntityNo, merchantNo)
                        .in(OrdAfterSale::getAfterSaleNo, afterSaleNos)
                        .in(OrdAfterSale::getStatus, OrdAfterSale.APPLIED,
                                OrdAfterSale.RETURNING, OrdAfterSale.WAIT_RECEIPT)
                        .apply("EXISTS (SELECT 1 FROM ord_sub_order so WHERE so.sub_order_no = "
                                + "ord_after_sale.sub_order_no AND so.store_no = {0} AND so.deleted = 0)",
                                storeNo)), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo))
                .stream().map(OrdAfterSale::getAfterSaleNo).collect(java.util.stream.Collectors.toSet());
    }

    // ---------------------------------------------------------------- 平台仲裁（P-6.1）

    @Override
    public List<OpsAfterSaleVO> opsList(String status, String merchantNo) {
        var w = Wrappers.<OrdAfterSale>lambdaQuery();
        if (status != null && !status.isBlank()) {
            w.eq(OrdAfterSale::getStatus, status);
        }
        if (merchantNo != null && !merchantNo.isBlank()) {
            w.eq(OrdAfterSale::getEntityNo, merchantNo);
        }
        w.orderByDesc(OrdAfterSale::getId);
        /*
         * **不绕过**：这是平台仲裁的全量工单池（merchantNo 可空），
         * 正是数据域该起作用的地方。这一页上有商家名与买家昵称 ——
         * 配了商家域的运营不该看到别家的工单，而下一步动作是裁决赔付。
         */
        return documentNumbers.load(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectList(w), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo).stream().map(this::opsDetailOf).toList();
    }

    @Override
    public OpsAfterSaleVO recoverRefund(String afterSaleNo, String operatorNo) {
        OrdAfterSale as = documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo);
        if (as == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        OrdRefundExecution execution = basicRefunds.find(afterSaleNo);
        if (execution == null || (!OrdAfterSale.REFUNDING.equals(as.getStatus())
                && !OrdAfterSale.REFUNDED.equals(as.getStatus()))) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        basicRefunds.recover(execution.getExecutionNo());
        return opsDetailOf(documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectById(as.getId()), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo));
    }

    /**
     * 消费者视角的 {@link #detailOf} 拼上商家名与买家昵称，给平台仲裁台用。
     *
     * <p>逐条查 Port 而不是先收集 entityNo/userNo 批量查——工单池是运营台的低频页面，
     * 不是一屏几百条的高频列表，批量优化在这里只是多绕一层没人用得上的复杂度。
     */
    private OpsAfterSaleVO opsDetailOf(OrdAfterSale as) {
        AfterSaleVO base = detailOf(as);
        String merchantName = merchantPort.find(as.getEntityNo())
                .map(MerchantQueryPort.MerchantBrief::merchantName).orElse(null);
        String buyerNickname = userPort.find(as.getUserNo())
                .map(UserQueryPort.UserBrief::nickname).orElse(null);
        return new OpsAfterSaleVO(base.afterSaleNo(), base.subOrderNo(), base.orderNo(),
                as.getEntityNo(), merchantName, buyerNickname,
                base.type(), base.status(), base.reason(), base.images(), base.refundMinor(),
                base.instant(), base.merchantReply(), base.returnExpressNo(), base.liability(),
                base.createdAt(), base.timeline(), base.refundExecution()).withDocumentNo(base.documentNo());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public OpsAfterSaleVO arbitrate(String afterSaleNo, boolean refund, String liability,
                                    String verdict, String operatorNo) {
        if (verdict == null || verdict.isBlank()) {
            // 用户与商家都会看到它。没有说明的裁决，对双方都等于「平台随便判了一下」
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        if (liability == null || !LIABILITIES.contains(liability)) {
            /*
             * **裁决必须落责任方**。赔付出资比例的口径未定（M4），但责任本身要记下来 ——
             * 不记的话，等口径定了要回头补，而那时已经没人说得清当初是怎么判的。
             */
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        OrdAfterSale as = lockedSystemAfterSale(afterSaleNo);
        if (as == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 只能裁「已上升到平台」的单：还没上升就裁，等于替商家做了他还没做的决定
        if (!OrdAfterSale.ARBITRATING.equals(as.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        // 支持退款时：退货单还没寄回 → 待寄回，否则 → 退款中（AfterSaleTargets.refundOrAwaitReturn）
        as.setLiability(liability);
        transit(as, refund ? AfterSaleEvent.ARBITRATE_REFUND : AfterSaleEvent.ARBITRATE_CLOSE, Actor.PLATFORM,
                operatorNo, (refund ? "平台裁决：支持退款。" : "平台裁决：维持商家决定。") + verdict.trim(),
                AfterSaleContext.builder().returnRefund(OrdAfterSale.RETURN_REFUND.equals(as.getType()))
                        .goodsReturned(as.getReturnReceivedAt() != null)
                        .inTransit(as.getExpressNo() != null && !as.getExpressNo().isBlank()).build());
        if (OrdAfterSale.REFUNDING.equals(as.getStatus())) {
            prepareOrdinaryAfterDecision(as, operatorNo);
        }
        return opsDetailOf(as);
    }

    private void prepareOrdinaryAfterDecision(OrdAfterSale as,String operatorNo) {
        var actor=new OrderActor(as.getEntityNo(),null,operatorNo);
        if(as.getSourceSystem()==null) {
            basicRefunds.prepare(as.getAfterSaleNo(),actor);
            return;
        }
        // REFUNDING + immutable case lines are durable. The existing retry scan resumes a missing execution
        // if a transactional merchant entry has not yet reached a clean outer after-commit boundary.
        AfterCommit.run("prepare original source refund "+as.getAfterSaleNo(),()-> {
            if(!TransactionSynchronizationManager.isActualTransactionActive()) basicRefunds.prepare(as.getAfterSaleNo(),actor);
        });
    }

    /**
     * 卡住的退款单（不变式 I5）。**只查不做** —— 续跑要逐条走
     * {@link #resumeRefund}，而自调用不走代理，循环放在 {@code RefundRetryJob} 里。
     *
     * <p><b>不加 {@code @Transactional}</b>：它只读，且返回的单号会被逐条
     * 独立提交地处置 —— 包成一个事务的话，一条失败会把已经退成功的那几条一起回滚。
     */
    @Override
    public List<String> stuckRefundNos(long stuckBefore, int limit) {
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(stuckBefore), java.time.ZoneId.systemDefault());
        // System queue: fresh persisted work is eligible now; older unprepared decisions retain the age cutoff.
        List<String> older = DataScopeContext.executeWithoutScope(() ->
                documentNumbers.load(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectList(Wrappers.<OrdAfterSale>lambdaQuery()
                                .eq(OrdAfterSale::getStatus, OrdAfterSale.REFUNDING)
                                .notInSql(OrdAfterSale::getAfterSaleNo,
                                        "SELECT after_sale_no FROM ord_points_refund_binding"
                                        + " WHERE phase IN ('COMPENSATED_PENDING_OPERATOR',"
                                        + " 'CASH_FAILED_PENDING_OPERATOR')")
                                .lt(OrdAfterSale::getUpdatedAt, cutoff)
                                .orderByAsc(OrdAfterSale::getUpdatedAt)
                                .last("limit " + Math.max(1, limit))), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo)
                        .stream().map(OrdAfterSale::getAfterSaleNo).toList());
        var ready = new LinkedHashSet<>(refundExecutions.pendingNos(Math.max(1, Math.min(limit, 1000))));
        ready.addAll(older);
        return ready.stream().limit(Math.max(1, limit)).toList();
    }

    /**
     * 不变式 I4：已退款而分账没回退的。<b>只查不修</b> —— 理由见接口注释。
     *
     * <p><b>只判 {@code = false}，不判 null。</b> 第一版写的是
     * {@code isNull(...).or().eq(..., false)}，理由是「存量数据可能是 null」——
     * 而那是想当然：{@code ord_after_sale.split_reversed} 在 baseline 里就是
     * {@code TINYINT NOT NULL DEFAULT 0}，库里不可能有 null。
     * 测试第一次跑就用 {@code NULL not allowed} 把这个前提证伪了。
     *
     * <p>留着那个分支的害处不是慢，是<b>它会让下一个人以为这一列可能为空</b>，
     * 于是照着在别处也写一遍防御 —— 而防御的是一个不存在的情况。
     */
    @Override
    public List<String> refundedWithoutSplitReversal(long since, int limit) {
        return DataScopeContext.executeWithoutScope(() ->
                documentNumbers.load(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectList(Wrappers.<OrdAfterSale>lambdaQuery()
                                .eq(OrdAfterSale::getStatus, OrdAfterSale.REFUNDED)
                                .ge(OrdAfterSale::getRefundedAt, since)
                                .eq(OrdAfterSale::getSplitReversed, false)
                                .orderByAsc(OrdAfterSale::getRefundedAt)
                                .last("limit " + Math.max(1, limit))), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo)
                        .stream().map(OrdAfterSale::getAfterSaleNo).toList());
    }

    /** Points refunds use the dedicated coordinator; ordinary cash remains on production V438. */
    @Override
    public void resumeRefund(String afterSaleNo, String operatorNo) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("REFUND_EXECUTION_REQUIRES_NO_TRANSACTION");
        }
        OrdAfterSale as = refundAfterSale(afterSaleNo, false);
        if (OrdAfterSale.REFUNDED.equals(as.getStatus())) {
            pointsResources.recover(afterSaleNo);
            return;
        }
        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        if (pointsIntegrated(sub)) {
            resumePointsRefund(afterSaleNo, sub.getOrderNo());
            return;
        }
        OrdRefundExecution ordinary = basicRefunds.find(afterSaleNo);
        if (ordinary != null) basicRefunds.recover(ordinary.getExecutionNo());
        else if(as.getSourceSystem()!=null && OrdAfterSale.REFUNDING.equals(as.getStatus()))
            basicRefunds.prepare(afterSaleNo,new OrderActor(as.getEntityNo(),sub.getStoreNo(),operatorNo));
    }

    @Override
    public OpsAfterSaleVO closeCompensatedRefund(String afterSaleNo, String operatorNo,
                                                  String reason) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("POINTS_OPERATOR_CLOSE_REQUIRES_NO_TRANSACTION");
        if (afterSaleNo == null || afterSaleNo.isBlank() || operatorNo == null
                || operatorNo.isBlank() || reason == null || reason.isBlank())
            throw BizException.of(ErrorCode.BAD_REQUEST);
        if (pointsRefunds == null || pointsRefundSource == null)
            throw new IllegalArgumentException("POINTS_IMPLEMENTATION_REQUIRED");
        var binding = pointsRefundSource.inspect(afterSaleNo);
        if (binding == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (binding.phase() == ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.ABORTED) {
            var closed = refundAfterSale(afterSaleNo, false);
            if (!OrdAfterSale.CLOSED.equals(closed.getStatus()))
                throw new IllegalStateException("POINTS_OPERATOR_CLOSE_INCOMPLETE");
            return opsDetailOf(closed);
        }
        if (binding.phase() != ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.COMPENSATED_PENDING_OPERATOR
                && binding.phase() != ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.CASH_FAILED_PENDING_OPERATOR)
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        var prep = binding.preparation();
        var proof = pointsRefundPort(afterSaleNo).recoveryStatus(prep.preparationNo(), prep.inputHash());
        var execution = refundExecutions.inspect(afterSaleNo);
        if (execution == null) throw new IllegalStateException("POINTS_OPERATOR_REFUND_SOURCE_MISSING");
        var plan = execution.plan();
        var sub = subOrderOf(plan.subOrderNo());
        if (!settlePort.cancelPreparedRefund(new SettlePort.SplitReversal(plan.subOrderNo(),
                afterSaleNo, plan.goodsMinor(), plan.freightMinor(), LineShares.goodsPaid(sub)),
                prep.preparationNo(), prep.inputHash()))
            throw BizException.of(ErrorCode.SPLIT_EXPIRED);
        refundTransactions.executeWithoutResult(status -> {
            pointsRefundSource.closeCompensated(afterSaleNo, proof);
            var as = refundAfterSale(afterSaleNo, true);
            if (!OrdAfterSale.REFUNDING.equals(as.getStatus()))
                throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
            transit(as, AfterSaleEvent.COMPENSATED_CLOSE, Actor.PLATFORM, operatorNo,
                    "补收回退后人工关闭：" + reason.trim(), AfterSaleContext.builder().build());
        });
        return opsDetailOf(refundAfterSale(afterSaleNo, false));
    }

    @Override
    public OpsAfterSaleVO retryCompensatedRefund(String afterSaleNo, String operatorNo,
                                                  String reason) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("POINTS_OPERATOR_RETRY_REQUIRES_NO_TRANSACTION");
        if (afterSaleNo == null || afterSaleNo.isBlank() || operatorNo == null
                || operatorNo.isBlank() || reason == null || reason.isBlank() || reason.length() > 500)
            throw BizException.of(ErrorCode.BAD_REQUEST);
        if (pointsRefunds == null || pointsRefundSource == null)
            throw new IllegalArgumentException("POINTS_IMPLEMENTATION_REQUIRED");
        var binding = pointsRefundSource.inspect(afterSaleNo);
        if (binding == null) throw BizException.of(ErrorCode.NOT_FOUND);
        if (binding.phase() == ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.ABORTED) {
            var prior = retryRows.find(afterSaleNo);
            if (prior == null || !operatorNo.equals(prior.get("operator_no"))
                    || !reason.trim().equals(prior.get("reason")))
                throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
            return opsDetailOf(refundAfterSale((String) prior.get("new_after_sale_no"), false));
        }
        if (binding.phase() != ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.COMPENSATED_PENDING_OPERATOR
                && binding.phase() != ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.CASH_FAILED_PENDING_OPERATOR)
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        var prep = binding.preparation();
        var proof = pointsRefundPort(afterSaleNo).recoveryStatus(prep.preparationNo(), prep.inputHash());
        var execution = refundExecutions.inspect(afterSaleNo);
        if (execution == null) throw new IllegalStateException("POINTS_OPERATOR_REFUND_SOURCE_MISSING");
        var plan = execution.plan();
        var subForHold = subOrderOf(plan.subOrderNo());
        if (!settlePort.cancelPreparedRefund(new SettlePort.SplitReversal(plan.subOrderNo(),
                afterSaleNo, plan.goodsMinor(), plan.freightMinor(), LineShares.goodsPaid(subForHold)),
                prep.preparationNo(), prep.inputHash()))
            throw BizException.of(ErrorCode.SPLIT_EXPIRED);
        String next = documentNumbers.withReservation(java.util.Map.of(DocumentType.AFTER_SALE, 1), () -> refundTransactions.execute(status -> DataScopeContext.executeWithoutScope(() -> {
            var original = refundAfterSale(afterSaleNo, false);
            var sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                    .eq(OrdSubOrder::getSubOrderNo, original.getSubOrderNo())
                    .last("LIMIT 1 FOR UPDATE"));
            if (sub == null)
                throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
            var prior = retryRows.find(afterSaleNo);
            if (prior != null) {
                if (!operatorNo.equals(prior.get("operator_no"))
                        || !reason.trim().equals(prior.get("reason")))
                    throw new IllegalArgumentException("POINTS_REFUND_RETRY_CONFLICT");
                return (String) prior.get("new_after_sale_no");
            }
            if (pointsRefundSource.inspect(afterSaleNo).phase()
                    != ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.COMPENSATED_PENDING_OPERATOR
                    && pointsRefundSource.inspect(afterSaleNo).phase()
                    != ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.CASH_FAILED_PENDING_OPERATOR
                    || !OrdAfterSale.REFUNDING.equals(original.getStatus()))
                throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
            var active = activeOf(sub.getSubOrderNo());
            if (active != null && !afterSaleNo.equals(active.getAfterSaleNo()))
                throw BizException.of(ErrorCode.CONFLICT);
            var frozen = refundExecutions.inspect(afterSaleNo);
            if (frozen == null || !"CASH_PENDING".equals(frozen.preparation().state())
                    || !plan.equals(frozen.plan()))
                throw new IllegalStateException("POINTS_REFUND_RETRY_SOURCE_CONFLICT");
            pointsRefundSource.closeCompensated(afterSaleNo, proof);
            transit(original, AfterSaleEvent.COMPENSATED_CLOSE, Actor.PLATFORM, operatorNo,
                    "补收回退后人工重试：" + reason.trim(), AfterSaleContext.builder().build());
            var retried = new OrdAfterSale();
            retried.setAfterSaleNo(BizKey.next(BizKey.AFTER_SALE));
            retried.setSubOrderNo(original.getSubOrderNo());
            retried.setOrderNo(original.getOrderNo());
            retried.setUserNo(original.getUserNo());
            retried.setEntityNo(original.getEntityNo());
            retried.setType(original.getType());
            retried.setReason(original.getReason());
            retried.setImages(original.getImages());
            retried.setRefundMinor(plan.totalMinor());
            retried.setApprovedMinor(plan.totalMinor());
            retried.setRefundMode(OrdAfterSale.REFUND_MODE_CASH);
            retried.setStatus(OrdAfterSale.REFUNDING);
            retried.setSplitReversed(false);
            retried.setInstant(false);
            retried.setLiability(original.getLiability());
            retried.setFulfillStage(original.getFulfillStage());
            retried.setExpressCompany(original.getExpressCompany());
            retried.setExpressNo(original.getExpressNo());
            slots.occupy(sub.getSubOrderNo(), retried.getAfterSaleNo());
            documentNumbers.bind(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", retried, retried.getAfterSaleNo(), afterSaleMapper::insert, OrdAfterSale::setDocumentNo);
            requestPlanner.save(retried.getAfterSaleNo(), plan);
            refundExecutor.prepare(retried, sub);
            retryRows.create(afterSaleNo, retried.getAfterSaleNo(), operatorNo, reason.trim());
            appendLog(sub.getSubOrderNo(), OrdAfterSale.REFUNDING,
                    "运营重新发起退款（原售后 " + afterSaleNo + "）：" + reason.trim(),
                    OrdStatusLog.BY_PLATFORM, operatorNo);
            return retried.getAfterSaleNo();
        })));
        return opsDetailOf(refundAfterSale(next, false));
    }

    private void resumePointsRefund(String afterSaleNo,String orderNo) {
        if(pointsPayments==null || pointsRefunds==null || pointsRefundSource==null
                || pointsSourceDelivery==null || pointsClosure==null) {
            throw new IllegalArgumentException("POINTS_IMPLEMENTATION_REQUIRED");
        }
        var payment=pointsPayments.verified(orderNo).orElseThrow(
                () -> new IllegalArgumentException("POINTS_PAYMENT_NOT_VERIFIED"));
        var execution=refundTransactions.execute(status -> {
            OrdAfterSale as=refundAfterSale(afterSaleNo,false);
            if(OrdAfterSale.REFUNDED.equals(as.getStatus())) return null;
            if(!OrdAfterSale.REFUNDING.equals(as.getStatus()))
                throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
            refundExecutor.prepare(as,subOrderOf(as.getSubOrderNo()));
            var saved=refundExecutions.inspect(afterSaleNo);
            pointsRefundSource.stage(afterSaleNo,saved.plan(),payment);
            return saved;
        });
        if(execution==null) return;
        var binding=pointsRefundSource.inspect(afterSaleNo);
        if("COMMITTED".equals(execution.preparation().state()) && refundObligations.versioned("PRF-"+afterSaleNo)) {
            recoverPointsClosure(afterSaleNo,execution.plan(),binding.preparation().netCashMinor(),binding.cashPaymentNo());
            return;
        }
        if(binding.phase()==ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.COMPENSATED_PENDING_OPERATOR
                || binding.phase()==ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.CASH_FAILED_PENDING_OPERATOR)
            return;
        var preparation=binding.preparation()!=null ? binding.preparation()
                : pointsRefundPort(afterSaleNo).prepare(binding.command());
        binding=pointsRefundSource.acceptPreparation(afterSaleNo,preparation);
        if(preparation.netCashMinor()==0) {
            var plan=execution.plan();
            var sub=subOrderOf(plan.subOrderNo());
            if(!settlePort.reversePreparedRefund(new SettlePort.SplitReversal(plan.subOrderNo(),afterSaleNo,
                    plan.goodsMinor(),plan.freightMinor(),LineShares.goodsPaid(sub)))) {
                throw BizException.of(ErrorCode.SPLIT_EXPIRED);
            }
            refundTransactions.executeWithoutResult(status -> pointsRefundSource.recordZeroCash(afterSaleNo));
            String zeroEvent="ORDER:ZERO_CASH:"+afterSaleNo;
            pointsSourceDelivery.deliver(zeroEvent,plan.subOrderNo());
            var zeroConfirmed=pointsRefundPort(afterSaleNo).confirm(new ai.neargo.shop.spi.points.PointsRefundPort.RefundConfirmation(
                    preparation.preparationNo(),preparation.inputHash(),null,
                    preparation.collectionTicket(),null,zeroEvent));
            pointsRefundSource.acceptPointsConfirmation(afterSaleNo,zeroConfirmed);
            if(!settlePort.confirmPreparedRefund(new SettlePort.SplitReversal(plan.subOrderNo(),afterSaleNo,
                    plan.goodsMinor(),plan.freightMinor(),LineShares.goodsPaid(sub)),
                    preparation.preparationNo(),preparation.inputHash()))
                throw BizException.of(ErrorCode.SPLIT_EXPIRED);
            finishPointsRefund(afterSaleNo,plan,0,null);
            return;
        }
        if(binding.phase()==ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.COMPENSATING) {
            var status=pointsRefundPort(afterSaleNo).recoveryStatus(preparation.preparationNo(),preparation.inputHash());
            if("ABORTED".equals(status.state()) && status.compensationSucceeded()) {
                var plan=execution.plan();
                var sub=subOrderOf(plan.subOrderNo());
                if(!settlePort.cancelPreparedRefund(new SettlePort.SplitReversal(plan.subOrderNo(),afterSaleNo,
                        plan.goodsMinor(),plan.freightMinor(),LineShares.goodsPaid(sub)),
                        preparation.preparationNo(),preparation.inputHash()))
                    throw BizException.of(ErrorCode.SPLIT_EXPIRED);
                pointsRefundSource.recordCompensated(afterSaleNo,status);
                return;
            }
            pointsRefundPort(afterSaleNo).requestCompensation(new ai.neargo.shop.spi.points.PointsRefundPort.RefundAbort(
                    preparation.preparationNo(),preparation.inputHash(),binding.cashPaymentNo()),
                    preparation.revision());
            return;
        }
        if(binding.phase()==ai.neargo.shop.trade.order.points.OrderPointsRefundCoordinator.Phase.PREPARED) {
            var decision=pointsRefundPort(afterSaleNo).authorizeCash(preparation.preparationNo(),
                    preparation.inputHash(),preparation.revision());
            binding=pointsRefundSource.authorizeCash(afterSaleNo,decision);
        }
        var intent=refundExecutions.beginCash(afterSaleNo);
        var plan=execution.plan();
        var sub=subOrderOf(plan.subOrderNo());
        if(!settlePort.reversePreparedRefund(new SettlePort.SplitReversal(plan.subOrderNo(),afterSaleNo,
                plan.goodsMinor(),plan.freightMinor(),LineShares.goodsPaid(sub)))) {
            throw BizException.of(ErrorCode.SPLIT_EXPIRED);
        }
        String paymentNo=execution.cashPaymentNo();
        if(paymentNo==null) {
            paymentNo=settlePort.prepareRefund(plan.subOrderNo(),afterSaleNo,intent.netCashMinor(),"售后退款");
            var evidence=settlePort.refundEvidence(paymentNo);
            refundExecutions.bindCash(afterSaleNo,paymentNo,intent.netCashMinor(),evidence);
            settlePort.dispatchRefund(paymentNo);
            return;
        }
        settlePort.dispatchRefund(paymentNo);
        settlePort.refreshRefund(paymentNo);
        var evidence=settlePort.refundEvidence(paymentNo);
        if(evidence!=null && evidence.safety()!=null && evidence.safety().safeToRecover()) {
            if(preparation.collectionTicket()==null) {
                pointsRefundPort(afterSaleNo).abort(new ai.neargo.shop.spi.points.PointsRefundPort.RefundAbort(
                        preparation.preparationNo(),preparation.inputHash(),paymentNo));
                var status=pointsRefundPort(afterSaleNo).recoveryStatus(preparation.preparationNo(),preparation.inputHash());
                refundTransactions.executeWithoutResult(tx ->
                        pointsRefundSource.recordNoCollectionCashFailure(afterSaleNo,evidence,status));
                return;
            }
            refundTransactions.executeWithoutResult(status -> pointsRefundSource.recordCashFailure(afterSaleNo,evidence));
            pointsRefundPort(afterSaleNo).requestCompensation(new ai.neargo.shop.spi.points.PointsRefundPort.RefundAbort(
                    preparation.preparationNo(),preparation.inputHash(),paymentNo),preparation.revision());
            return;
        }
        if(evidence==null || !"SUCCESS".equals(evidence.status()) || evidence.verifiedAt()==null) return;
        refundTransactions.executeWithoutResult(status -> {
            refundExecutions.confirmCash(afterSaleNo,evidence);
            pointsRefundSource.recordCashSuccess(afterSaleNo,evidence);
        });
        String eventNo="ORDER:REFUND_CONFIRM:"+afterSaleNo;
        pointsSourceDelivery.deliver(eventNo,plan.subOrderNo());
        var confirmed=pointsRefundPort(afterSaleNo).confirm(new ai.neargo.shop.spi.points.PointsRefundPort.RefundConfirmation(
                preparation.preparationNo(),preparation.inputHash(),paymentNo,
                preparation.collectionTicket(),null));
        pointsRefundSource.acceptPointsConfirmation(afterSaleNo,confirmed);
        if(!settlePort.confirmPreparedRefund(new SettlePort.SplitReversal(plan.subOrderNo(),afterSaleNo,
                plan.goodsMinor(),plan.freightMinor(),LineShares.goodsPaid(sub)),
                preparation.preparationNo(),preparation.inputHash()))
            throw BizException.of(ErrorCode.SPLIT_EXPIRED);
        finishPointsRefund(afterSaleNo,plan,intent.netCashMinor(),paymentNo);
    }

    /** Commit confirmed source counters once; consumer obligations can then finish independently. */
    private void finishPointsRefund(String no, ai.neargo.shop.trade.order.refund.model.RefundPlan plan,long cash,String payment) {
        boolean versioned=refundObligations.versioned("PRF-"+no);
        refundTransactions.executeWithoutResult(tx -> {
            if(cash==0) refundExecutions.completeZeroCash(no);
            var result=refundExecutions.commit(no);
            var as=refundAfterSale(no,true);
            if(OrdAfterSale.REFUNDED.equals(as.getStatus())) return;
            as.setSplitReversed(true);
            as.setRefundPaymentNo(payment);
            if(versioned) {
                update(as);
                refundObligations.prepare("PRF-"+no);
                pointsResources.prepare(as,plan);
            } else {
                restoreStockIfReturned(as,plan);
                settleRefundTerminal(as,cash==0?"积分退款已确认":"退款到账已核验",cash,result.progress()==RefundProgress.FULL);
                pointsRefundSource.commit(no);
                pointsClosure.finalizeSubOrder(plan.subOrderNo());
            }
        });
        if(versioned) recoverPointsClosure(no,plan,cash,payment);
    }

    private void recoverPointsClosure(String no, ai.neargo.shop.trade.order.refund.model.RefundPlan plan,long cash,String payment) {
        refundObligations.recover("PRF-"+no);
        refundTransactions.executeWithoutResult(tx -> {
            var sub=lockRefundChild(plan.subOrderNo());
            var as=refundAfterSale(no,true);
            if(OrdAfterSale.REFUNDED.equals(as.getStatus()) || !refundObligations.settled("PRF-"+no)) return;
            var execution=refundExecutions.inspect(no);
            var binding=pointsRefundSource.inspect(no);
            if(execution==null || !"COMMITTED".equals(execution.preparation().state())
                    || binding==null || binding.outcome()==null || !"CONFIRMED".equals(binding.outcome().state()))
                throw BizException.of(ErrorCode.CONFLICT);
            var items=DataScopeContext.executeWithoutScope(() -> orderItemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                    .eq(OrdItem::getSubOrderNo,plan.subOrderNo()).last("FOR UPDATE")));
            long ordered=items.stream().mapToLong(it -> it.getQty()).sum();
            long terminated=items.stream().mapToLong(it -> it.getRefundedQty()==null?0:it.getRefundedQty()).sum();
            var facts=new ai.neargo.shop.trade.order.refund.model.RefundClosureFacts(ordered,0,terminated,
                    sub.getPayAmount(),sub.getRefundedMinor(),true);
            var decision=new ai.neargo.shop.trade.order.refund.policy.RefundClosurePolicy().decide(facts);
            as.setRefundPaymentNo(payment);
            settleRefundTerminal(as,cash==0?"零现金退款权益已结清":"退款及权益已结清",cash,
                    decision.targetOrderStatus()==ai.neargo.shop.trade.order.machine.order.OrderStatus.REFUNDED);
            pointsRefundSource.commit(no);
            pointsClosure.finalizeSubOrder(plan.subOrderNo());
        });
        pointsResources.recover(no);
    }

    private OrdAfterSale refundAfterSale(String afterSaleNo, boolean lock) {
        OrdAfterSale as = DataScopeContext.executeWithoutScope(() -> documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectOne(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)
                        .last(lock ? "LIMIT 1 FOR UPDATE" : "LIMIT 1")), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo));
        if (as == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        return as;

    }

    // ------------------------------------------------------------------ 时效（§3）

    /**
     * {@code APPLIED} 里放着的都是<b>等商家</b>的单 —— 自营单一申请就进 {@code ARBITRATING}
     * （责任跟着钱走，平台就是卖家），所以按状态取已经天然排除了它们，
     * 不需要再去问一次 {@code funds_mode}。这条判断有测试盯着。
     */
    @Override
    public List<String> idlePendingNos(long idleBefore, int limit) {
        return idleNos(idleBefore, limit, q -> q.eq(OrdAfterSale::getStatus, OrdAfterSale.APPLIED));
    }

    @Override
    public List<String> unshippedReturnNos(long idleBefore, int limit) {
        return idleNos(idleBefore, limit, q -> q.eq(OrdAfterSale::getStatus, OrdAfterSale.WAIT_RETURN));
    }

    @Override
    public List<String> unconfirmedReturnNos(long idleBefore, int limit) {
        return idleNos(idleBefore, limit, q -> q.eq(OrdAfterSale::getStatus, OrdAfterSale.RETURNING));
    }

    /**
     * 三条时效查询共用的骨架：同一个「最后动过的时间早于 X」+ 同一条数据域豁免。
     *
     * <p><b>不带数据域</b>：系统巡检没有 BizContext，带上查出来的是空集 ——
     * 而空集与「没有超时的单」在结果上一模一样（{@link #stuckRefundNos} 同一条注释）。
     */
    private List<String> idleNos(long idleBefore, int limit,
                                 java.util.function.Consumer<
                                         com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OrdAfterSale>> where) {
        LocalDateTime cutoff = LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(idleBefore), java.time.ZoneId.systemDefault());
        var q = Wrappers.<OrdAfterSale>lambdaQuery();
        where.accept(q);
        q.lt(OrdAfterSale::getUpdatedAt, cutoff)
                .orderByAsc(OrdAfterSale::getUpdatedAt)
                .last("limit " + Math.max(1, limit));
        return DataScopeContext.executeWithoutScope(() ->
                documentNumbers.load(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectList(q), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo).stream()
                        .map(OrdAfterSale::getAfterSaleNo).toList());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void remindOverdue(String afterSaleNo) {
        OrdAfterSale as = bySystem(afterSaleNo);
        if (as == null || !OrdAfterSale.APPLIED.equals(as.getStatus())) {
            return;   // 幂等：商家在这一刻自己处理了
        }
        /*
         * **退款永远不自动**（《退货退款逻辑》v2，2026-10-14 拍板）：此前这里替商家同意并退钱，
         * 生鲜灰色地带又只会无限再提醒、没有出口。现在统一：提醒商家（到点一次 + 24h 后一次），
         * 并给买家开「申请平台介入」（escalationOpen 按 replyHours 判）。
         */
        remind(as, REMIND_OVERDUE, "商家超时未处理，已提醒商家；买家可申请平台介入");
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void autoCloseUnshipped(String afterSaleNo) {
        OrdAfterSale as = bySystem(afterSaleNo);
        if (as == null || !OrdAfterSale.WAIT_RETURN.equals(as.getStatus())) {
            return;   // 买家在本轮扫描与这一刻之间寄了（已转 RETURNING），或已处理
        }
        transit(as, AfterSaleEvent.RETURN_EXPIRED, Actor.SYSTEM, null,
                "超过寄回时限仍未寄出，本次申请已关闭 —— 可重新申请", AfterSaleContext.builder().build());
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void remindUnconfirmedReturn(String afterSaleNo) {
        OrdAfterSale as = bySystem(afterSaleNo);
        if (as == null || !OrdAfterSale.RETURNING.equals(as.getStatus())) {
            return;
        }
        // 此前这里「商家超时未确认收货，系统自动退款」—— 改为提醒 + 开放介入，钱由商家或平台放
        remind(as, REMIND_RETURN, "退货已寄回，商家超时未确认收货，已提醒商家；买家可申请平台介入");
    }

    // ---------------------------------------------------------------- 状态机 / 提醒 / 介入入口

    /** 提醒的流水事件名（落 ord_status_log.event）：每种最多提醒两次（到点一次、再隔 24h 一次） */
    private static final String REMIND_OVERDUE = "REMIND_MERCHANT_OVERDUE";
    private static final String REMIND_RETURN = "REMIND_RETURN_UNCONFIRMED";
    private static final String REMIND_SIGNED = "REMIND_SIGNED_CONFIRM_REFUND";
    private static final int REMIND_MAX = 2;
    private static final long REMIND_GAP_MS = 24 * 3_600_000L;

    /**
     * 按新状态机校验这次动作（TDD-订单状态机 步 3：先用它判合法性，写路径在步 5 整体改走转换门）。
     * 归属已由 own / ofMerchant / bySystem 判过，这里只判「这个人在这个状态能不能做这件事」。
     */
    private void transit(OrdAfterSale as, AfterSaleEvent event, Actor actor, String operatorNo, String label,
                         AfterSaleContext ctx) {
        if (event == AfterSaleEvent.ESCALATE && OrdAfterSale.REJECTED.equals(as.getStatus())) {
            // 被驳回时已释放占位；申请介入要重新占 —— 这期间买家若已另提一张，介入即冲突
            slots.occupy(as.getSubOrderNo(), as.getAfterSaleNo());
        }
        statusWriter.afterSale(as, event, actor, operatorNo, label, ctx);
        slots.releaseIfDone(as);
        // 状态已经 CAS 写过（版本已同步）；这里写同一次动作改的其余字段（备注 / 核定额 / 运单号 / 责任方…）
        if (DataScopeContext.executeWithoutScope(() -> afterSaleMapper.updateById(as)) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);   // R5：@Version 冲突写 0 行不报错，必须看返回值
        }
    }

    /**
     * 买家能否申请平台介入：被驳回、在途待签收 → 能；待商家处理 / 已寄回待确认 → 商家超时后才能。
     * 计时按 {@code updated_at}（提醒只写流水不动它，所以不会把计时重置）。
     */
    private boolean escalationOpen(OrdAfterSale as) {
        long idle = System.currentTimeMillis() - millis(as.getUpdatedAt());
        var rule = ruleService.get();
        return switch (as.getStatus()) {
            case OrdAfterSale.REJECTED, OrdAfterSale.WAIT_RECEIPT -> true;
            case OrdAfterSale.APPLIED -> idle >= rule.replyHours() * 3_600_000L;
            case OrdAfterSale.RETURNING -> idle >= rule.confirmHours() * 3_600_000L;
            default -> false;
        };
    }

    /**
     * 提醒商家（写流水 + 发「售后待处理」事件走既有的商家触达）；每种最多两次、间隔 24h。
     *
     * <p>去重按 V402 的 {@code aggregate_no + event} 精确查这张售后单自己的提醒，<b>不按时间窗口、不按标签文字</b>。
     * 上一版按「标签前缀 + at ≥ 售后创建时间」查，在全量测试里出现过重复提醒（单跑复现不了，
     * 根因未定位；时间窗口要做 LocalDateTime → 毫秒的时区换算，是嫌疑之一），换成按单号与事件名查之后不再依赖它。
     */
    private void remind(OrdAfterSale as, String tag, String label) {
        List<OrdStatusLog> sent = DataScopeContext.executeWithoutScope(() -> statusLogMapper.selectList(
                Wrappers.<OrdStatusLog>lambdaQuery()
                        .eq(OrdStatusLog::getAggregateNo, as.getAfterSaleNo())
                        .eq(OrdStatusLog::getEvent, tag)
                        .orderByDesc(OrdStatusLog::getId)));
        long now = System.currentTimeMillis();
        if (sent.size() >= REMIND_MAX
                || (!sent.isEmpty() && sent.get(0).getAt() != null && now - sent.get(0).getAt() < REMIND_GAP_MS)) {
            return;
        }
        OrdStatusLog row = new OrdStatusLog();
        row.setSubOrderNo(as.getSubOrderNo());
        row.setAggregateType("AFTER_SALE");
        row.setAggregateNo(as.getAfterSaleNo());
        row.setEvent(tag);
        row.setFromStatus(as.getStatus());
        row.setStatus(as.getStatus());
        row.setLabel(label);
        row.setOperatorType(OrdStatusLog.BY_SYSTEM);
        row.setAt(now);
        row.setTenantNo("MAIN");
        row.setCreatedAt(LocalDateTime.now());
        DataScopeContext.executeWithoutScope(() -> statusLogMapper.insert(row));
        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        eventBus.publish(new OrderEvents.AfterSaleApplied(as.getAfterSaleNo(), as.getSubOrderNo(),
                as.getEntityNo(), sub == null ? null : sub.getStoreNo(), as.getUserNo(), as.getType(),
                as.getRefundMinor() == null ? 0L : as.getRefundMinor()));
    }

    private OrdAfterSale lockedSystemAfterSale(String no) {
        OrdAfterSale hint = bySystem(no);
        if (hint == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        lockRefundChild(hint.getSubOrderNo());
        return bySystem(no);
    }

    /** 系统身份取单：没有属主校验（{@link #ofMerchant} 那套是给商家用的），也不带数据域 */
    private OrdAfterSale bySystem(String afterSaleNo) {
        return DataScopeContext.executeWithoutScope(() ->
                documentNumbers.loadOne(DocumentType.AFTER_SALE, "CORE_TRADE", "ord_after_sale", afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo).last("limit 1")), OrdAfterSale::getAfterSaleNo, OrdAfterSale::setDocumentNo));
    }

    /** 责任方取值域，与 {@code ord_after_sale.liability} 的注释一致。 */
    private static final java.util.Set<String> LIABILITIES =
            java.util.Set.of("PLATFORM", "MERCHANT", "PICKUP");
}
