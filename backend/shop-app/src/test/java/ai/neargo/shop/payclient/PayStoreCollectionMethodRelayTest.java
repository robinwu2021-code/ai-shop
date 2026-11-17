package ai.neargo.shop.payclient;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import ai.neargo.shop.portal.biz.BizPayMerchantController;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** TDD-门店付款方式-App设置 AC1/AC2：门店付款方式读写原样转发给 near-pay（鉴权由 near-pay 做）。 */
class PayStoreCollectionMethodRelayTest {
    @Test void onlyGetAndPutOfOneStoreAreForwardable() {
        String root = "/biz/pay/v2/stores/ST2611132NS49C2EPQ/collection-methods";
        assertTrue(BizOnboardingForwarder.allowed("GET", root));
        assertTrue(BizOnboardingForwarder.allowed("PUT", root));
        assertFalse(BizOnboardingForwarder.allowed("POST", root));
        assertFalse(BizOnboardingForwarder.allowed("DELETE", root));
        assertFalse(BizOnboardingForwarder.allowed("GET", root + "/extra"));
        assertFalse(BizOnboardingForwarder.allowed("GET", "/biz/pay/v2/stores/ST.1/collection-methods"));
        assertFalse(BizOnboardingForwarder.allowed("GET", "/biz/pay/v2/stores//collection-methods"));
        assertFalse(BizOnboardingForwarder.allowed("PUT", "/biz/pay/v2/stores/ST1/collection-policy"));
    }

    @Test void putBodyAndMerchantHeaderReachNearPayUnchanged() throws Exception {
        byte[] command = "{\"requestNo\":\"R1\",\"expectedVersion\":3,\"enabledProductCodes\":[\"LINEPAY_ONLINE\"]}"
                .getBytes(StandardCharsets.UTF_8);
        var auth = new AtomicReference<String>();
        var merchant = new AtomicReference<String>();
        var method = new AtomicReference<String>();
        var input = new AtomicReference<byte[]>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/biz/pay/v2/stores/ST1/collection-methods", e -> {
            method.set(e.getRequestMethod());
            auth.set(e.getRequestHeaders().getFirst("Authorization"));
            merchant.set(e.getRequestHeaders().getFirst("X-Merchant-No"));
            input.set(e.getRequestBody().readAllBytes());
            byte[] body = "{\"code\":0,\"data\":{\"storeNo\":\"ST1\",\"mode\":\"OVERRIDE\",\"version\":4,\"items\":[]}}"
                    .getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
        });
        server.start();
        try {
            var controller = new BizPayMerchantController(
                    new BizOnboardingForwarder(URI.create("http://127.0.0.1:" + server.getAddress().getPort())));
            var request = new MockHttpServletRequest("PUT", "/biz/pay/v2/stores/ST1/collection-methods");
            request.addHeader("Authorization", "Bearer btk_fixture");
            request.addHeader("X-Merchant-No", "PM1");
            request.setContentType("application/json");
            request.setContent(command);
            var result = controller.storeCollectionMethods(request);
            assertEquals(200, result.getStatusCode().value());
            assertEquals("PUT", method.get());
            assertEquals("Bearer btk_fixture", auth.get());
            assertEquals("PM1", merchant.get());
            assertArrayEquals(command, input.get());
            assertTrue(new String(result.getBody(), StandardCharsets.UTF_8).contains("\"version\":4"));
        } finally {
            server.stop(0);
        }
    }
}
