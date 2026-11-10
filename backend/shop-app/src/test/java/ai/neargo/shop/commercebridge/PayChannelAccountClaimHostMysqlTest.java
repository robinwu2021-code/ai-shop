package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.config.PayChannelAccountClaimConfiguration;
import ai.neargo.shop.config.PayBizOnboardingConfiguration;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.impl.StoreOwnershipWritePortImpl;
import ai.neargo.shop.payclient.*;
import ai.neargo.shop.portal.biz.*;
import ai.neargo.svc.client.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

/** Real DB authentication + host foundation admission; PAY business authority is independently tested in PAY. */
@EnabledIfEnvironmentVariable(named="COMMERCE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class PayChannelAccountClaimHostMysqlTest {
    private static final String BASE=PayChannelAccountClaimForwarder.BASE;
    private final PaySourceLinkApi sources=mock(PaySourceLinkApi.class);
    private final AtomicInteger calls=new AtomicInteger(),responseStatus=new AtomicInteger(200);
    private final AtomicReference<byte[]> received=new AtomicReference<>(),response=new AtomicReference<>("{\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8));
    private final AtomicReference<String> target=new AtomicReference<>();
    private final AtomicReference<com.sun.net.httpserver.Headers> headers=new AtomicReference<>();
    private AccessMysqlFixture f;
    private AnnotationConfigWebApplicationContext web;
    private HttpServer upstream;
    private MockMvc mvc;
    private String owner,staff;

    @BeforeEach void open() throws Exception { initialize(true); }
    void initialize(boolean legacy) throws Exception {
        f=new AccessMysqlFixture(Clock.systemUTC());web=new AnnotationConfigWebApplicationContext();
        upstream=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        upstream.createContext("/",exchange->{
            calls.incrementAndGet();received.set(exchange.getRequestBody().readAllBytes());headers.set(exchange.getRequestHeaders());
            target.set(exchange.getRequestMethod()+" "+exchange.getRequestURI());
            exchange.getResponseHeaders().set("Content-Type","application/octet-stream");
            exchange.getResponseHeaders().set("Cache-Control","private, no-store");exchange.getResponseHeaders().set("X-Content-Type-Options","nosniff");
            byte[] bytes=response.get();exchange.sendResponseHeaders(responseStatus.get(),bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });upstream.start();
        var properties=new LinkedHashMap<String,Object>();
        properties.put("shop.pay.channel-account-claims.enabled","true");
        properties.put("shop.pay.channel-account-claims.base-url","http://127.0.0.1:"+upstream.getAddress().getPort());
        properties.put("shop.pay.biz-onboarding.enabled",Boolean.toString(legacy));
        properties.put("shop.pay.biz-onboarding.base-url","http://127.0.0.1:"+upstream.getAddress().getPort());
        properties.put("shop.auth.token-store-by-realm.merchant","db");
        web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("claimsTest",properties));
        when(sources.status("MAIN","M")).thenReturn(new PaySourceLinkApi.Status("PENDING","PM","VERIFY_PENDING","PENDING",null));
        web.register(PayChannelAccountClaimConfiguration.class,PayBizOnboardingConfiguration.class,
                BizChannelAccountClaimController.class,BizPayOnboardingController.class,
                PayBizExceptionHandler.class,ApiResponseWrapper.class,StoreOwnershipWritePortImpl.class);
        web.addBeanFactoryPostProcessor(beans->{
            beans.registerSingleton("claimJdbc",f.jdbc);beans.registerSingleton("authJdbcClient",JdbcClient.create(f.ds));beans.registerSingleton("claimSources",sources);
            beans.registerSingleton("claimSync",new StoreSyncProperties(true,"http://127.0.0.1:1","AI_SHOP_UME","local-test","MAIN","CN",null,Duration.ofSeconds(60),Duration.ofSeconds(1)));
        });
        mvc=CommerceAccessSecurityFlowTest.merchantMvc(f,web,true);
        owner=f.store.issue(TokenStore.SessionData.of(LoginUser.merchantByUser("U","owner")));
        f.jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,is_owner,status,created_at,updated_at) VALUES('SF','M',0,'ACTIVE',NOW(),NOW())");
        f.jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at) VALUES('SF','S','CLERK',NOW(),NOW())");
        staff=f.store.issue(TokenStore.SessionData.of(LoginUser.merchant("SF","clerk")));
    }
    @AfterEach void close() {
        if(web!=null)web.close();if(upstream!=null)upstream.stop(0);if(f!=null)f.close();
    }
    MockHttpServletRequestBuilder selected(MockHttpServletRequestBuilder request) {
        return request.header("Authorization","Bearer "+owner).header("X-Store-No","S").header("X-Merchant-No","PM");
    }
    @Test void claimsHaveOwnDbAuthenticationAndMultipartResolverWhenLegacyBridgeIsDisabled() throws Exception {
        close();initialize(false);
        assertTrue(web.getBeansOfType(BizPayOnboardingController.class).isEmpty());
        assertNotNull(web.getBean("multipartResolver"));
        mvc.perform(selected(get(BASE))).andExpect(status().isOk());
        assertEquals(1,calls.get());
    }
    @Test void everyFrozenRouteUsesFinanceFoundationThenPreservesOriginalRequestAndOldBridge() throws Exception {
        var routes=Map.of("GET",List.of(BASE,BASE+"/by-request/R",BASE+"/C",BASE+"/C/files",BASE+"/C/files/"+"a".repeat(32),BASE+"/C/commands/R"),
                "POST",List.of(BASE,BASE+"/C/submit"));
        for(var entry:routes.entrySet())for(String path:entry.getValue()) {
            var request="GET".equals(entry.getKey())?get(path):post(path).contentType(MediaType.APPLICATION_JSON).content("{\"requestNo\":\"R\",\"expectedVersion\":0}");
            mvc.perform(selected(request)).andExpect(status().isOk());assertEquals(entry.getKey()+" "+path,target.get());
        }
        assertEquals("Bearer "+owner,headers.get().getFirst("Authorization"));
        mvc.perform(get("/biz/pay/v2/memberships").header("Authorization","Bearer "+owner)).andExpect(status().isOk());
        assertEquals("GET /biz/pay/v2/memberships",target.get());
    }
    @Test void freshDbSessionRejectsConsumerExpiredRevokedDisabledAndNoFinanceBeforeForward() throws Exception {
        mvc.perform(get(BASE).header("Authorization","Bearer ctk_wrong").header("X-Store-No","S").header("X-Merchant-No","PM")).andExpect(status().isForbidden());
        mvc.perform(get(BASE).header("Authorization","Bearer "+staff).header("X-Store-No","S").header("X-Merchant-No","PM")).andExpect(status().isForbidden());
        f.jdbc.update("UPDATE mch_session SET revoked_at=NOW() WHERE token_hash=?",TokenHash.of(owner));
        mvc.perform(selected(get(BASE))).andExpect(status().isUnauthorized());
        f.jdbc.update("UPDATE mch_session SET revoked_at=NULL,expires_at=DATE_SUB(UTC_TIMESTAMP(),INTERVAL 1 SECOND) WHERE token_hash=?",TokenHash.of(owner));
        mvc.perform(selected(get(BASE))).andExpect(status().isUnauthorized());
        f.jdbc.update("UPDATE mch_account SET status='DISABLED' WHERE mch_account_no='SF'");
        mvc.perform(get(BASE).header("Authorization","Bearer "+staff)).andExpect(status().isForbidden());
        f.jdbc.execute("DROP TABLE mch_session");
        mvc.perform(selected(get(BASE))).andExpect(status().isServiceUnavailable());
        assertEquals(0,calls.get());verifyNoInteractions(sources);
    }
    @Test void selectedTargetAndTrustedMerchantMappingCannotBeReplacedByHeaders() throws Exception {
        mvc.perform(selected(get(BASE)).header("X-Entity-No","FOREIGN")).andExpect(status().isForbidden());
        mvc.perform(get(BASE).header("Authorization","Bearer "+owner).header("X-Store-No","FOREIGN").header("X-Merchant-No","PM")).andExpect(status().isForbidden());
        mvc.perform(get(BASE).header("Authorization","Bearer "+owner).header("X-Merchant-No","PM")).andExpect(status().isForbidden());
        mvc.perform(selected(get(BASE)).header("X-Merchant-No","FOREIGN")).andExpect(status().isForbidden());
        mvc.perform(get(BASE).header("Authorization","Bearer "+owner).header("X-Store-No","S").header("X-Merchant-No","FOREIGN")).andExpect(status().isForbidden());
        assertEquals(0,calls.get());
    }
    @Test void sourceNoneAndUnknownNeverBecomeHostSuccessOrForwardedClaims() throws Exception {
        when(sources.status("MAIN","M")).thenReturn(new PaySourceLinkApi.Status("NONE",null,null,null,null));
        mvc.perform(selected(post(BASE)).contentType(MediaType.APPLICATION_JSON).content("{\"requestNo\":\"R\"}")).andExpect(status().isConflict());
        when(sources.status("MAIN","M")).thenThrow(new ServiceCallException(CallOutcome.TIMEOUT,"PAY",0,"controlled"));
        mvc.perform(selected(get(BASE))).andExpect(status().isServiceUnavailable());assertEquals(0,calls.get());
    }
    @Test void privateMultipartBytesAndBinaryRepliesAreBoundedWithoutInternalHeaderForwarding() throws Exception {
        String boundary="ClaimBoundary";
        byte[] bytes=("--"+boundary+"\r\nContent-Disposition: form-data; name=\"requestNo\"\r\n\r\nR\r\n--"+boundary
                +"\r\nContent-Disposition: form-data; name=\"expectedVersion\"\r\n\r\n0\r\n--"+boundary
                +"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"proof.png\"\r\nContent-Type: image/png\r\n\r\nprivate-proof\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] binary={0,-1,2,0};response.set(binary);
        mvc.perform(selected(post(BASE+"/C/files")).contentType("multipart/form-data; boundary="+boundary).content(bytes)
                .header("X-Internal-Token","forged").header("X-Internal-Caller","forged"))
                .andExpect(status().isOk()).andExpect(content().bytes(binary)).andExpect(header().string("Cache-Control","private, no-store"));
        assertArrayEquals(bytes,received.get());assertNull(headers.get().getFirst("X-Internal-Token"));assertNull(headers.get().getFirst("X-Internal-Caller"));
        mvc.perform(selected(post(BASE)).contentType(MediaType.APPLICATION_JSON).content(new byte[1024*1024+1])).andExpect(status().isPayloadTooLarge());
        mvc.perform(selected(post(BASE+"/C/files")).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnsupportedMediaType());
        assertEquals(1,calls.get());
    }
    @Test void upstreamUnknownResultIsReturnedOnceAndOriginalRequestKeyIsUnchanged() throws Exception {
        responseStatus.set(504);byte[] failure="{\"code\":10505,\"data\":null}".getBytes(StandardCharsets.UTF_8);response.set(failure);
        byte[] command="{\"requestNo\":\"original-R\",\"payChannel\":\"NEWEBPAY\",\"channelMerchantId\":\"12345\"}".getBytes(StandardCharsets.UTF_8);
        mvc.perform(selected(post(BASE)).contentType(MediaType.APPLICATION_JSON).content(command)).andExpect(status().isGatewayTimeout()).andExpect(content().bytes(failure));
        assertEquals(1,calls.get());assertArrayEquals(command,received.get());
        responseStatus.set(200);mvc.perform(selected(get(BASE+"/by-request/original-R"))).andExpect(status().isOk());
        assertEquals("GET "+BASE+"/by-request/original-R",target.get());
    }
}
