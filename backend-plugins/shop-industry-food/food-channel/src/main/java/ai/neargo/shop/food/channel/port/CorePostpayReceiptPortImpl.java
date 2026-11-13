package ai.neargo.shop.food.channel.port;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.PostpayReceiptInternalApi;
import ai.neargo.shop.spi.trade.PostpayReceiptPort;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.function.Supplier;

/**
 * 独立部署（food-server）调宿主核心确认后付实收。
 *
 * <p>核心的业务码按名字原样回译，让收银台能区分「还差钱」「多收了非现金」「账单变了」；
 * 「还差」的金额参数不跨服务传递，这里以「—」代替（嵌入部署直接拿到核心算出的差额）。
 */
public class CorePostpayReceiptPortImpl implements PostpayReceiptPort {
    private final Supplier<PostpayReceiptInternalApi> api;

    public CorePostpayReceiptPortImpl(Supplier<PostpayReceiptInternalApi> api) {
        this.api = api;
    }

    @Override
    public Receipt confirm(Confirm command) {
        try {
            Receipt value = ServiceCalls.call(ServiceName.PLATFORM, () -> api.get().confirm(command));
            if (value == null || value.receiptNo() == null || !command.bizNo().equals(value.bizNo())
                    || value.receivedMinor() < value.dueMinor() || value.changeMinor() < 0) {
                throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
            }
            return value;
        } catch (ServiceCallException failure) {
            BizException mapped = business(failure, PostpayReceiptPort.digest(command));
            if (mapped != null) throw mapped;
            throw failure;
        }
    }

    static BizException business(ServiceCallException failure, String digest) {
        // 只有摘要对得上才采信远端业务码，防止把别的请求的错误安到这次头上
        boolean bound = failure.remoteErrorCode() != null && digest.equals(failure.requestDigest());
        String remote = bound ? failure.remoteErrorCode() : "";
        return switch (remote) {
            case "POSTPAY_RECEIPT_UNDERPAID" -> BizException.of(ErrorCode.POSTPAY_RECEIPT_UNDERPAID, "—");
            case "POSTPAY_RECEIPT_OVERPAID_NON_CASH" -> BizException.of(ErrorCode.POSTPAY_RECEIPT_OVERPAID_NON_CASH);
            case "POSTPAY_RECEIPT_RECORD_INVALID" -> BizException.of(ErrorCode.POSTPAY_RECEIPT_RECORD_INVALID);
            case "POSTPAY_RECEIPT_ORDER_CHANGED" -> BizException.of(ErrorCode.POSTPAY_RECEIPT_ORDER_CHANGED);
            default -> switch (failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                default -> null;
            };
        };
    }
}
