package ai.neargo.shop.trade.mapper;

import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdInvoiceRequest;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdCartItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** trade 域的 Mapper 集合。 */
public final class TradeMappers {

    private TradeMappers() {
    }

    public interface CartItemMapper extends BaseMapper<TrdCartItem> {
    }

    public interface OrderMapper extends BaseMapper<OrdOrder> {
    }

    public interface SubOrderMapper extends BaseMapper<OrdSubOrder> {
    }

    public interface OrderItemMapper extends BaseMapper<OrdItem> {
    }

    public interface StatusLogMapper extends BaseMapper<OrdStatusLog> {
    }

    public interface AfterSaleMapper extends BaseMapper<OrdAfterSale> {
    }

    public interface InvoiceRequestMapper extends BaseMapper<OrdInvoiceRequest> {
    }
}
