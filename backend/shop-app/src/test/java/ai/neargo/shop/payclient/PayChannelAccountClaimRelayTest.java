package ai.neargo.shop.payclient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.commercebridge.FoundationPaymentAccess;
import ai.neargo.shop.commercebridge.FoundationTarget;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.portal.biz.BizChannelAccountClaimController;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

class PayChannelAccountClaimRelayTest {
    @Test void transportUnknownNeverRetriesOrEchoesInternalDetail() throws Exception {
        for(var failure:new Exception[]{new HttpTimeoutException("private-detail"),new IOException("private-detail"),new InterruptedException("private-detail")}) {
            var forwarder=mock(PayChannelAccountClaimForwarder.class);
            var access=mock(FoundationPaymentAccess.class);var api=mock(PaySourceLinkApi.class);
            var beans=new StaticListableBeanFactory();beans.addBean("source",api);
            var sync=new StoreSyncProperties(true,"http://127.0.0.1:1","AI_SHOP_UME","local","MAIN","CN",null,Duration.ofSeconds(60),Duration.ofSeconds(1));
            var controller=new BizChannelAccountClaimController(forwarder,access,beans.getBeanProvider(PaySourceLinkApi.class),sync);
            when(access.require(any(),eq("M"),eq("S"))).thenReturn(new FoundationTarget("M","S"));
            when(api.status("MAIN","M")).thenReturn(new PaySourceLinkApi.Status("PENDING","PM",null,null,null));
            when(forwarder.forward(any(),any(),any(),any())).thenThrow(failure);
            var request=new MockHttpServletRequest("POST",PayChannelAccountClaimForwarder.BASE);
            request.addHeader("X-Store-No","S");request.addHeader("X-Merchant-No","PM");
            request.setContentType("application/json");request.setContent("{\"requestNo\":\"original\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            BizContext.set(new BizContext("M",Set.of(),Set.of(),Set.of("S"),"S",true));
            try {
                var error=assertThrows(PayBizException.class,()->controller.write(request));
                assertEquals(failure instanceof HttpTimeoutException?HttpStatus.GATEWAY_TIMEOUT:HttpStatus.BAD_GATEWAY,error.status());
                assertFalse(error.getMessage().contains("private-detail"));
                assertEquals(failure instanceof InterruptedException,Thread.currentThread().isInterrupted());
                verify(forwarder,times(1)).forward(any(),any(),any(),any());
            } finally {BizContext.clear();Thread.interrupted();}
        }
    }
}
