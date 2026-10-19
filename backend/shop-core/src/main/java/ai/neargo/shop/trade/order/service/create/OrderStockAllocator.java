package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.product.StockPort;
import java.util.List;
import org.springframework.stereotype.Component;

/** Only a known, fully rolled-back shortage permits dropping gifts and retrying paid quantities. */
@Component
public class OrderStockAllocator {
    private final StockPort stock;

    public OrderStockAllocator(StockPort stock) {
        this.stock = stock;
    }

    public boolean reserve(String payNo, List<StockPort.SkuQty> withGifts, List<StockPort.SkuQty> paidOnly) {
        try {
            lock(payNo, withGifts);
            return true;
        } catch (StockPort.ShortageException shortage) {
            if (withGifts.equals(paidOnly)) {
                throw BizException.of(ErrorCode.STOCK_NOT_ENOUGH);
            }
        } catch (BizException failure) {
            if (failure.errorCode() != ErrorCode.STOCK_NOT_ENOUGH
                    && failure.errorCode() != ErrorCode.STOCK_SHORT_ITEMS) {
                throw failure;
            }
            if (withGifts.equals(paidOnly)) {
                throw failure;
            }
        }
        lock(payNo, paidOnly);
        return false;
    }

    private void lock(String no, List<StockPort.SkuQty> needs) {
        List<String> failed = stock.lock(no, needs);
        if (failed == null) {
            throw new IllegalStateException("stock reservation result unknown");
        }
        if (!failed.isEmpty()) {
            throw BizException.of(ErrorCode.STOCK_NOT_ENOUGH);
        }
    }
}
