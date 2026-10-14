package ai.neargo.shop.trade.order.machine.aftersale;

import ai.neargo.shop.trade.order.statemachine.StateMachine;

import java.util.Set;

import static ai.neargo.shop.trade.order.machine.aftersale.AfterSaleEvent.*;
import static ai.neargo.shop.trade.order.machine.aftersale.AfterSaleGuards.*;
import static ai.neargo.shop.trade.order.machine.aftersale.AfterSaleStatus.*;
import static ai.neargo.shop.trade.order.statemachine.Actor.*;

/**
 * 售后单状态机（订单状态机-完整方案 §5.2）。
 *
 * <p><b>退款永不自动</b>：进入「退钱」的迁移只由商家、平台、或买家接受商家的协商额触发；
 * 系统唯一的例外是 {@link AfterSaleEvent#TRADE_VOID}（交易不成立的原路退回）。由 {@code MachineInvariantsTest} 锁死。
 */
public final class AfterSaleMachine {

    public static final StateMachine<AfterSaleStatus, AfterSaleEvent, AfterSaleContext> MACHINE =
            StateMachine.<AfterSaleStatus, AfterSaleEvent, AfterSaleContext>builder("after-sale")
                    // A1 买家申请
                    .create().on(APPLY).by(BUYER).when(AUTHORIZED, IN_WINDOW, NO_OPEN, REFUNDABLE, REASON, APPLY_RULE)
                    .to(APPLIED).emit("AfterSaleApplied").add()
                    // A2 商家主动退款（缺货少发 / 称重差价 / 补偿 …）
                    .create().on(MERCHANT_INITIATE).by(MERCHANT).when(AUTHORIZED, REASON, REFUNDABLE, CHANNEL_OK)
                    .toOneOf(AfterSaleTargets.MONEY, AfterSaleTargets::moneyStep).emit("AfterSaleInitiated").add()
                    // A3 交易不成立（拼团未成团 / 集单撤单 / 关单后迟到回调）—— 系统唯一可退钱的入口
                    .create().on(TRADE_VOID).by(SYSTEM).when(VOID_REASON).to(REFUNDING).emit("TradeVoided").add()
                    // A4 商家同意（分流见 AfterSaleTargets.approve）
                    .from(APPLIED).on(APPROVE).by(MERCHANT).when(AUTHORIZED, CHANNEL_OK)
                    .toOneOf(AfterSaleTargets.APPROVE_TARGETS, AfterSaleTargets::approve).emit("AfterSaleApproved").add()
                    // A5 商家提出协商金额
                    .from(APPLIED).on(OFFER).by(MERCHANT).when(AUTHORIZED, OFFER_VALID, CHANNEL_OK).to(NEGOTIATING)
                    .emit("AfterSaleOffered").add()
                    // A6 买家接受协商额（决定是商家做的，买家只是接受）
                    .from(NEGOTIATING).on(ACCEPT_OFFER).by(BUYER).when(AUTHORIZED)
                    .toOneOf(AfterSaleTargets.ACCEPT_TARGETS, AfterSaleTargets::refundOrAwaitReturn)
                    .emit("AfterSaleOfferAccepted").add()
                    // A7 买家拒绝 / 48h 未答 → 回到待商家处理
                    .from(NEGOTIATING).on(DECLINE_OFFER).by(BUYER).when(AUTHORIZED).to(APPLIED).add()
                    .from(NEGOTIATING).on(OFFER_EXPIRED).by(SYSTEM).when(EXPIRED).to(APPLIED).add()
                    // A8 商家驳回（理由必填）
                    .from(APPLIED).on(REJECT).by(MERCHANT).when(AUTHORIZED, REMARK).to(REJECTED)
                    .emit("AfterSaleRejected").add()
                    // A9 在途仅退款签收后，商家一键确认退款（不自动）
                    .from(WAIT_RECEIPT).on(CONFIRM_REFUND).by(MERCHANT).when(AUTHORIZED)
                    .toOneOf(AfterSaleTargets.MONEY, AfterSaleTargets::moneyStep).emit("AfterSaleRefundConfirmed").add()
                    // A10–A12 退货：买家寄回 → 商家确认收货 → 退钱；7 天不寄回关闭
                    .from(WAIT_RETURN).on(RETURN_SHIPPED).by(BUYER).when(AUTHORIZED, WAYBILL).to(RETURNING)
                    .emit("AfterSaleReturnShipped").add()
                    .from(WAIT_RETURN).on(RETURN_EXPIRED).by(SYSTEM).when(EXPIRED).to(CLOSED).add()
                    .from(RETURNING).on(RETURN_RECEIVED).by(MERCHANT).when(AUTHORIZED)
                    .toOneOf(AfterSaleTargets.MONEY, AfterSaleTargets::moneyStep).emit("AfterSaleReturnReceived").add()
                    // A13 / A14 退款回执（执行结果，不是决定）
                    .from(REFUNDING).on(REFUND_SUCCEEDED).by(CHANNEL, SYSTEM).to(REFUNDED).emit("AfterSaleRefunded").add()
                    .from(REFUNDING).on(REFUND_FAILED).by(CHANNEL).stay().emit("AfterSaleRefundFailed").add()
                    // A15 申请平台介入（只能由买家 / 商家发起，系统不自动转入）
                    .from(APPLIED, NEGOTIATING, WAIT_RETURN, RETURNING, WAIT_RECEIPT, REJECTED,
                            WAIT_OFFLINE_REFUND, OFFLINE_REFUNDED)
                    .on(ESCALATE).by(BUYER, MERCHANT).when(AUTHORIZED, ESCALATION_OPEN).to(ARBITRATING)
                    .emit("AfterSaleEscalated").add()
                    // A16 / A17 平台裁决
                    .from(ARBITRATING).on(ARBITRATE_REFUND).by(PLATFORM).when(AUTHORIZED, REFUNDABLE, CHANNEL_OK)
                    .toOneOf(AfterSaleTargets.ACCEPT_TARGETS, AfterSaleTargets::refundOrAwaitReturn)
                    .emit("AfterSaleArbitrated").add()
                    .from(ARBITRATING).on(ARBITRATE_CLOSE).by(PLATFORM).when(AUTHORIZED, REMARK).to(CLOSED)
                    .emit("AfterSaleArbitrated").add()
                    // A18 买家撤销（钱已在路上不能撤）
                    .from(APPLIED, NEGOTIATING, WAIT_RETURN, REJECTED).on(WITHDRAW).by(BUYER).when(AUTHORIZED)
                    .to(CLOSED).emit("AfterSaleWithdrawn").add()
                    // A19 驳回后 7 天未申请介入
                    .from(REJECTED).on(ESCALATION_WINDOW_EXPIRED).by(SYSTEM).when(EXPIRED).to(CLOSED).add()
                    // A20 / A21 线下付款单：商家线下退款登记 → 买家确认收到
                    .from(WAIT_OFFLINE_REFUND).on(OFFLINE_REFUND_REGISTERED).by(MERCHANT).when(AUTHORIZED, PROOF)
                    .to(OFFLINE_REFUNDED).emit("AfterSaleOfflineRefunded").add()
                    .from(OFFLINE_REFUNDED).on(OFFLINE_REFUND_ACKED).by(BUYER).when(AUTHORIZED).to(REFUNDED)
                    .emit("AfterSaleRefunded").add()
                    .build();

    public static final Set<AfterSaleStatus> TERMINALS = Set.of(REFUNDED, CLOSED);

    /** 「钱开始走」的状态：进入它们的迁移受「退款永不自动」约束 */
    public static final Set<AfterSaleStatus> MONEY_MOVING = Set.of(REFUNDING, WAIT_OFFLINE_REFUND, OFFLINE_REFUNDED);

    private AfterSaleMachine() {
    }
}
