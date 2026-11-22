package ai.neargo.shop.message.notify;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ai.neargo.shop.message.entity.NotifyChannel;
import ai.neargo.shop.message.entity.SysNotifyLog;
import ai.neargo.shop.message.mapper.MessageMappers.NotifyLogMapper;
import ai.neargo.shop.message.notify.port.NotifyLoggingSmsPort;
import ai.neargo.shop.spi.notify.SendResult;
import ai.neargo.shop.spi.notify.SmsPort;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

class MitakeNotifyOperationsTest {
    private PlatformChannelCredentials credentials(String provider) {
        return new PlatformChannelCredentials(new MockEnvironment().withProperty("shop.sms.provider", provider));
    }

    @Test void successAndFailureLogsUseSelectedProviderAndPreserveReceipt() {
        for (String provider : new String[]{"ali", "mitake"}) {
            var mapper = mock(NotifyLogMapper.class);
            when(mapper.insert(any(SysNotifyLog.class))).thenReturn(1);
            var gateway = mock(SmsPort.class);
            when(gateway.sendOtp("0912345678", "123456")).thenReturn(new SendResult("#00000013", "MITAKE_LOGIN", "【NearShop】登入驗證碼：123456，請勿提供給他人。"));
            var port = new NotifyLoggingSmsPort(gateway, new NotifyLogWriter(mapper, credentials(provider)));
            port.sendOtp("0912345678", "123456");
            when(gateway.sendOtp("0912345678", "123456")).thenThrow(new SmsPort.SmsException("mitake_rejected:e", false, "拒絕正文：654321"));
            assertThrows(SmsPort.SmsException.class, () -> port.sendOtp("0912345678", "123456"));
            var captor = ArgumentCaptor.forClass(SysNotifyLog.class);
            verify(mapper, times(2)).insert(captor.capture());
            assertEquals(provider.toUpperCase(java.util.Locale.ROOT), captor.getAllValues().getFirst().getProvider());
            assertEquals(provider.toUpperCase(java.util.Locale.ROOT), captor.getAllValues().getLast().getProvider());
            assertEquals("#00000013", captor.getAllValues().getFirst().getProviderMsgId());
            assertEquals("【NearShop】登入驗證碼：123456，請勿提供給他人。", captor.getAllValues().getFirst().getContent());
            assertEquals("拒絕正文：654321", captor.getAllValues().getLast().getContent());
            assertEquals("091****5678", captor.getAllValues().getFirst().getTarget());
            assertEquals(SysNotifyLog.SENT, captor.getAllValues().getFirst().getStatus());
            assertEquals(SysNotifyLog.FAILED, captor.getAllValues().getLast().getStatus());
        }
    }

    @Test void inactiveSmsProviderIsNeverAdvertisedReadyAndOperatorDisableStillWins() {
        var service = mock(NotifyChannelService.class);
        when(service.credsReady(any(), any())).thenReturn(true);
        var registry = new NotifyChannelRegistry(null, service, credentials("mitake"));
        var channel = new NotifyChannel();
        channel.setChannelType(NotifyChannel.TYPE_SMS);
        channel.setScope(NotifyChannel.SCOPE_PLATFORM);
        channel.setEnabled(true);
        channel.setProvider(NotifyChannel.PROV_ALI);
        assertEquals(NotifyChannel.STATUS_DISABLED, registry.statusOf(channel));
        channel.setProvider(NotifyChannel.PROV_MITAKE);
        assertEquals(NotifyChannel.STATUS_READY, registry.statusOf(channel));
        channel.setEnabled(false);
        assertEquals(NotifyChannel.STATUS_DISABLED, registry.statusOf(channel));
        channel.setScope(NotifyChannel.SCOPE_TEST);
        assertEquals(NotifyChannel.STATUS_STUB, registry.statusOf(channel));
    }
}
