package ai.neargo.shop.spi.fulfillment;

import java.util.List;
import java.util.Optional;

/**
 * 快递代下单通道（TDD-快递100商家寄件）。一期实现是快递100 商家寄件；桩是默认。
 *
 * <p><b>快递公司一律用微信的 {@code delivery_id}</b>（{@code ExpressCompanies}）进出这个接口。
 * 通道自己的公司码只在实现里映射一次 —— 散出去的话，订单上存的码与微信上报的码会对不上。
 */
public interface ExpressPickupPort {

    /** 通道通没通。桩返回 false，调用方据此直说「没开通」，不装成下单成功 */
    boolean enabled();

    /** 这个通道能叫的快递公司（微信码），按展示顺序 */
    List<String> carriers();

    /**
     * 查一家的价。查不到（这家不接这条线、通道超时）返回空，调用方跳过这一家 ——
     * 一家查不到不该让整张报价单打不开。
     */
    Optional<Quote> quote(String carrier, String senderAddress, String receiverAddress, int weightG);

    /** 下单。通道拒单时 {@link Booked#ok()} 为 false，{@link Booked#message()} 是通道原话 */
    Booked create(CreateCmd cmd);

    /** 取消。通道拒绝时返回 false 与原因 */
    Booked cancel(String taskId, String providerOrderId, String reason);

    /**
     * 解析并验签一条回调。<b>验签不过返回空</b> —— 调用方不落库、不回成功。
     */
    Optional<Callback> parseCallback(String taskId, String sign, String param);

    /** @param priceMinor 折后价（分），平台实际要付的；@param listPriceMinor 标准价（分），给商家看省了多少 */
    record Quote(String carrier, long priceMinor, long listPriceMinor) {
    }

    record Party(String name, String mobile, String address) {
    }

    /** @param thirdOrderNo 我方取件单号，通道原样带回，对账用 */
    record CreateCmd(String thirdOrderNo, String carrier, int weightG, String cargo, Party sender, Party receiver) {
    }

    record Booked(boolean ok, String taskId, String orderId, String trackingNo, String message) {
        public static Booked fail(String message) {
            return new Booked(false, null, null, null, message);
        }
    }

    /**
     * @param providerStatus 通道的原始状态码（快递100：0 下单成功 / 10 已取件 / 99 已取消 …）
     * @param chargedWeightG 计费重量（克），没给为空
     * @param freightMinor   折后运费（分），没给为空
     */
    record Callback(String taskId, String orderId, int providerStatus, String trackingNo,
                    Integer chargedWeightG, Long freightMinor, Long listPriceMinor,
                    String courierName, String courierMobile, String message) {
    }
}
