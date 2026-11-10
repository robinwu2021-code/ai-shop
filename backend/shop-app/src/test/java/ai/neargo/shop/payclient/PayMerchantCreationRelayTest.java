package ai.neargo.shop.payclient;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import ai.neargo.shop.portal.biz.BizPayOnboardingController;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PayMerchantCreationRelayTest {
    @Test void originalCredentialAndBodyAreRelayedWithoutEntityOrMerchantSubstitution() throws Exception {
        byte[] command="{\"requestNo\":\"context\",\"operatingEntityId\":\"OE1\",\"name\":\"独立支付商户\"}".getBytes(StandardCharsets.UTF_8);
        var header=new AtomicReference<String>();var input=new AtomicReference<byte[]>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/biz/pay/v2/merchant-creations",e->{header.set(e.getRequestHeaders().getFirst("Authorization"));input.set(e.getRequestBody().readAllBytes());byte[] body="{\"code\":0,\"data\":{\"merchantNo\":\"PM1\"}}".getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,body.length);e.getResponseBody().write(body);e.close();});server.start();
        try {
            var controller=new BizPayOnboardingController(new BizOnboardingForwarder(URI.create("http://127.0.0.1:"+server.getAddress().getPort())));
            var request=new MockHttpServletRequest("POST","/biz/pay/v2/merchant-creations");request.addHeader("Authorization","Bearer btk_fixture");request.setContentType("application/json");request.setContent(command);
            var result=controller.createMerchant(request);
            assertEquals(200,result.getStatusCode().value());assertEquals("Bearer btk_fixture",header.get());assertArrayEquals(command,input.get());assertTrue(new String(result.getBody(),StandardCharsets.UTF_8).contains("PM1"));
        } finally {server.stop(0);}
    }
    @Test void capabilitiesAndOriginalKeysHaveSeparateExactRoutes(){
        assertTrue(BizOnboardingForwarder.allowed("GET","/biz/pay/v2/merchant-creations/context"));
        assertTrue(BizOnboardingForwarder.allowed("POST","/biz/pay/v2/merchant-creations"));
        assertTrue(BizOnboardingForwarder.allowed("GET","/biz/pay/v2/merchant-creations/by-request/context"));
        assertFalse(BizOnboardingForwarder.allowed("GET","/biz/pay/v2/merchant-creations/original"));
        assertFalse(BizOnboardingForwarder.allowed("POST","/biz/pay/v2/merchant-creations/by-request/context"));
        assertFalse(BizOnboardingForwarder.allowed("GET","/biz/pay/v2/merchant-creations/by-request/context/extra"));
    }
}
