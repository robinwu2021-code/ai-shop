package ai.neargo.shop.fulfillment.dto;

/**
 * 核销结果。
 *
 * <p><b>失败必须给出具体原因</b>（B-10.2.5）：店主站在货架前，
 * 「核销失败」四个字没法让他决定下一步 —— 是让顾客去别的点、
 * 还是告诉他已经取过了、还是这单已经退款了，处理方式完全不同。
 */
public record VerifyResultVO(boolean success, String subOrderNo, String reason) {

    public static final String ALREADY_VERIFIED = "ALREADY_VERIFIED";
    public static final String NOT_THIS_PICKUP = "NOT_THIS_PICKUP";
    public static final String REFUNDED = "REFUNDED";
    public static final String NOT_FOUND = "CODE_NOT_FOUND";
    public static final String NOT_PAID = "NOT_PAID";

    public static VerifyResultVO ok(String subOrderNo) {
        return new VerifyResultVO(true, subOrderNo, null);
    }

    public static VerifyResultVO fail(String reason) {
        return new VerifyResultVO(false, null, reason);
    }

    /** 失败但已知是哪一单（如非本点、已核销）—— 留痕时带上单号，纠纷时才查得到。 */
    public static VerifyResultVO fail(String reason, String subOrderNo) {
        return new VerifyResultVO(false, subOrderNo, reason);
    }
}
