package ai.neargo.shop.payclient;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.config.PayBizOnboardingConfiguration;
import ai.neargo.shop.config.SecurityConfig;
import ai.neargo.shop.pay.api.guard.core.EntryGuard;
import ai.neargo.shop.pay.api.guard.core.GuardFilter;
import ai.neargo.shop.pay.api.guard.core.GuardFilters;
import ai.neargo.shop.pay.api.guard.core.RequestLogWriter;
import ai.neargo.shop.pay.client.BizOnboardingForwarder;
import ai.neargo.shop.portal.biz.BizPayOnboardingController;
import ai.neargo.shop.portal.biz.BizPaySessionController;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class PayBizOnboardingHttpTest {
  HttpServer upstream;
  AnnotationConfigWebApplicationContext context;
  MockMvc mvc;
  JdbcTemplate jdbc;
  AtomicInteger calls = new AtomicInteger(), responseStatus = new AtomicInteger(200);
  AtomicReference<byte[]> responseBody =
      new AtomicReference<>("{\"code\":0,\"data\":[]}".getBytes(StandardCharsets.UTF_8));
  AtomicReference<String> target = new AtomicReference<>();
  AtomicReference<byte[]> body = new AtomicReference<>();
  AtomicReference<com.sun.net.httpserver.Headers> headers = new AtomicReference<>();

  @BeforeEach
  void setUp() throws Exception {
    upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    upstream.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          target.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
          headers.set(exchange.getRequestHeaders());
          body.set(exchange.getRequestBody().readAllBytes());
          exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
          exchange.getResponseHeaders().set("Content-Disposition", "inline; filename=private.png");
          exchange.getResponseHeaders().set("Cache-Control", "private, no-store");
          exchange.getResponseHeaders().set("Pragma", "no-cache");
          exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
          exchange.getResponseHeaders().set("X-Frame-Options", "SAMEORIGIN");
          exchange.getResponseHeaders().set("Set-Cookie", "never=forward");
          exchange.getResponseHeaders().set("Location", "/login");
          byte[] bytes = responseBody.get();
          exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
          exchange.getResponseBody().write(bytes);
          exchange.close();
        });
    upstream.start();
    open(
        Map.of(
            "shop.pay.biz-onboarding.enabled",
            "true",
            "shop.auth.token-store-by-realm.merchant",
            "db"));
  }

  void open(Map<String, Object> properties) {
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.getEnvironment().setActiveProfiles("api");
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(new MapPropertySource("test", properties));
    context
        .getEnvironment()
        .getPropertySources()
        .addLast(
            new MapPropertySource(
                "origin",
                Map.of(
                    "shop.pay.biz-onboarding.base-url",
                    "http://127.0.0.1:" + upstream.getAddress().getPort())));
    context.register(
        Web.class,
        SecurityConfig.class,
        PayBizOnboardingConfiguration.class,
        BizPaySessionController.class,
        BizPayOnboardingController.class,
        PayBizExceptionHandler.class,
        ApiResponseWrapper.class,
        GlobalExceptionHandler.class);
    context.refresh();
    mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    jdbc = context.getBean(JdbcTemplate.class);
    clearInvocations(context.getBean(GuardFilters.class));
  }

  @AfterEach
  void tearDown() {
    if (context != null) context.close();
    if (upstream != null) upstream.stop(0);
  }

  @Test
  void sessionIsAuthenticatedTypedMinimalAndIgnoresIdentityAndStoreHeaders() throws Exception {
    mvc.perform(get("/biz/auth/session")).andExpect(status().isUnauthorized());
    mvc.perform(get("/biz/auth/session").header("Authorization", "Bearer ctk_wrong"))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/biz/auth/session")
                .header("Authorization", "Bearer btk_user")
                .header("X-User-No", "forged")
                .header("X-Subject-Type", "MCH_ACCOUNT")
                .header("X-Tenant-No", "OTHER")
                .header("X-Store-No", "not-a-host-store"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.code").value(0))
        .andExpect(jsonPath("$.data.realm").value("MERCHANT"))
        .andExpect(jsonPath("$.data.subjectType").value("USER"))
        .andExpect(jsonPath("$.data.subjectRef").value("SAME"))
        .andExpect(jsonPath("$.data.tenantNo").value("MAIN"))
        .andExpect(jsonPath("$.data.status").value("ACTIVE"))
        .andExpect(jsonPath("$.data.length()").value(5));
    mvc.perform(get("/biz/auth/session").header("Authorization", "Bearer btk_staff"))
        .andExpect(jsonPath("$.data.subjectType").value("MCH_ACCOUNT"));
    assertEquals(0, calls.get());
    verifyNoInteractions(
        context.getBean(BizIdentityResolver.class), context.getBean(TokenStore.class));
  }

  @Test
  void everyRequestChecksRevocationDisabledIdentityAndDependencyFailure() throws Exception {
    mvc.perform(get("/biz/pay/v2/memberships").header("Authorization", "Bearer btk_user"))
        .andExpect(status().isOk());
    jdbc.update(
        "UPDATE mch_session SET revoked_at=CURRENT_TIMESTAMP WHERE token_hash=?",
        TokenHash.of("btk_user"));
    mvc.perform(get("/biz/pay/v2/memberships").header("Authorization", "Bearer btk_user"))
        .andExpect(status().isUnauthorized());
    jdbc.update("UPDATE mch_account SET status='DISABLED'");
    mvc.perform(get("/biz/auth/session").header("Authorization", "Bearer btk_staff"))
        .andExpect(status().isForbidden());
    jdbc.execute("DROP TABLE mch_session");
    mvc.perform(get("/biz/auth/session").header("Authorization", "Bearer btk_user"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(
            content()
                .string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SELECT"))));
    assertEquals(1, calls.get());
  }

  @Test
  void allHandoffRoutesReachUpstreamWithoutLocalStoreAdmission() throws Exception {
    var routes =
        new ObjectMapper()
            .readTree(
                Files.readString(
                    Path.of("../../docs/handoff/nearpay-biz-onboarding-2026-11-09/routes.json")))
            .get("routes");
    for (var route : routes) {
      String path =
          route
              .get("path")
              .asText()
              .replace("{no}", "ONB_1")
              .replace("{requestNo}", "REQ_1")
              .replace("{fileNo}", "a".repeat(32));
      var request =
          request(
                  org.springframework.http.HttpMethod.valueOf(route.get("method").asText()),
                  URI.create(path))
              .header("Authorization", "Bearer btk_user")
              .header("X-Merchant-No", "PAYMENT_MERCHANT");
      if (path.endsWith("/files") && route.get("method").asText().equals("POST")) {
        request
            .contentType("multipart/form-data; boundary=raw-boundary")
            .content(PayBizRequestBodyTest.multipart(2 * 1024 * 1024));
      } else request.contentType("application/json").content("{}");
      mvc.perform(request).andExpect(status().isOk());
      assertEquals(route.get("method").asText() + " " + path, target.get());
    }
    assertEquals(15, calls.get());
    verifyNoInteractions(
        context.getBean(BizIdentityResolver.class),
        context.getBean(TokenStore.class),
        context.getBean(GuardFilters.class));
  }

  @Test
  void rawMultipartAndBinaryAreUnchangedAndOnlyAllowlistedHeadersCross() throws Exception {
    byte[] payload = PayBizRequestBodyTest.multipart(2 * 1024 * 1024);
    byte[] binary = new byte[] {0, -1, 3, 12, 0};
    responseBody.set(binary);
    mvc.perform(
            post("/biz/pay/v2/onboardings/ONB/files")
                .header("Authorization", "Bearer btk_user")
                .header("X-Merchant-No", "PAY-M")
                .header("X-Store-No", "PAY-S")
                .header("X-Request-Id", "original-request")
                .header("X-User-No", "forged")
                .header("X-Subject-Type", "OPS")
                .header("Cookie", "private=cookie")
                .contentType("multipart/form-data; boundary=raw-boundary")
                .content(payload))
        .andExpect(status().isOk())
        .andExpect(content().bytes(binary))
        .andExpect(header().string("Content-Disposition", "inline; filename=private.png"))
        .andExpect(header().string("Cache-Control", "private, no-store"))
        .andExpect(header().string("Pragma", "no-cache"))
        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
        .andExpect(header().string("X-Frame-Options", "SAMEORIGIN"))
        .andExpect(header().doesNotExist("Set-Cookie"))
        .andExpect(header().doesNotExist("Location"));
    assertArrayEquals(payload, body.get());
    assertEquals("Bearer btk_user", headers.get().getFirst("Authorization"));
    assertEquals("PAY-M", headers.get().getFirst("X-Merchant-No"));
    assertEquals("PAY-S", headers.get().getFirst("X-Store-No"));
    assertEquals("original-request", headers.get().getFirst("X-Request-Id"));
    assertEquals(
        "multipart/form-data; boundary=raw-boundary", headers.get().getFirst("Content-Type"));
    assertNull(headers.get().getFirst("X-User-No"));
    assertNull(headers.get().getFirst("X-Subject-Type"));
    assertNull(headers.get().getFirst("Cookie"));
  }

  @Test
  void preservesJsonVersionsQueriesErrorsAndRedirectsWithoutResending() throws Exception {
    String payload =
        "{ \"requestNo\":\"original-command\", \"expectedVersion\":3, \"expectedDraftVersion\":4,"
            + " \"fields\":{} }";
    for (int status : List.of(302, 303, 401, 403, 409, 422, 503)) {
      responseStatus.set(status);
      responseBody.set("{\"code\":987,\"data\":null}".getBytes(StandardCharsets.UTF_8));
      int before = calls.get();
      mvc.perform(
              put("/biz/pay/v2/onboardings/ONB/draft")
                  .header("Authorization", "Bearer btk_user")
                  .contentType("application/json")
                  .content(payload))
          .andExpect(status().is(status))
          .andExpect(content().bytes(responseBody.get()));
      assertArrayEquals(payload.getBytes(StandardCharsets.UTF_8), body.get());
      assertEquals(before + 1, calls.get());
    }
    mvc.perform(
        get("/biz/pay/v2/onboardings?page=2&size=10").header("Authorization", "Bearer btk_user"));
    assertEquals("GET /biz/pay/v2/onboardings?page=2&size=10", target.get());
  }

  @Test
  void unauthorizedUnlistedMalformedAndOversizedRequestsNeverReachUpstream() throws Exception {
    mvc.perform(post("/biz/pay/v2/onboardings").content("{}")).andExpect(status().isUnauthorized());
    for (String suffix : List.of("submit", "dispatch", "credentials"))
      mvc.perform(
              post("/biz/pay/v2/onboardings/ONB/" + suffix)
                  .header("Authorization", "Bearer btk_user"))
          .andExpect(status().is4xxClientError());
    mvc.perform(
            get("/biz/pay/v2/onboardings/" + "X".repeat(65))
                .header("Authorization", "Bearer btk_user"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            get("/biz/pay/v2/onboardings/ONB/files/UPPERCASE")
                .header("Authorization", "Bearer btk_user"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/biz/pay/v2/onboardings")
                .header("Authorization", "Bearer btk_user")
                .contentType("application/json")
                .content(new byte[1024 * 1024 + 1]))
        .andExpect(status().isPayloadTooLarge());
    mvc.perform(
            post("/biz/pay/v2/onboardings/ONB/files")
                .header("Authorization", "Bearer btk_user")
                .contentType("multipart/form-data; boundary=raw-boundary")
                .content(PayBizRequestBodyTest.multipart(5 * 1024 * 1024 + 1)))
        .andExpect(status().isPayloadTooLarge());
    assertEquals(0, calls.get());
  }

  @Test
  void unavailableUpstreamReturnsSafe502() throws Exception {
    upstream.stop(0);
    mvc.perform(
            post("/biz/pay/v2/onboardings")
                .header("Authorization", "Bearer btk_user")
                .contentType("application/json")
                .content("{\"requestNo\":\"original\"}"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.data").isEmpty());
  }

  @Test
  void timeoutAndInterruptionUseSafeStatusesAndNeverRetryTheWrite() throws Exception {
    var forwarder = mock(BizOnboardingForwarder.class);
    var boundary =
        MockMvcBuilders.standaloneSetup(new BizPayOnboardingController(forwarder))
            .setControllerAdvice(new PayBizExceptionHandler(), new ApiResponseWrapper())
            .build();
    when(forwarder.forward(any(), any(), any(), any()))
        .thenThrow(new java.net.http.HttpTimeoutException("private-internal-detail"));
    boundary
        .perform(
            put("/biz/pay/v2/onboardings/ONB/draft").contentType("application/json").content("{}"))
        .andExpect(status().isGatewayTimeout())
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private-internal-detail"))));
    verify(forwarder, times(1)).forward(any(), any(), any(), any());
    reset(forwarder);
    when(forwarder.forward(any(), any(), any(), any()))
        .thenThrow(new InterruptedException("private-internal-detail"));
    try {
      boundary
          .perform(post("/biz/pay/v2/onboardings").contentType("application/json").content("{}"))
          .andExpect(status().isBadGateway());
      assertTrue(Thread.currentThread().isInterrupted());
      verify(forwarder, times(1)).forward(any(), any(), any(), any());
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void switchOffDoesNotCreateRelayAndEnablingWithoutDbFailsAtStartup() {
    context.close();
    open(Map.of());
    assertTrue(context.getBeansOfType(BizOnboardingForwarder.class).isEmpty());
    assertTrue(context.getBeansOfType(BizPaySessionController.class).isEmpty());
    context.close();
    assertThrows(
        org.springframework.beans.BeansException.class,
        () -> open(Map.of("shop.pay.biz-onboarding.enabled", "true")));
  }

  @Test
  void originAndRawTargetValidationAreClosed() {
    for (String origin :
        List.of(
            "https://near-pay.test/path",
            "http://near-pay.test",
            "https://user@near-pay.test",
            "https://near-pay.test?q=1"))
      assertThrows(
          IllegalArgumentException.class, () -> new BizOnboardingForwarder(URI.create(origin)));
    for (String path :
        List.of(
            "https://other.test/biz/pay/v2/memberships",
            "//other.test/biz/pay/v2/memberships",
            "/biz/pay/v2/onboardings/../ONB",
            "/biz/pay/v2/onboardings/%4fNB",
            "/biz/pay/v2/memberships#fragment",
            "/biz/pay/v2/onboardings/ONB\\draft",
            "/pay-ops/v2/onboardings"))
      assertFalse(BizOnboardingForwarder.allowed("GET", path), path);
  }

  @Configuration(proxyBeanMethods = false)
  @EnableWebMvc
  @EnableWebSecurity
  static class Web {
    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    TokenStore tokenStore() {
      return mock(TokenStore.class);
    }

    @Bean
    BizIdentityResolver bizIdentityResolver() {
      return mock(BizIdentityResolver.class);
    }

    @Bean
    GuardFilters guardFilters() {
      var filters = mock(GuardFilters.class);
      when(filters.forEntry(any()))
          .thenAnswer(
              call ->
                  new GuardFilter(
                      call.getArgument(0), mock(EntryGuard.class), mock(RequestLogWriter.class)));
      return filters;
    }

    @Bean
    JdbcTemplate jdbcTemplate() {
      var ds =
          new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
      var jdbc = new JdbcTemplate(ds);
      jdbc.execute(
          "CREATE TABLE mch_session(token_hash VARCHAR PRIMARY KEY,user_no VARCHAR,subject_kind"
              + " VARCHAR,expires_at TIMESTAMP,revoked_at TIMESTAMP)");
      jdbc.execute(
          "CREATE TABLE usr_account(user_no VARCHAR PRIMARY KEY,status VARCHAR,tenant_no"
              + " VARCHAR,deleted INT)");
      jdbc.execute(
          "CREATE TABLE mch_account(mch_account_no VARCHAR PRIMARY KEY,status VARCHAR,tenant_no"
              + " VARCHAR,deleted INT)");
      jdbc.update("INSERT INTO usr_account VALUES ('SAME','NORMAL','MAIN',0)");
      jdbc.update("INSERT INTO mch_account VALUES ('SAME','ACTIVE','MAIN',0)");
      for (var pair : Map.of("btk_user", "USR", "btk_staff", "MCH").entrySet())
        jdbc.update(
            "INSERT INTO mch_session VALUES (?, 'SAME', ?, ?, NULL)",
            TokenHash.of(pair.getKey()),
            pair.getValue(),
            LocalDateTime.now().plusHours(1));
      return jdbc;
    }

    @Bean
    JdbcClient authJdbcClient(JdbcTemplate jdbc) {
      return JdbcClient.create(jdbc.getDataSource());
    }
  }
}
