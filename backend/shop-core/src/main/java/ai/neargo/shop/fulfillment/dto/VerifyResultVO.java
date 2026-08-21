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
    /**
     * 货还没送到这个点上（子单仍在 {@code WAIT_FULFILL}）。
     *
     * <p><b>与 NOT_THIS_PICKUP 分开</b>：那个是「这单不归你」，店员该让顾客换个点；
     * 这个是「这单归你，但货还没到」，他该让顾客等通知 —— 下一步动作不同。
     *
     * <p>此前没有这一条，未到货的码**核销成功**：邻居代收点上，
     * 货还在路上就被记成「已取货」，之后没有任何人知道它没到
     * （2026-08-17 B 端第二轮实测，用例 TB-B-6-2）。
     */
    public static final String NOT_ARRIVED = "NOT_ARRIVED";

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
