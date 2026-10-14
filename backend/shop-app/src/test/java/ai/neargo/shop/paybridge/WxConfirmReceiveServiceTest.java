package ai.neargo.shop.paybridge;

import ai.neargo.shop.fulfillment.service.LogisticsService;
import ai.neargo.shop.fulfillment.service.LogisticsService.SignedShipment;
import ai.neargo.shop.spi.trade.WxShippingPort;
import ai.neargo.shop.spi.trade.WxShippingPort.ConfirmCmd;
import ai.neargo.shop.spi.trade.WxShippingPort.Result;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.entity.TrdShippingUpload;
import ai.neargo.shop.trade.mapper.TradeMappers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 签收 → 提醒买家确认收货（TDD-物流域-完整方案 批 A）。
 *
 * <p>守的是微信那三条硬约束：每单一次、仅快递、签收时间是<b>秒</b>且要晚于发货。
 */
class WxConfirmReceiveServiceTest {

    private final LogisticsService logistics = mock(LogisticsService.class);
    private final WxShippingPort shipping = mock(WxShippingPort.class);
    private final TradeMappers.ShippingUploadMapper uploadMapper = mock(TradeMappers.ShippingUploadMapper.class);
    private final TradeMappers.SubOrderMapper subOrderMapper = mock(TradeMappers.SubOrderMapper.class);

    private WxConfirmReceiveService service() {
        return new WxConfirmReceiveService(logistics, shipping, uploadMapper, subOrderMapper);
    }

    /** 一单已签收，台账行由参数决定（type=1 快递、SUCCESS、未提醒过 为默认可提醒态） */
    private TrdShippingUpload wire(long signedAtMillis, Integer logisticsType,
                                   String status, Long alreadyNotifiedAt) {
        when(logistics.signedShipments(anyInt()))
                .thenReturn(List.of(new SignedShipment("SH-1", "SUB-1", signedAtMillis)));
        OrdSubOrder sub = new OrdSubOrder();
        sub.setSubOrderNo("SUB-1");
        sub.setOrderNo("SO-1");
        when(subOrderMapper.selectOne(any())).thenReturn(sub);
        TrdShippingUpload row = new TrdShippingUpload();
        row.setId(7L);
        row.setOrderNo("SO-1");
        row.setOutTradeNo("SO-1");
        row.setLogisticsType(logisticsType);
        row.setStatus(status);
        row.setConfirmNotifiedAt(alreadyNotifiedAt);
        when(uploadMapper.selectOne(any())).thenReturn(row);
        return row;
    }

    @Test
    @DisplayName("★★★ 签收后提醒买家确认收货，签收时间按**秒**传给微信")
    void notifiesWithSecondsPrecision() {
        wire(1_791_500_000_000L, 1, TrdShippingUpload.SUCCESS, null);
        when(shipping.notifyConfirmReceive(any())).thenReturn(Result.ok());

        var r = service().notifyPending(50);

        assertThat(r.scanned()).isEqualTo(1);
        assertThat(r.notified()).isEqualTo(1);
        ArgumentCaptor<ConfirmCmd> cmd = ArgumentCaptor.forClass(ConfirmCmd.class);
        verify(shipping).notifyConfirmReceive(cmd.capture());
        assertThat(cmd.getValue().outTradeNo()).isEqualTo("SO-1");
        /*
         * 库里存毫秒、微信要秒。传毫秒不会报错 —— 微信把它当成一个遥远未来的时间，
         * 「晚于发货时间」那条校验反而过，买家收到的提醒上写着五万年后。
         */
        assertThat(cmd.getValue().receivedAt()).isEqualTo(1_791_500_000L);
        // 发完要落标记，否则下一轮再来一次，而微信「每单一次」的那一次已经用掉了
        ArgumentCaptor<TrdShippingUpload> patch = ArgumentCaptor.forClass(TrdShippingUpload.class);
        verify(uploadMapper).updateById(patch.capture());
        assertThat(patch.getValue().getConfirmNotifiedAt()).isNotNull();
    }

    @Test
    @DisplayName("★★★ 每单一次：已经提醒过的不再调微信")
    void neverNotifiesTwice() {
        wire(1_791_500_000_000L, 1, TrdShippingUpload.SUCCESS, 1_791_400_000_000L);

        var r = service().notifyPending(50);

        assertThat(r.notified()).isZero();
        verify(shipping, never()).notifyConfirmReceive(any());
    }

    @Test
    @DisplayName("★★ 只对物流快递提醒 —— 自提/虚拟单微信不允许")
    void onlyExpress() {
        wire(1_791_500_000_000L, 4, TrdShippingUpload.SUCCESS, null);   // 4 = 用户自提

        assertThat(service().notifyPending(50).notified()).isZero();
        verify(shipping, never()).notifyConfirmReceive(any());
    }

    @Test
    @DisplayName("★★ 发货都没报上去的单，不谈提醒收货")
    void skipsWhenShippingNotUploaded() {
        wire(1_791_500_000_000L, 1, "PENDING", null);

        assertThat(service().notifyPending(50).notified()).isZero();
        verify(shipping, never()).notifyConfirmReceive(any());
    }

    @Test
    @DisplayName("★★★ 不可重试的失败也要落标记 —— 否则下一轮再撞一次，而那一次可能是「每单一次」的最后机会")
    void marksEvenOnFatalFailure() {
        wire(1_791_500_000_000L, 1, TrdShippingUpload.SUCCESS, null);
        when(shipping.notifyConfirmReceive(any())).thenReturn(Result.fatal(10060029, "签收时间非法"));

        assertThat(service().notifyPending(50).notified()).isZero();
        verify(uploadMapper).updateById(any(TrdShippingUpload.class));
    }

    @Test
    @DisplayName("★★ 可重试的失败不落标记 —— 取 token 失败这类下一轮会好")
    void keepsRetryable() {
        wire(1_791_500_000_000L, 1, TrdShippingUpload.SUCCESS, null);
        when(shipping.notifyConfirmReceive(any())).thenReturn(Result.retry(40001, "access_token 失效"));

        assertThat(service().notifyPending(50).notified()).isZero();
        verify(uploadMapper, never()).updateById(any(TrdShippingUpload.class));
    }

    @Test
    @DisplayName("一个支付单多张子单签收时间不同：取**最晚**那个，保证晚于任何一次发货")
    void takesLatestSignedTimeOfTheOrder() {
        when(logistics.signedShipments(anyInt())).thenReturn(List.of(
                new SignedShipment("SH-1", "SUB-1", 1_791_400_000_000L),
                new SignedShipment("SH-2", "SUB-2", 1_791_500_000_000L)));
        OrdSubOrder sub = new OrdSubOrder();
        sub.setSubOrderNo("SUB-1");
        sub.setOrderNo("SO-1");
        when(subOrderMapper.selectOne(any())).thenReturn(sub);
        TrdShippingUpload row = new TrdShippingUpload();
        row.setId(7L);
        row.setOrderNo("SO-1");
        row.setOutTradeNo("SO-1");
        row.setLogisticsType(1);
        row.setStatus(TrdShippingUpload.SUCCESS);
        when(uploadMapper.selectOne(any())).thenReturn(row);
        when(shipping.notifyConfirmReceive(any())).thenReturn(Result.ok());

        service().notifyPending(50);

        ArgumentCaptor<ConfirmCmd> cmd = ArgumentCaptor.forClass(ConfirmCmd.class);
        verify(shipping).notifyConfirmReceive(cmd.capture());
        assertThat(cmd.getValue().receivedAt()).isEqualTo(1_791_500_000L);
    }
}
