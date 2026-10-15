package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.trade.order.policy.PhaseOnePointsPolicy;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.service.BasicRefundExecutionService;
import ai.neargo.shop.trade.order.refund.service.BasicRefundPolicy;
import ai.neargo.shop.trade.order.refund.service.RefundableQuery;
import ai.neargo.shop.trade.order.entity.OrdRefundExecution;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Isolation;

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

/**
 * 售后。
 *
 * <p><b>本类只有一条真正重要的规则</b>：{@link #doRefund} 里，
 * 「回退分账」永远在「退款」之前，且回退失败就**停在 REFUNDING 等重试**，绝不继续退款。
 * 钱可以晚退给用户（他会催），但不能退了之后收不回分账（没人会还）。
 */
@Service
public class AfterSaleServiceImpl implements AfterSaleService {

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
    /** 现金退款：记账 → 按额回退分账 → 原路退（第 5 步，取代 doRefund 的现金分支） */
    private final ai.neargo.shop.trade.order.refund.service.AfterSaleRefundExecutor refundExecutor;
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

    /** 整单退款退抵扣积分（待办设计 P2a）。setter 注入，理由同 couponPort */
    private ai.neargo.shop.spi.settle.PointsPort pointsPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPointsPort(ai.neargo.shop.spi.settle.PointsPort pointsPort) {
        this.pointsPort = pointsPort;
    }

    /** 退券 / 退积分 / 收回赠送积分各一个开关（运营端功能开关，默认开） */
    private ai.neargo.shop.spi.platform.PlatformSwitchPort switchPort;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setSwitchPort(ai.neargo.shop.spi.platform.PlatformSwitchPort switchPort) {
        this.switchPort = switchPort;
    }

    static final String FLAG_RETURN_COUPON = "refund.return-coupon";
    static final String FLAG_RETURN_POINTS = "refund.return-points";
    static final String FLAG_CLAWBACK_EARNED = "refund.clawback-earned";

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
                                ai.neargo.shop.trade.order.aftersale.AfterSaleSlots slots) {
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
        String waybillStatus = null;
        Long waybillCreatedAt = null;
        if (logisticsPort != null && OrdSubOrder.EXPRESS.equals(sub.getFulfillment())
                && sub.getExpressNo() != null && !sub.getExpressNo().isBlank()) {
            var tv = logisticsPort.track(
                    ai.neargo.shop.spi.logistics.LogisticsPort.TrackQuery.subOrder(
                            sub.getSubOrderNo(), "BIZ", false)).orElse(null);
            if (tv != null) {
                waybillStatus = tv.status();
                waybillCreatedAt = tv.createdAt();
            }
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO apply(String subOrderNo, ApplyCommand cmd) {
        // 售后定位日志（2026-10-11 店主反馈退款"数据不存在"）：先把"谁在退哪一单、带没带图"记下来
        LOG.info("[after-sale] apply 进入 subOrderNo={} currentUser={} type={} hasReason={} imageCount={}",
                subOrderNo, SecurityUtils.currentUserNo(),
                cmd == null ? null : cmd.type(),
                cmd != null && cmd.reason() != null && !cmd.reason().isBlank(),
                cmd == null || cmd.images() == null ? 0 : cmd.images().size());
        OrdSubOrder sub = ownSubOrder(subOrderNo);
        PhaseOnePointsPolicy.requireCashHistory(sub);

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
        RefundPlan full = BasicRefundPolicy.full(sub, refundQuery.items(sub));
        RefundPlan plan = requestPlanner.planFull(sub, full,
                cmd.items() == null ? null : cmd.items().stream()
                        .map(i -> new RefundLine(i.itemId(), i.qty(), 0L)).toList(),
                cmd.refundMinor(), cmd.ruleReason(), System.currentTimeMillis());
        long refund = plan.totalMinor();

        OrdAfterSale as = new OrdAfterSale();
        as.setAfterSaleNo(BizKey.next(BizKey.AFTER_SALE));
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
        afterSaleMapper.insert(as);
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
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public java.util.Optional<AfterSaleVO> systemRefund(String subOrderNo, String reason, String label) {
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
        DataScopeContext.executeWithoutScope(() -> afterSaleMapper.insert(as));
        appendLog(subOrderNo, OrdAfterSale.REFUNDING, label, OrdStatusLog.BY_SYSTEM, null);
        doRefund(as, label, AfterSaleEvent.TRADE_VOID, Actor.SYSTEM, null);
        return java.util.Optional.of(detailOf(as));
    }

    private java.util.Optional<OrdAfterSale> latestOf(String subOrderNo) {
        return java.util.Optional.ofNullable(DataScopeContext.executeWithoutScope(() ->
                afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getSubOrderNo, subOrderNo)
                        .orderByDesc(OrdAfterSale::getId).last("limit 1"))));
    }

    @Override
    public List<AfterSaleVO> myList() {
        return afterSaleMapper.selectList(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getUserNo, SecurityUtils.currentUserNo())
                        .orderByDesc(OrdAfterSale::getId)).stream()
                .map(this::detailOf).toList();
    }

    @Override
    public AfterSaleVO detail(String afterSaleNo) {
        return detailOf(own(afterSaleNo));
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO cancel(String afterSaleNo) {
        OrdAfterSale as = ownForMutation(afterSaleNo);
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
        return DataScopeContext.executeWithoutScope(() -> afterSaleMapper.selectList(w))
                .stream().map(as -> {
                    AfterSaleVO vo = detailOf(as);
                    // 待处理的才算「同意之后会退回什么」—— 已经处理完的，说了也改变不了什么
                    return OrdAfterSale.APPLIED.equals(as.getStatus())
                            || OrdAfterSale.WAIT_RETURN.equals(as.getStatus())
                            || OrdAfterSale.RETURNING.equals(as.getStatus())
                            ? vo.withImpact(impactOf(as)) : vo;
                }).toList();
    }

    /**
     * 同意这一笔之后会一并退回什么（待办设计 P2c · B 端售后单）。
     *
     * <p>判据与真正退的那一刻（{@link #returnBenefitsIfWholeOrderRefunded}）同一条：
     * 这是主单下<b>最后一笔</b>还在履约中的子单才算整单退；开关关掉的那一项不说。
     * 从现状查：券看它此刻是不是还占在这一单上，分看流水。
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
        long ret = 0L;
        long revoke = 0L;
        if (PhaseOnePointsPolicy.enabled() && pointsPort != null) {
            var impact = pointsPort.pendingImpactOf(
                    siblings.stream().map(OrdSubOrder::getSubOrderNo).toList());
            ret = on(FLAG_RETURN_POINTS) ? impact.refunded() : 0L;
            revoke = on(FLAG_CLAWBACK_EARNED) ? impact.clawedBack() : 0L;
        }
        return new AfterSaleVO.RefundImpact(coupon, ret, revoke);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO approve(String merchantNo, String afterSaleNo, String remark,
                               Long refundMinor, String refundMode) {
        if (OrdAfterSale.REFUND_MODE_POINTS.equals(refundMode)) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);
        PhaseOnePointsPolicy.requireCashHistory(subOrderOf(as.getSubOrderNo()));
        // 说明可空；给了就留下来 —— C 端订单页那句「商家回复」读的就是它
        if (remark != null && !remark.isBlank()) {
            as.setMerchantRemark(remark.trim());
        }

        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        FulfillStage stage = stageOf(sub);

        /*
         * 核定金额（AC9，§2.4）：**只有已签收能定制金额** —— 未签收商家看不到实物损坏比例，
         * 只能全额或驳回。传了金额却未签收 → 拒；金额 ≤0 或超实付 → 拒。核定额落 approved_minor，
         * 与买家申请额分开留痕，实退以它为准。
         */
        if (refundMinor != null) {
            if (!refundMinor.equals(sub.getPayAmount())) {
                throw BizException.of(ErrorCode.AFTERSALE_AMOUNT_INVALID);
            }
            as.setApprovedMinor(refundMinor);
        }
        // 退款形式（AC10）：只认 POINTS，其余（含空）为现金默认
        if (OrdAfterSale.REFUND_MODE_POINTS.equals(refundMode)) {
            as.setRefundMode(OrdAfterSale.REFUND_MODE_POINTS);
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
        if (OrdAfterSale.REFUND_ONLY.equals(as.getType()) && stage == FulfillStage.SHIPPED) {
            transit(as, AfterSaleEvent.APPROVE, Actor.MERCHANT, merchantNo,
                    "商家已同意，货在途——签收后由商家确认退款", AfterSaleContext.builder().inTransit(true).build());
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
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);
        if (as.getReturnReceivedAt() == null) {
            as.setReturnReceivedAt(System.currentTimeMillis());
            as.setRestockApproved(restockApproved);
        }
        doRefund(as, "商家已收到退货", AfterSaleEvent.RETURN_RECEIVED, Actor.MERCHANT, merchantNo);
        return detailOf(as);
    }

    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AfterSaleVO confirmRefund(String merchantNo, String afterSaleNo) {
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);
        doRefund(as, "商家确认退款", AfterSaleEvent.CONFIRM_REFUND, Actor.MERCHANT, merchantNo);
        return detailOf(as);
    }

    /**
     * 签收触发「待收货后退款」（AC7/AC8 出口①）：子单签收/完成时调用，把这单上处于
     * {@link OrdAfterSale#WAIT_RECEIPT} 的仅退款单退掉。**幂等**（无 WAIT_RECEIPT 单就是空操作，
     * Outbox/重试再调也只退一次 —— doRefund 自己也挡 REFUNDED）。系统身份调用，绕数据域。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void settleOnReceipt(String subOrderNo) {
        OrdAfterSale as = DataScopeContext.executeWithoutScope(() -> afterSaleMapper.selectOne(
                Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getSubOrderNo, subOrderNo)
                        .eq(OrdAfterSale::getStatus, OrdAfterSale.WAIT_RECEIPT)
                        .last("limit 1")));
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

    /**
     * <b>退款的唯一实现</b>。顺序是这里唯一重要的东西：
     * <ol>
     *   <li>① 回退分账 —— 失败就停在 REFUNDING 等重试，<b>绝不往下走</b></li>
     *   <li>② 调支付退款</li>
     *   <li>③ 子单转 REFUNDED，发事件（下游据此回补库存、调整评分）</li>
     * </ol>
     * 反过来做的话，钱退了但分账收不回，商家已提现的部分只能人工追。
     */
    private void doRefund(OrdAfterSale as, String label, AfterSaleEvent event, Actor actor, String operatorNo) {
        PhaseOnePointsPolicy.requireCashHistory(subOrderOf(as.getSubOrderNo()));
        if (OrdAfterSale.REFUND_MODE_POINTS.equals(as.getRefundMode())) {
            throw BizException.of(ErrorCode.ORDER_POINTS_MANUAL_REQUIRED);
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
        basicRefunds.prepare(as.getAfterSaleNo(), new OrderActor(as.getEntityNo(), null, operatorNo));
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
        transit(as, AfterSaleEvent.REFUND_SUCCEEDED, Actor.SYSTEM, null, label, AfterSaleContext.builder().build());

        /*
         * **部分退款不改订单状态**（第 5 步）：只有账本说「全部退完」才经门迁到 REFUNDED（O10）。
         * 此前任何一笔退款落定子单就 REFUNDED —— 退一件，买家看到「已退款」。
         */
        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        if (sub != null && fullyRefunded && canFullyRefund(sub)) {
            statusWriter.order(sub, ai.neargo.shop.trade.order.machine.order.OrderEvent.FULLY_REFUNDED,
                    Actor.SYSTEM, null, label, fullRefundContext());
        }
        returnBenefitsIfWholeOrderRefunded(sub);
        eventBus.publish(new OrderEvents.AfterSaleRefunded(as.getAfterSaleNo(), as.getSubOrderNo(),
                as.getUserNo(), cashMinor));
    }

    /**
     * **整单退完才退券**（执行计划 B6，用户 2026-09-21 拍板）。
     *
     * <p>口径是行业通行的那一条：整单退则退券，部分退不退。判据是
     * 「这笔主单下所有子单都不在履约中了」——券是按主单占的（一张券一单），
     * 部分退还要按比例拆券，那是另一套账。
     *
     * <p><b>幂等</b>：{@code release} 只作用于状态仍是 USED 的券，重复调不会退两张。
     *
     * <p><b>抵扣积分同一个判据一起退</b>（待办设计 P2a）：逐子单调 {@code pointsPort.reverse}。
     * 它只认 PENDING 的抵扣流水 —— 分账前的单（绝大多数退款）池子一分没动，退回也不碰池子；
     * 分账后的单那笔已是 CONFIRMED，由 {@code refundConfirmed}（P2b）连池子一起冲 ——
     * 补差收回了才入池，没收回不入池，否则「流通中的积分 == 池子里的钱」会被一笔假入账盖住。
     *
     * <p>两件事各有一个开关（{@value #FLAG_RETURN_COUPON} / {@value #FLAG_RETURN_POINTS}）：
     * 影响的账不同，出问题时要能只关其中一个。
     */
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
        if (PhaseOnePointsPolicy.enabled() && pointsPort != null && on(FLAG_RETURN_POINTS)) {
            for (OrdSubOrder x : siblings) {
                /*
                 * 跨域写推迟到提交之后，与取消订单退分同一个姿势（OrderServiceImpl.cancel）。
                 * 幂等在数据本身（只认 PENDING），取消时已退过的子单这里找不到流水、静默返回。
                 */
                final String subNo = x.getSubOrderNo();
                ai.neargo.shop.event.AfterCommit.run("整单退款退回积分 subOrderNo=" + subNo, () -> {
                    pointsPort.reverse(subNo, "整单退款");
                    // 分账后的单那笔已 CONFIRMED：连池子一起冲（P2b），按状态与上一行互斥
                    pointsPort.refundConfirmed(subNo, "整单退款");
                });
            }
        }
        /*
         * 收回这一单发放的购物积分（待办设计 P2c）。货钱都退了，买它得的分还留着的话，
         * 「买了退、退了买」就能刷分。已转正且花掉的允许扣成负数（points.config 可关）。
         */
        if (PhaseOnePointsPolicy.enabled() && pointsPort != null && on(FLAG_CLAWBACK_EARNED)) {
            for (OrdSubOrder x : siblings) {
                final String subNo = x.getSubOrderNo();
                ai.neargo.shop.event.AfterCommit.run("整单退款收回赠送积分 subOrderNo=" + subNo,
                        () -> pointsPort.revokeEarned(subNo, "整单退款"));
            }
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
        OrdAfterSale as = afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)
                .eq(OrdAfterSale::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
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
                afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)
                        .eq(OrdAfterSale::getEntityNo, merchantNo)
                        .last("limit 1")));
        if (as == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        lockRefundChild(as.getSubOrderNo());
        return DataScopeContext.executeWithoutScope(() -> afterSaleMapper.selectOne(
                Wrappers.<OrdAfterSale>lambdaQuery().eq(OrdAfterSale::getAfterSaleNo, afterSaleNo)
                        .eq(OrdAfterSale::getEntityNo, merchantNo)));
    }

    private OrdAfterSale activeOf(String subOrderNo) {
        return DataScopeContext.executeWithoutScope(() ->
                afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getSubOrderNo, subOrderNo)
                        .notIn(OrdAfterSale::getStatus,
                                List.of(OrdAfterSale.CLOSED, OrdAfterSale.REJECTED, OrdAfterSale.REFUNDED))
                        .last("limit 1")));
    }

    private OrdSubOrder ownSubOrder(String subOrderNo) {
        String currentUser = SecurityUtils.currentUserNo();
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
        OrdAfterSale as = afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getSubOrderNo, subOrderNo)
                .orderByDesc(OrdAfterSale::getId)
                .last("limit 1"));
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

        return new AfterSaleVO(as.getAfterSaleNo(), as.getSubOrderNo(), as.getOrderNo(),
                as.getType(), as.getStatus(), as.getReason(), readList(as.getImages()),
                as.getRefundMinor() == null ? 0L : as.getRefundMinor(),
                Boolean.TRUE.equals(as.getInstant()), as.getMerchantRemark(),
                as.getExpressNo(), as.getLiability(), millis(as.getCreatedAt()),
                // 更新时间：库里有 updated_at，此前没往外发 —— 两个端都按它显示「最后动了什么时候」
                millis(as.getUpdatedAt()), timeline, null,
                as.getRefundMode(), as.getRefundPoints(), as.getApprovedMinor(), as.getFulfillStage(),
                OrdAfterSale.WAIT_RECEIPT.equals(as.getStatus()), escalationOpen(as), refundProgress(as));
    }

    private AfterSaleVO.RefundExecutionProgress refundProgress(OrdAfterSale as) {
        OrdRefundExecution row = basicRefunds.find(as.getAfterSaleNo());
        return row == null ? null : new AfterSaleVO.RefundExecutionProgress(row.getExecutionNo(), row.getPaymentNo(),
                row.getStatus(), row.getPhase(), row.getErrorCode(), row.getResourceStatus(),
                row.getResourceErrorCode(), row.getSucceededAt(), row.getNextAttemptAt());
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
        Long n = DataScopeContext.executeWithoutScope(() ->
                afterSaleMapper.selectCount(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getEntityNo, merchantNo)
                        .in(OrdAfterSale::getStatus, OrdAfterSale.APPLIED, OrdAfterSale.RETURNING)));
        return n == null ? 0 : n.intValue();
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
        return afterSaleMapper.selectList(w).stream().map(this::opsDetailOf).toList();
    }

    @Override
    public OpsAfterSaleVO recoverRefund(String afterSaleNo, String operatorNo) {
        OrdAfterSale as = afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo));
        if (as == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        OrdRefundExecution execution = basicRefunds.find(afterSaleNo);
        if (execution == null || (!OrdAfterSale.REFUNDING.equals(as.getStatus())
                && !OrdAfterSale.REFUNDED.equals(as.getStatus()))) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        basicRefunds.recover(execution.getExecutionNo());
        return opsDetailOf(afterSaleMapper.selectById(as.getId()));
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
                base.createdAt(), base.timeline(), base.refundExecution());
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
            basicRefunds.prepare(as.getAfterSaleNo(), new OrderActor(as.getEntityNo(), null, operatorNo));
        }
        return opsDetailOf(as);
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
        /*
         * **不带数据域**：这是系统巡检，不是某个运营在看列表。
         * 带上的话 worker 里没有 BizContext，查出来的是空集 ——
         * 而空集与「没有卡住的单」在结果上一模一样。
         */
        /*
         * **排除退货退款**（§3 施工时发现的资损口子）。
         *
         * {@code REFUNDING} 这个状态被两件完全不同的事共用：
         * 「退款在途、分账回退失败了等重试」与「商家已同意退货、<b>等买家把货寄回来</b>」
         * （{@link #approve} 对 RETURN_REFUND 就是转到 REFUNDING）。
         * 而这条查询只看状态与时间，于是后者在 30 分钟后被本任务当成「卡住的退款」
         * 捞起来退掉 —— <b>货还没寄回，钱已经退出去了</b>，且全程零报错。
         *
         * 退货退款的时限归 {@code AfterSaleTimeoutJob} 管（寄回 N 天、确认 N 小时），
         * 它那条「已寄回但商家不确认」的分支同时也是这一类的重试入口，所以这里排除掉
         * 不会让任何单子失去续跑的机会。
         *
         * 线上此刻 {@code ord_after_sale} 一行都没有，所以这个口子还没花过钱。
         */
        return DataScopeContext.executeWithoutScope(() ->
                afterSaleMapper.selectList(Wrappers.<OrdAfterSale>lambdaQuery()
                                .eq(OrdAfterSale::getStatus, OrdAfterSale.REFUNDING)
                                .lt(OrdAfterSale::getUpdatedAt, cutoff)
                                .orderByAsc(OrdAfterSale::getUpdatedAt)
                                .last("limit " + Math.max(1, limit)))
                        .stream().map(OrdAfterSale::getAfterSaleNo).toList());
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
                afterSaleMapper.selectList(Wrappers.<OrdAfterSale>lambdaQuery()
                                .eq(OrdAfterSale::getStatus, OrdAfterSale.REFUNDED)
                                .ge(OrdAfterSale::getRefundedAt, since)
                                .eq(OrdAfterSale::getSplitReversed, false)
                                .orderByAsc(OrdAfterSale::getRefundedAt)
                                .last("limit " + Math.max(1, limit)))
                        .stream().map(OrdAfterSale::getAfterSaleNo).toList());
    }

    /**
     * 退款回退分账队列的收尾（P-12.1.5 / E4）。
     *
     * <p>刻意<b>只有三行</b>：查单、校状态、进 {@link #doRefund}。
     * 收尾的每一件事（回退分账 → 退款 → 子单转态 → 发事件）都在那里，
     * 这里再抄一遍的话，漏掉的那几件不会报错。
     */
    @Override
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void resumeRefund(String afterSaleNo, String operatorNo) {
        OrdAfterSale as = lockedSystemAfterSale(afterSaleNo);
        if (as == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if (OrdAfterSale.REFUNDED.equals(as.getStatus())) {
            return;   // 幂等：列表没刷新就再点一次，不能退两次
        }
        /*
         * **只收尾已经判过的单**。APPLIED 的单还没人决定要不要退，
         * 从财务这个入口把它退掉，等于绕过商家与仲裁两道判断 ——
         * 而这个按钮的岗位是财务，不是售后。
         */
        if (!OrdAfterSale.REFUNDING.equals(as.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }
        doRefund(as, "平台执行退款回退分账", AfterSaleEvent.REFUND_SUCCEEDED, Actor.SYSTEM, operatorNo);
        appendLog(as.getSubOrderNo(), as.getStatus(), "财务请求恢复原退款执行",
                OrdStatusLog.BY_PLATFORM, operatorNo);
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
                afterSaleMapper.selectList(q).stream()
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
                afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo).last("limit 1")));
    }

    /** 责任方取值域，与 {@code ord_after_sale.liability} 的注释一致。 */
    private static final java.util.Set<String> LIABILITIES =
            java.util.Set.of("PLATFORM", "MERCHANT", "PICKUP");
}
