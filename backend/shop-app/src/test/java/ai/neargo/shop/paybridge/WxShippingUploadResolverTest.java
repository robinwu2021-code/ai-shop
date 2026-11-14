package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.WxLogisticsTypes;
import ai.neargo.shop.pay.service.PaymentLedgerService;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.order.mapper.OrderItemMapper;
import ai.neargo.shop.trade.order.mapper.StoreOrderMapper;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 微信发货上报只认大陆表（TDD-商户App快递公司 E4）：台湾码永远报不上微信。 */
class WxShippingUploadResolverTest {
    private final StoreOrderMapper subOrders = mock(StoreOrderMapper.class);
    private final OrderItemMapper items = mock(OrderItemMapper.class);
    private final PaymentLedgerService ledger = mock(PaymentLedgerService.class);
    private final ShippingUploadSources sources = mock(ShippingUploadSources.class);
    private final ObjectMapper json = JsonMapper.builder().build();
    private final WxShippingUploadResolver resolver =
            new WxShippingUploadResolver(subOrders, items, ledger, sources, json);

    @Test
    @DisplayName("★★★ 台湾码不进微信上报：存量单路径与冻结快照路径都判为缺料")
    void tw_code_is_never_reported_to_wechat() {
        assertThat(legacy("TCAT").ready()).isFalse();
        assertThat(legacy("SF").ready()).isTrue();
        assertThat(frozen("TCAT").ready()).isFalse();
        assertThat(frozen("SF").ready()).isTrue();
    }

    private WxShippingUploadResolver.Material legacy(String carrier) {
        TrdShippingUpload row = row();
        OrdItem item = new OrdItem();
        item.setTitle("蛋饼");
        when(items.selectList(any())).thenReturn(List.of(item));
        OrdSubOrder sub = new OrdSubOrder();
        sub.setExpressNo("NO-1");
        sub.setExpressCompany(carrier);
        when(subOrders.selectList(any())).thenReturn(List.of(sub));
        return resolver.resolve(row);
    }

    private WxShippingUploadResolver.Material frozen(String carrier) {
        TrdShippingUpload row = row();
        ShippingUploadSnapshot snapshot = new ShippingUploadSnapshot(List.of(new WxShippingPort.ShippingItem(
                "SUB-1", "SHP-1", 0L, "NO-1", carrier, "蛋饼")), 1, true, null);
        row.setSourceSnapshot(json.writeValueAsString(snapshot));
        when(sources.snapshotOf("O-1")).thenReturn(snapshot);
        return resolver.resolve(row);
    }

    private TrdShippingUpload row() {
        TrdShippingUpload row = new TrdShippingUpload();
        row.setOrderNo("O-1");
        row.setOutTradeNo("OT-1");
        row.setLogisticsType(WxLogisticsTypes.EXPRESS);
        when(ledger.paidPayment("O-1"))
                .thenReturn(Optional.of(new PaymentLedgerService.PaidPayment("OT-1", "openid-1", "wx-app")));
        return row;
    }
}
