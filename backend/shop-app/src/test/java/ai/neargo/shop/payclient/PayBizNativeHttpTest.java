package ai.neargo.shop.payclient;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.config.PayBizOnboardingConfiguration;
import ai.neargo.shop.config.SecurityConfig;
import ai.neargo.shop.portal.biz.BizPayCollectionController;
import ai.neargo.shop.portal.biz.BizPayOnboardingController;
import ai.neargo.shop.portal.biz.BizPaySessionController;
import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.DispatcherType;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;

/** Real MySQL 9 + embedded Servlet transport. Synthetic identities, never a channel acceptance. */
@EnabledIfEnvironmentVariable(
    named = "NEARPAY_HOST_MYSQL_URL",
    matches = "jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class PayBizNativeHttpTest {
  @Configuration(proxyBeanMethods = false)
  static class NativeDatabase {
    @Bean
    JdbcTemplate jdbcTemplate(org.springframework.core.env.Environment env) {
      return new JdbcTemplate(
          new DriverManagerDataSource(
              env.getRequiredProperty("host-test-db-url"),
              System.getenv("NEARPAY_HOST_MYSQL_USER"),
              System.getenv("NEARPAY_HOST_MYSQL_PASSWORD")));
    }
  }

  @Test
  void mysqlSessionAndRealServletUploadEnforceTheHandoffBoundary() throws Exception {
    String adminUrl = System.getenv("NEARPAY_HOST_MYSQL_URL");
    var admin =
        new JdbcTemplate(
            new DriverManagerDataSource(
                adminUrl,
                System.getenv("NEARPAY_HOST_MYSQL_USER"),
                System.getenv("NEARPAY_HOST_MYSQL_PASSWORD")));
    assertTrue(admin.queryForObject("SELECT VERSION()", String.class).startsWith("9."));
    String schema = "nearpay_host_test_" + UUID.randomUUID().toString().replace("-", "");
    admin.execute("CREATE DATABASE " + schema);
    var database = adminUrl.replace("/?", "/" + schema + "?");
    if (database.equals(adminUrl)) database = adminUrl + schema;
    var jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                database,
                System.getenv("NEARPAY_HOST_MYSQL_USER"),
                System.getenv("NEARPAY_HOST_MYSQL_PASSWORD")));
    var calls = new AtomicInteger();
    var received = new AtomicReference<byte[]>();
    var upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    byte[] binary = {0, -1, 2, 8, 0};
    upstream.createContext(
        "/",
        exchange -> {
          calls.incrementAndGet();
          received.set(exchange.getRequestBody().readAllBytes());
          exchange.getResponseHeaders().set("Content-Type", "image/png");
          exchange.getResponseHeaders().set("Cache-Control", "no-store");
          exchange.sendResponseHeaders(200, binary.length);
          exchange.getResponseBody().write(binary);
          exchange.close();
        });
    upstream.start();
    var context = new AnnotationConfigWebApplicationContext();
    var tomcat = new Tomcat();
    var temp = Files.createTempDirectory("nearpay-host-servlet-");
    try {
      jdbc.execute(
          "CREATE TABLE mch_session(token_hash CHAR(64) PRIMARY KEY,user_no"
              + " VARCHAR(64),subject_kind VARCHAR(8),expires_at DATETIME,revoked_at DATETIME)");
      jdbc.execute(
          "CREATE TABLE usr_account(user_no VARCHAR(64) PRIMARY KEY,status VARCHAR(16),tenant_no"
              + " VARCHAR(32),deleted INT)");
      jdbc.execute(
          "CREATE TABLE mch_account(mch_account_no VARCHAR(64) PRIMARY KEY,status"
              + " VARCHAR(16),tenant_no VARCHAR(32),deleted INT)");
      jdbc.update("INSERT INTO usr_account VALUES ('SAME','NORMAL','MAIN',0)");
      jdbc.update("INSERT INTO mch_account VALUES ('SAME','ACTIVE','MAIN',0)");
      for (var pair : Map.of("btk_user", "USR", "btk_staff", "MCH").entrySet())
        jdbc.update(
            "INSERT INTO mch_session VALUES (?, 'SAME', ?, ?, NULL)",
            TokenHash.of(pair.getKey()),
            pair.getValue(),
            LocalDateTime.now().plusHours(1));
      context.setAllowBeanDefinitionOverriding(true);
      context.getEnvironment().setActiveProfiles("api");
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "native",
                  Map.of(
                      "shop.pay.biz-onboarding.enabled",
                      "true",
                      "shop.auth.token-store-by-realm.merchant",
                      "db",
                      "shop.pay.biz-onboarding.base-url",
                      "http://127.0.0.1:" + upstream.getAddress().getPort(),
                      "host-test-db-url",
                      database)));
      context.register(
          PayBizOnboardingHttpTest.Web.class,
          NativeDatabase.class,
          SecurityConfig.class,
          PayBizOnboardingConfiguration.class,
          BizPaySessionController.class,
          BizPayOnboardingController.class,
          BizPayCollectionController.class,
          PayBizExceptionHandler.class,
          ApiResponseWrapper.class,
          GlobalExceptionHandler.class);
      tomcat.setBaseDir(temp.toString());
      tomcat.setPort(0);
      tomcat.getConnector();
      var container = tomcat.addContext("", temp.toString());
      container.setParentClassLoader(getClass().getClassLoader());
      container.addServletContainerInitializer(
          (classes, servlet) -> {
            context.setServletContext(servlet);
            context.refresh();
            servlet
                .addFilter(
                    "springSecurityFilterChain",
                    new DelegatingFilterProxy("springSecurityFilterChain", context))
                .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
            var registration = servlet.addServlet("dispatcher", new DispatcherServlet(context));
            registration.addMapping("/");
            registration.setLoadOnStartup(1);
          },
          Set.of());
      tomcat.start();
      String origin = "http://127.0.0.1:" + tomcat.getConnector().getLocalPort();
      try (var client = HttpClient.newHttpClient()) {
        var user =
            send(
                client,
                HttpRequest.newBuilder(URI.create(origin + "/biz/auth/session"))
                    .header("Authorization", "Bearer btk_user")
                    .GET()
                    .build());
        assertEquals(200, user.statusCode());
        assertTrue(
            new String(user.body(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"subjectType\":\"USER\""));
        var staff =
            send(
                client,
                HttpRequest.newBuilder(URI.create(origin + "/biz/auth/session"))
                    .header("Authorization", "Bearer btk_staff")
                    .GET()
                    .build());
        assertTrue(
            new String(staff.body(), java.nio.charset.StandardCharsets.UTF_8)
                .contains("\"subjectType\":\"MCH_ACCOUNT\""));
        byte[] upload = PayBizRequestBodyTest.multipart(2 * 1024 * 1024);
        var response = send(client, upload(origin, upload));
        assertEquals(200, response.statusCode());
        assertArrayEquals(upload, received.get());
        assertArrayEquals(binary, response.body());
        assertEquals("image/png", response.headers().firstValue("Content-Type").orElseThrow());
        assertEquals(
            413,
            send(client, upload(origin, PayBizRequestBodyTest.multipart(5 * 1024 * 1024 + 1)))
                .statusCode());
        assertEquals(1, calls.get());
        jdbc.update(
            "UPDATE mch_session SET revoked_at=NOW() WHERE token_hash=?", TokenHash.of("btk_user"));
        assertEquals(401, send(client, upload(origin, upload)).statusCode());
        jdbc.update("UPDATE mch_account SET status='DISABLED'");
        assertEquals(
            403,
            send(
                    client,
                    HttpRequest.newBuilder(URI.create(origin + "/biz/auth/session"))
                        .header("Authorization", "Bearer btk_staff")
                        .GET()
                        .build())
                .statusCode());
        jdbc.execute("DROP TABLE mch_session");
        assertEquals(
            503,
            send(
                    client,
                    HttpRequest.newBuilder(URI.create(origin + "/biz/auth/session"))
                        .header("Authorization", "Bearer btk_staff")
                        .GET()
                        .build())
                .statusCode());
      }
    } finally {
      try {
        tomcat.stop();
        tomcat.destroy();
      } finally {
        context.close();
        upstream.stop(0);
        admin.execute("DROP DATABASE " + schema);
        try (var paths = Files.walk(temp)) {
          for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
            Files.deleteIfExists(path);
        }
      }
    }
  }

  private HttpRequest upload(String origin, byte[] bytes) {
    // Unknown content length exercises the streaming limit, not a Content-Length shortcut.
    return HttpRequest.newBuilder(URI.create(origin + "/biz/pay/v2/onboardings/ONB/files"))
        .header("Authorization", "Bearer btk_user")
        .header("X-Merchant-No", "PAYMENT-M")
        .header("Content-Type", "multipart/form-data; boundary=raw-boundary")
        .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(bytes)))
        .build();
  }

  private HttpResponse<byte[]> send(HttpClient client, HttpRequest request) throws Exception {
    return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
  }
}
