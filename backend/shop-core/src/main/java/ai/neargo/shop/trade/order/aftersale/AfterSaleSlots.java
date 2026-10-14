package ai.neargo.shop.trade.order.aftersale;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.order.mapper.AfterSaleSlotMapper;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 「一张订单同时只有一张进行中售后」的库内保证（V406，集群规则 R1 同款 CAS）。
 *
 * <p>申请 / 被驳回后申请介入时占位，进 CLOSED / REJECTED / REFUNDED 时释放。
 * 两个实例同时收到同一单的两次申请：只有一个能占到，另一个 CONFLICT 回滚 —— 先查后插做不到这一点。
 */
@Component
public class AfterSaleSlots {

    /** 进了这些状态，这张售后就不再占着订单（与 activeOf 的口径一致） */
    public static final Set<String> RELEASING = Set.of(OrdAfterSale.CLOSED, OrdAfterSale.REJECTED, OrdAfterSale.REFUNDED);

    private final AfterSaleSlotMapper mapper;

    public AfterSaleSlots(AfterSaleSlotMapper mapper) {
        this.mapper = mapper;
    }

    /** 占不到（已有别的进行中售后）→ CONFLICT */
    public void occupy(String subOrderNo, String afterSaleNo) {
        if (!tryOccupy(subOrderNo, afterSaleNo)) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }

    public boolean tryOccupy(String subOrderNo, String afterSaleNo) {
        return DataScopeContext.executeWithoutScope(() -> mapper.occupy(subOrderNo, afterSaleNo)) == 1;
    }

    /** 状态落到释放集合时调；不是自己占的就什么也不做 */
    public void releaseIfDone(OrdAfterSale as) {
        if (RELEASING.contains(as.getStatus())) {
            DataScopeContext.executeWithoutScope(() -> mapper.release(as.getSubOrderNo(), as.getAfterSaleNo()));
        }
    }
}
