package ai.neargo.shop.trade.dto;

import java.util.List;

/**
 * 商家端订单的完整详情（TDD-B端收款订单与商城接口对齐 P2，B2）：{@link OrderVO} 原样放在 {@code order} 里，
 * 外加商家处理订单要看、而 {@link OrderVO} 没有的几样。
 *
 * <p><b>为什么不往 OrderVO 里加字段</b>：OrderVO 是 C 端与 B 端共用的形状，几十个位置参数、多处位置构造；
 * 往里插字段要改的地方多且未必报错。商家端独有的东西放在这一层，C 端一个字节都不多拿。
 *
 * @param currency 主单币种（OrderVO.amount.currency 同步改为取它，不再写死 CNY）
 * @param remark   顾客下单时的备注
 * @param payments 这单的付款，一条一笔：线上（渠道、渠道交易号）或线下（确认人）
 * @param operations 状态流水，按时间正序
 * @param invoice  顾客申请过发票才有
 */
public record MerchantOrderFullVO(OrderVO order, String currency, String remark, Customer customer, Source source,
                                  List<Payment> payments, List<Operation> operations, Invoice invoice) {

    /** @param phone 口径同 OrderVO.receiver：自送 / 快递给全号，其余后四位 */
    public record Customer(String nickname, String receiverName, String phone, String salutation) {
    }

    /** @param caller RETAIL / FOOD / BEAUTY；存量单可能为空（按零售） */
    public record Source(String caller, String trafficSource, String admissionKind, String payScene) {
    }

    /**
     * @param kind          ONLINE / OFFLINE
     * @param payDocumentNo 主单号（支付根）；线上单在「收款」里可用 channelTradeNo 搜到对应支付订单
     * @param confirmedBy   线下收款的确认人
     */
    public record Payment(String kind, String channel, long amountMinor, Long at, String channelTradeNo,
                          String payDocumentNo, String confirmedBy) {
    }

    public record Operation(long at, String event, String fromStatus, String status, String label,
                            String operatorType, String operatorNo) {
    }

    public record Invoice(String requestNo, String status, String titleType, String title, String invoiceNo,
                          Long issuedAt, long amountMinor) {
    }
}
