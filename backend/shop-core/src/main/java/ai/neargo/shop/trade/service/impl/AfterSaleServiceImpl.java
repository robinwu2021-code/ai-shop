package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.trade.service.AfterSaleService;
import ai.neargo.shop.trade.service.OrderStateMachine;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.OrderEvents;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.trade.dto.AfterSaleVO;
import ai.neargo.shop.trade.dto.OpsAfterSaleVO;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.spi.product.StockPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
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

    /** 三种售后类型。与 {@link OrdAfterSale} 的常量同源，不在这里另写字符串 */
    private static final java.util.Set<String> AFTER_SALE_TYPES = java.util.Set.of(
            OrdAfterSale.REFUND_ONLY, OrdAfterSale.RETURN_REFUND, OrdAfterSale.EXCHANGE);


    /** 极速退阈值：≤ 该金额自动通过。真实阈值由 P-6.1.2 运营配置，这里是缺省。 */
    @Value("${shop.after-sale.instant-threshold-minor:10000}")
    private long instantThresholdMinor;

    /**
     * 售后原因。**下发的是码，不是文案** —— 这是个三语 App（zh/en/ar），
     * 下发中文文案等于把翻译这件事从端上剥夺掉，英文用户会看到一串中文。
     *
     * <p>此前这里是中文字面量，而 c-app 压根没调这个接口、自己硬编码了另一份**六个码**的清单 ——
     * 两份清单各自漂移，运营改后端这份，端上纹丝不动。
     */
    private static final List<String> REASONS = List.of(
            "NOT_WANTED", "DAMAGED", "MISSING", "WRONG_ITEM", "QUALITY", "EXPIRED", "OTHER");

    private final AfterSaleMapper afterSaleMapper;
    private final SubOrderMapper subOrderMapper;
    private final StatusLogMapper statusLogMapper;
    private final SettlePort settlePort;
    private final OutboxEventBus eventBus;
    private final ObjectMapper json;
    /** 仲裁台要看「谁的店、谁买的」——消费者自己的售后单不需要这两个 Port */
    private final MerchantQueryPort merchantPort;
    private final UserQueryPort userPort;
    private final StockPort stockPort;
    private final OrderItemMapper orderItemMapper;

    public AfterSaleServiceImpl(AfterSaleMapper afterSaleMapper, SubOrderMapper subOrderMapper,
                                StatusLogMapper statusLogMapper, SettlePort settlePort,
                                OutboxEventBus eventBus, ObjectMapper json,
                                MerchantQueryPort merchantPort, UserQueryPort userPort,
                                StockPort stockPort, OrderItemMapper orderItemMapper) {
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

    @Override
    public List<String> reasons() {
        return REASONS;
    }

    // ---------------------------------------------------------------- C 端

    @Override
    @Transactional
    public AfterSaleVO apply(String subOrderNo, ApplyCommand cmd) {
        OrdSubOrder sub = ownSubOrder(subOrderNo);

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
                || cmd.type() == null || !AFTER_SALE_TYPES.contains(cmd.type())) {
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

        long paid = sub.getPayAmount() == null ? 0L : sub.getPayAmount();
        long refund = cmd.refundMinor() == null || cmd.refundMinor() <= 0 ? paid : cmd.refundMinor();
        if (refund > paid) {
            // 退款金额超过实付：这是最直接的资损口子，必须在入口就挡住
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }

        OrdAfterSale as = new OrdAfterSale();
        as.setAfterSaleNo(BizKey.next(BizKey.AFTER_SALE));
        as.setSubOrderNo(subOrderNo);
        as.setOrderNo(sub.getOrderNo());
        as.setUserNo(sub.getUserNo());
        as.setEntityNo(sub.getEntityNo());
        as.setType(cmd.type());
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
        as.setStatus(platformIsSeller ? OrdAfterSale.ARBITRATING : OrdAfterSale.APPLIED);
        as.setSplitReversed(false);

        // 极速退：仅退款且金额在阈值内 → 自动通过。退货退款要等收到货，不能自动
        boolean instant = OrdAfterSale.REFUND_ONLY.equals(cmd.type()) && refund <= instantThresholdMinor;
        as.setInstant(instant);
        afterSaleMapper.insert(as);
        // 日志要写**真实去向**：自营单没有「等商家处理」这一步，
        // 写成一样的话，用户看时间线会以为在等商家，而实际在等平台
        appendLog(subOrderNo, as.getStatus(),
                platformIsSeller ? "已申请售后，由平台直接处理" : "已申请售后",
                OrdStatusLog.BY_USER, sub.getUserNo());

        // B-N-2：商家越早看到售后越可能协商解决。自营单也发 —— 收件的是平台商户的员工
        eventBus.publish(new OrderEvents.AfterSaleApplied(as.getAfterSaleNo(), subOrderNo,
                sub.getEntityNo(), sub.getUserNo(), cmd.type(), refund));

        if (instant) {
            doRefund(as, "极速退");
        }
        return detailOf(as);
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
    @Transactional
    public AfterSaleVO cancel(String afterSaleNo) {
        OrdAfterSale as = own(afterSaleNo);
        OrderStateMachine.assertAfterSaleTransit(as.getStatus(), OrdAfterSale.CLOSED);
        as.setStatus(OrdAfterSale.CLOSED);
        afterSaleMapper.updateById(as);
        appendLog(as.getSubOrderNo(), OrdAfterSale.CLOSED, "用户撤销申请",
                OrdStatusLog.BY_USER, as.getUserNo());
        return detailOf(as);
    }

    @Override
    @Transactional
    public AfterSaleVO shipBack(String afterSaleNo, String company, String expressNo) {
        OrdAfterSale as = own(afterSaleNo);
        as.setExpressCompany(company);
        as.setExpressNo(expressNo);
        afterSaleMapper.updateById(as);
        appendLog(as.getSubOrderNo(), as.getStatus(), "买家已寄回：" + expressNo,
                OrdStatusLog.BY_USER, as.getUserNo());
        return detailOf(as);
    }

    @Override
    @Transactional
    public AfterSaleVO escalate(String afterSaleNo, String appeal) {
        OrdAfterSale as = own(afterSaleNo);
        OrderStateMachine.assertAfterSaleTransit(as.getStatus(), OrdAfterSale.ARBITRATING);
        as.setStatus(OrdAfterSale.ARBITRATING);
        afterSaleMapper.updateById(as);
        appendLog(as.getSubOrderNo(), OrdAfterSale.ARBITRATING, "已申请平台介入：" + appeal,
                OrdStatusLog.BY_USER, as.getUserNo());
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
                .stream().map(this::detailOf).toList();
    }

    @Override
    @Transactional
    public AfterSaleVO approve(String merchantNo, String afterSaleNo, String remark) {
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);
        // 说明可空；给了就留下来 —— C 端订单页那句「商家回复」读的就是它
        if (remark != null && !remark.isBlank()) {
            as.setMerchantRemark(remark.trim());
        }

        if (OrdAfterSale.RETURN_REFUND.equals(as.getType())) {
            // 退货退款：同意 ≠ 立刻退钱，要等收到货。这里只推进到「等待寄回」
            OrderStateMachine.assertAfterSaleTransit(as.getStatus(), OrdAfterSale.REFUNDING);
            as.setStatus(OrdAfterSale.REFUNDING);
            update(as);
            appendLog(as.getSubOrderNo(), OrdAfterSale.REFUNDING, "商家已同意，待买家寄回",
                    OrdStatusLog.BY_MERCHANT, merchantNo);
            return detailOf(as);
        }
        doRefund(as, "商家同意退款");
        return detailOf(as);
    }

    @Override
    @Transactional
    public AfterSaleVO reject(String merchantNo, String afterSaleNo, String remark) {
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);
        // 极速退已经是终态，商家只可见不可拒 —— 状态机在这里挡住
        OrderStateMachine.assertAfterSaleTransit(as.getStatus(), OrdAfterSale.REJECTED);
        as.setStatus(OrdAfterSale.REJECTED);
        as.setMerchantRemark(remark);
        update(as);
        appendLog(as.getSubOrderNo(), OrdAfterSale.REJECTED, "商家驳回：" + remark,
                OrdStatusLog.BY_MERCHANT, merchantNo);
        return detailOf(as);
    }

    @Override
    @Transactional
    public AfterSaleVO confirmReturn(String merchantNo, String afterSaleNo) {
        OrdAfterSale as = ofMerchant(merchantNo, afterSaleNo);
        doRefund(as, "商家已收到退货");
        return detailOf(as);
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
    private void doRefund(OrdAfterSale as, String label) {
        if (OrdAfterSale.REFUNDED.equals(as.getStatus())) {
            return;   // 幂等：重复点「同意」不会退两次
        }
        if (!OrdAfterSale.REFUNDING.equals(as.getStatus())) {
            OrderStateMachine.assertAfterSaleTransit(as.getStatus(), OrdAfterSale.REFUNDING);
            as.setStatus(OrdAfterSale.REFUNDING);
            update(as);
        }

        // ① 先回退分账
        if (!settlePort.reverseSplit(as.getSubOrderNo())) {
            appendLog(as.getSubOrderNo(), OrdAfterSale.REFUNDING, "分账回退失败，待重试",
                    OrdStatusLog.BY_SYSTEM, null);
            throw BizException.of(ErrorCode.SPLIT_EXPIRED);
        }
        as.setSplitReversed(true);

        /*
         * ② 再退款，**并把退款流水号记下来**（S8 · 2026-09-02）。
         *
         * 此前这里丢弃了返回值 —— 而 refund 本身也只打了一行日志、
         * 返回一个编造的号。两边加起来的效果是：<b>一笔退款发生之后，
         * 资金侧没有任何东西记得它</b>，而售后单上的 refund_payment_no
         * 这个字段只声明、从没被赋值过。
         *
         * 拿不到流水号不阻断退款：那说明原收款流水找不到（存量单、
         * 或者钱本来就没收到），而<b>此时更不该把用户的退款卡住</b> ——
         * 缺的那一行是账，账可以补，用户的钱不能不退。记 WARN 让人能查。
         */
        String refundPaymentNo = settlePort.refund(
                as.getSubOrderNo(), as.getRefundMinor(), as.getReason());
        if (refundPaymentNo == null) {
            /*
             * 留痕落在**售后单的时间线**上，不只写日志 ——
             * 日志会滚掉，而客服查这张单时看到的是时间线。
             */
            appendLog(as.getSubOrderNo(), OrdAfterSale.REFUNDING,
                    "退款已执行，但没能落下资金流水（原收款找不到）——"
                            + "这一笔不在对账范围内，需人工补",
                    OrdStatusLog.BY_SYSTEM, null);
        } else {
            as.setRefundPaymentNo(refundPaymentNo);
        }

        // ③ 退货类的把货加回来
        restoreStockIfReturned(as);

        // ④ 落终态。**回补的标记与状态一起写** —— 分两次写的话，
        //    中间挂掉会让重试再补一次，而多出来的那几件不会有任何地方报错
        as.setStatus(OrdAfterSale.REFUNDED);
        as.setRefundedAt(System.currentTimeMillis());
        update(as);

        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        if (sub != null && OrderStateMachine.canTransit(OrderStateMachine.subOrderGraph(),
                sub.getStatus(), OrdSubOrder.REFUNDED)) {
            sub.setStatus(OrdSubOrder.REFUNDED);
            DataScopeContext.executeWithoutScope(() -> subOrderMapper.updateById(sub));
        }
        appendLog(as.getSubOrderNo(), OrdAfterSale.REFUNDED, label, OrdStatusLog.BY_SYSTEM, null);
        eventBus.publish(new OrderEvents.AfterSaleRefunded(as.getAfterSaleNo(), as.getSubOrderNo(),
                as.getUserNo(), as.getRefundMinor()));
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
    private void restoreStockIfReturned(OrdAfterSale as) {
        if (!OrdAfterSale.RETURN_REFUND.equals(as.getType())
                || Integer.valueOf(1).equals(as.getStockRestored())) {
            return;
        }
        OrdSubOrder sub = subOrderOf(as.getSubOrderNo());
        List<OrdItem> items = DataScopeContext.executeWithoutScope(() ->
                orderItemMapper.selectList(Wrappers.<OrdItem>lambdaQuery()
                        .eq(OrdItem::getSubOrderNo, as.getSubOrderNo())));
        if (items.isEmpty()) {
            return;
        }
        List<StockPort.SkuQty> lines = new ArrayList<>();
        for (OrdItem it : items) {
            lines.add(new StockPort.SkuQty(it.getSkuNo(), it.getQty(),
                    sub == null ? null : sub.getStoreNo()));
        }
        stockPort.restore(as.getAfterSaleNo(), lines);
        as.setStockRestored(1);
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
        return as;
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
        OrdSubOrder sub = subOrderMapper.selectOne(Wrappers.<OrdSubOrder>lambdaQuery()
                .eq(OrdSubOrder::getSubOrderNo, subOrderNo)
                .eq(OrdSubOrder::getUserNo, SecurityUtils.currentUserNo())
                .last("limit 1"));
        if (sub == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return sub;
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
            OrdAfterSale.APPLIED, OrdAfterSale.REFUNDING, OrdAfterSale.REFUNDED,
            OrdAfterSale.REJECTED, OrdAfterSale.ARBITRATING, OrdAfterSale.CLOSED);

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
                millis(as.getUpdatedAt()), timeline);
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
                        .eq(OrdAfterSale::getStatus, OrdAfterSale.APPLIED)));
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
                base.createdAt(), base.timeline());
    }

    @Override
    @Transactional
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
        OrdAfterSale as = DataScopeContext.executeWithoutScope(() ->
                afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo).last("limit 1")));
        if (as == null) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        // 只能裁「已上升到平台」的单：还没上升就裁，等于替商家做了他还没做的决定
        if (!OrdAfterSale.ARBITRATING.equals(as.getStatus())) {
            throw BizException.of(ErrorCode.ORDER_STATE_ILLEGAL);
        }

        String target = refund ? OrdAfterSale.REFUNDING : OrdAfterSale.CLOSED;
        OrderStateMachine.assertAfterSaleTransit(as.getStatus(), target);
        as.setStatus(target);
        as.setLiability(liability);
        DataScopeContext.executeWithoutScope(() -> afterSaleMapper.updateById(as));
        appendLog(as.getSubOrderNo(), target,
                (refund ? "平台裁决：支持退款。" : "平台裁决：维持商家决定。") + verdict.trim(),
                OrdStatusLog.BY_PLATFORM, operatorNo);
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
    @Transactional
    public void resumeRefund(String afterSaleNo, String operatorNo) {
        OrdAfterSale as = DataScopeContext.executeWithoutScope(() ->
                afterSaleMapper.selectOne(Wrappers.<OrdAfterSale>lambdaQuery()
                        .eq(OrdAfterSale::getAfterSaleNo, afterSaleNo).last("limit 1")));
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
        doRefund(as, "平台执行退款回退分账");
        appendLog(as.getSubOrderNo(), OrdAfterSale.REFUNDED, "财务执行：先回退分账，再退款",
                OrdStatusLog.BY_PLATFORM, operatorNo);
    }

    /** 责任方取值域，与 {@code ord_after_sale.liability} 的注释一致。 */
    private static final java.util.Set<String> LIABILITIES =
            java.util.Set.of("PLATFORM", "MERCHANT", "PICKUP");
}
