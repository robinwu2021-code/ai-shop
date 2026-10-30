package ai.neargo.shop.beauty.dto;

import ai.neargo.shop.spi.product.StoreSalesCatalogPort.SalesMode;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort.Window;
import java.util.List;

/** Sales window display only; saleableNow does not grant appointment capacity or trading permission. */
public record ServiceSalesVO(String catalogVersionNo, String groupKey, int position, String timezone,
                             SalesMode salesMode, List<Window> windows, boolean saleableNow) {
    public ServiceSalesVO { windows = List.copyOf(windows); }
}
