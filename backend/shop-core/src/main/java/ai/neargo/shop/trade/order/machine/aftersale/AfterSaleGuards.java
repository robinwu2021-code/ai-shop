package ai.neargo.shop.trade.order.machine.aftersale;

import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.statemachine.Guard;

import java.util.Optional;

/** 售后守卫：每个一行、各报各的码。规则变了改这里，不碰迁移表。 */
final class AfterSaleGuards {

    static final Guard<AfterSaleContext> AUTHORIZED = Guard.of(c -> c.authorized, ErrorCode.FORBIDDEN);
    static final Guard<AfterSaleContext> APPLY_RULE = c -> Optional.ofNullable(c.applyRuleError);
    static final Guard<AfterSaleContext> IN_WINDOW = Guard.of(c -> c.inWindow, ErrorCode.AFTERSALE_WINDOW_CLOSED);
    static final Guard<AfterSaleContext> NO_OPEN = Guard.of(c -> c.noOpenAfterSale, ErrorCode.CONFLICT);
    static final Guard<AfterSaleContext> REFUNDABLE =
            Guard.of(c -> c.amountRefundable, ErrorCode.AFTERSALE_AMOUNT_INVALID);
    static final Guard<AfterSaleContext> CHANNEL_OK =
            Guard.of(c -> c.channelSupports, ErrorCode.AFTERSALE_CHANNEL_PARTIAL_UNSUPPORTED);
    static final Guard<AfterSaleContext> REASON = Guard.of(c -> c.reasonValid, ErrorCode.BAD_REQUEST);
    static final Guard<AfterSaleContext> OFFER_VALID = Guard.of(c -> c.offerValid, ErrorCode.AFTERSALE_AMOUNT_INVALID);
    static final Guard<AfterSaleContext> REMARK = Guard.of(c -> c.remarkPresent, ErrorCode.BAD_REQUEST);
    static final Guard<AfterSaleContext> ESCALATION_OPEN =
            Guard.of(c -> c.escalationOpen, ErrorCode.ORDER_STATE_ILLEGAL);
    static final Guard<AfterSaleContext> EXPIRED = Guard.of(c -> c.expired, ErrorCode.ORDER_STATE_ILLEGAL);
    static final Guard<AfterSaleContext> WAYBILL = Guard.of(c -> c.waybillValid, ErrorCode.BAD_REQUEST);
    static final Guard<AfterSaleContext> PROOF = Guard.of(c -> c.proofPresent, ErrorCode.BAD_REQUEST);
    static final Guard<AfterSaleContext> VOID_REASON = Guard.of(c -> c.voidReasonValid, ErrorCode.BAD_REQUEST);
    static final Guard<AfterSaleContext> PREPARATION_NOT_STARTED =
            Guard.of(c -> c.preparationNotStarted, ErrorCode.ORDER_STATE_ILLEGAL);

    private AfterSaleGuards() {
    }
}
