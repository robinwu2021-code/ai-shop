package ai.neargo.shop.payclient;
import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import ai.neargo.shop.portal.biz.BizPayMerchantController;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
class PayMerchantProfileRelayTest {
    @Test void originalUserCredentialMerchantHeaderAndBytesReachTheIndependentService() throws Exception {
        byte[] body="{\"requestNo\":\"context\",\"expectedRevision\":0,\"legalName\":\"Fixture\",\"registrationNo\":\"AB12345678\"}".getBytes(StandardCharsets.UTF_8);
        var seen=new AtomicReference<byte[]>();var authorization=new AtomicReference<String>();var merchant=new AtomicReference<String>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/biz/pay/v2/merchant-profile-revisions",e->{seen.set(e.getRequestBody().readAllBytes());authorization.set(e.getRequestHeaders().getFirst("Authorization"));merchant.set(e.getRequestHeaders().getFirst("X-Merchant-No"));byte[] out="{\"code\":0,\"data\":{\"reviewStatus\":\"SUBMITTED\"}}".getBytes(StandardCharsets.UTF_8);e.getResponseHeaders().set("Content-Type","application/json");e.sendResponseHeaders(200,out.length);e.getResponseBody().write(out);e.close();});server.start();
        try {
            var controller=new BizPayMerchantController(new BizOnboardingForwarder(URI.create("http://127.0.0.1:"+server.getAddress().getPort())));
            var request=new MockHttpServletRequest("POST","/biz/pay/v2/merchant-profile-revisions");request.addHeader("Authorization","Bearer btk_fixture");request.addHeader("X-Merchant-No","PM-real");request.setContentType("application/json");request.setContent(body);
            assertEquals(200,controller.submitMerchantProfile(request).getStatusCode().value());assertArrayEquals(body,seen.get());assertEquals("Bearer btk_fixture",authorization.get());assertEquals("PM-real",merchant.get());
        }finally{server.stop(0);}
    }
    @Test void whitelistOpensOnlyProfileReadSubmitAndOriginalResult() {
        String root="/biz/pay/v2/merchant-profile-revisions";
        assertTrue(BizOnboardingForwarder.allowed("GET",root));assertTrue(BizOnboardingForwarder.allowed("POST",root));assertTrue(BizOnboardingForwarder.allowed("GET",root+"/by-request/context"));
        assertFalse(BizOnboardingForwarder.allowed("PUT",root));assertFalse(BizOnboardingForwarder.allowed("POST",root+"/approve"));assertFalse(BizOnboardingForwarder.allowed("GET",root+"/by-request/context/other"));
    }
}
