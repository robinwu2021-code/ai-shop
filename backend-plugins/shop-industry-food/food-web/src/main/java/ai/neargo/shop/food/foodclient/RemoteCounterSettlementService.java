package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CounterSettlementContracts.CounterSettlementVO;
import ai.neargo.shop.food.service.CounterSettlementService;
import ai.neargo.shop.food.spi.CounterSettlementInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.List;
import java.util.function.Supplier;

/** 拆分部署：宿主里的柜台结账控制器经内部接口调 food-server。不做传输层重试之外的任何补偿。 */
public class RemoteCounterSettlementService implements CounterSettlementService {
    private final CounterSettlementInternalApi api;

    public RemoteCounterSettlementService(CounterSettlementInternalApi api) {
        this.api = api;
    }

    @Override
    public CounterSettlementVO create(String entity, String store, String actor, String checkNo, String key) {
        var r = new CounterSettlementInternalApi.Create(entity, store, actor, checkNo, key);
        return invoke(() -> api.create(r), CounterSettlementInternalApi.digest(r), null, checkNo);
    }

    @Override
    public CounterSettlementVO finalize(String entity, String store, String actor, String settlementNo,
            List<String> offlineRecordNos) {
        var r = new CounterSettlementInternalApi.Finalize(entity, store, actor, settlementNo, offlineRecordNos);
        return invoke(() -> api.finalizeSettlement(r), CounterSettlementInternalApi.digest(r), settlementNo, null);
    }

    @Override
    public CounterSettlementVO cancel(String entity, String store, String actor, String settlementNo) {
        var r = new CounterSettlementInternalApi.Reference(entity, store, actor, settlementNo);
        return invoke(() -> api.cancel(r), CounterSettlementInternalApi.digest("cancel", r), settlementNo, null);
    }

    @Override
    public CounterSettlementVO get(String entity, String store, String settlementNo) {
        var r = new CounterSettlementInternalApi.Reference(entity, store, null, settlementNo);
        return invoke(() -> api.get(r), CounterSettlementInternalApi.digest("get", r), settlementNo, null);
    }

    private static CounterSettlementVO invoke(Supplier<CounterSettlementVO> operation, String digest,
            String settlementNo, String checkNo) {
        try {
            var result = ServiceCalls.call(ServiceName.FOOD, operation::get);
            if (result == null || result.settlementNo() == null || result.status() == null || result.dueMinor() < 0
                    || settlementNo != null && !settlementNo.equals(result.settlementNo())
                    || checkNo != null && !checkNo.equals(result.checkNo())) {
                throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
            }
            return result;
        } catch (ServiceCallException failure) {
            BizException mapped = business(failure, digest);
            if (mapped != null) {
                throw mapped;
            }
            throw failure;
        }
    }

    static BizException business(ServiceCallException failure, String digest) {
        boolean bound = failure.remoteErrorCode() != null && digest.equals(failure.requestDigest());
        String remote = bound ? failure.remoteErrorCode() : "";
        return switch (remote) {
            case "POSTPAY_RECEIPT_UNDERPAID" -> BizException.of(ErrorCode.POSTPAY_RECEIPT_UNDERPAID, "—");
            case "POSTPAY_RECEIPT_OVERPAID_NON_CASH" -> BizException.of(ErrorCode.POSTPAY_RECEIPT_OVERPAID_NON_CASH);
            case "POSTPAY_RECEIPT_RECORD_INVALID" -> BizException.of(ErrorCode.POSTPAY_RECEIPT_RECORD_INVALID);
            case "POSTPAY_RECEIPT_ORDER_CHANGED" -> BizException.of(ErrorCode.POSTPAY_RECEIPT_ORDER_CHANGED);
            case "COUNTER_SETTLEMENT_NOT_READY" -> BizException.of(ErrorCode.COUNTER_SETTLEMENT_NOT_READY);
            case "COUNTER_SETTLEMENT_SPLIT_LINES" -> BizException.of(ErrorCode.COUNTER_SETTLEMENT_SPLIT_LINES);
            case "COUNTER_SETTLEMENT_CONFIRMED" -> BizException.of(ErrorCode.COUNTER_SETTLEMENT_CONFIRMED);
            default -> switch (failure.statusCode()) {
                case 400 -> BizException.of(ErrorCode.BAD_REQUEST);
                case 403 -> BizException.of(ErrorCode.FORBIDDEN);
                case 404 -> BizException.of(ErrorCode.NOT_FOUND);
                case 409 -> BizException.of(ErrorCode.CONFLICT);
                case 503 -> BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
                default -> null;
            };
        };
    }
}
