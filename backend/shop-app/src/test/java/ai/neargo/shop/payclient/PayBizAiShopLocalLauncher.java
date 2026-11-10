package ai.neargo.shop.payclient;

import ai.neargo.auth.store.TokenHash;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.config.PayBizOnboardingConfiguration;
import ai.neargo.shop.config.SecurityConfig;
import ai.neargo.shop.portal.biz.BizPayOnboardingController;
import ai.neargo.shop.portal.biz.BizPaySessionController;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import org.apache.catalina.startup.Tomcat;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;

/** Test classpath only: real merchant SQL/session chain and relay, isolated loopback fixtures. */
public final class PayBizAiShopLocalLauncher {
  @TestConfiguration(proxyBeanMethods = false)
  static class LocalDatabase {
    @Bean
    JdbcTemplate jdbcTemplate(Environment environment) {
      return new JdbcTemplate(
          new DriverManagerDataSource(
              environment.getRequiredProperty("host-test-db-url"),
              System.getenv("ALIGNMENT_MYSQL_USER"),
              System.getenv("ALIGNMENT_MYSQL_PASSWORD")));
    }

    @Bean
    TokenStore tokenStore() {
      return new MemoryTokenStore(Duration.ofHours(1));
    }
  }

  /** Standard operator-chain-authenticated callback; no login bypass or merchant claim input. */
  @TestComponent
  @RestController
  static class LocalOperatorIdentity {
    @GetMapping("/ops/auth/me")
    public Map<String, Object> me() {
      var user = SecurityUtils.requireUser();
      var result = new LinkedHashMap<String, Object>();
      result.put("staffNo", user.userNo());
      result.put("username", user.userNo() + "@local.test");
      result.put("realName", user.nickname());
      result.put("status", "ACTIVE");
      result.put("roles", user.roles());
      result.put("perms", user.perms());
      result.put("merchantNo", null);
      result.put("communityNo", null);
      result.put("pickupNo", null);
      return result;
    }
  }

  public static void main(String[] args) throws Exception {
    if (!Boolean.getBoolean("nearpay.aishop.local.acceptance"))
      throw new IllegalStateException("explicit local acceptance opt-in required");
    String database = System.getenv("ALIGNMENT_HOST_JDBC_URL");
    if (database == null || !database.startsWith("jdbc:mysql://127.0.0.1:3308/ai_shop_align_"))
      throw new IllegalArgumentException("isolated loopback MySQL 9 schema required");
    int port = Integer.parseInt(System.getenv("ALIGNMENT_HOST_PORT"));
    var context = new AnnotationConfigWebApplicationContext();
    context.setAllowBeanDefinitionOverriding(true);
    context.getEnvironment().setActiveProfiles("api", "ops");
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource(
                "alignment",
                Map.of(
                    "shop.pay.biz-onboarding.enabled",
                    "true",
                    "shop.auth.token-store-by-realm.merchant",
                    "db",
                    "shop.pay.biz-onboarding.base-url",
                    System.getenv("ALIGNMENT_PAY_ORIGIN"),
                    "host-test-db-url",
                    database)));
    context.register(
        PayBizOnboardingHttpTest.Web.class,
        LocalDatabase.class,
        LocalOperatorIdentity.class,
        SecurityConfig.class,
        PayBizOnboardingConfiguration.class,
        BizPaySessionController.class,
        BizPayOnboardingController.class,
        PayBizExceptionHandler.class,
        ApiResponseWrapper.class,
        GlobalExceptionHandler.class);
    var tomcat = new Tomcat();
    Path temp = Files.createTempDirectory("ai-shop-alignment-tomcat-");
    tomcat.setBaseDir(temp.toString());
    tomcat.setPort(port);
    tomcat.getConnector().setProperty("address", "127.0.0.1");
    var container = tomcat.addContext("", temp.toString());
    container.setParentClassLoader(PayBizAiShopLocalLauncher.class.getClassLoader());
    container.addServletContainerInitializer(
        (classes, servlet) -> {
          context.setServletContext(servlet);
          context.refresh();
          servlet
              .addFilter(
                  "springSecurityFilterChain",
                  new DelegatingFilterProxy("springSecurityFilterChain", context))
              .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
          var dispatcher = servlet.addServlet("dispatcher", new DispatcherServlet(context));
          dispatcher.addMapping("/");
          dispatcher.setLoadOnStartup(1);
        },
        java.util.Set.of());
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  try {
                    tomcat.stop();
                    tomcat.destroy();
                  } catch (org.apache.catalina.LifecycleException cleanupFailure) {
                    System.err.println("Local Tomcat shutdown incomplete");
                  } finally {
                    context.close();
                    try (var files = Files.walk(temp)) {
                      for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList())
                        Files.deleteIfExists(file);
                    } catch (java.io.IOException cleanupFailure) {
                      System.err.println("Local Tomcat scratch cleanup incomplete");
                    }
                  }
                }));
    tomcat.start();
    JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
    if (!jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("9."))
      throw new IllegalStateException("MySQL 9 required");
    jdbc.execute(
        "CREATE TABLE mch_session(token_hash CHAR(64) PRIMARY KEY,user_no VARCHAR(64),subject_kind"
            + " VARCHAR(8),expires_at DATETIME,revoked_at DATETIME)");
    jdbc.execute(
        "CREATE TABLE usr_account(user_no VARCHAR(64) PRIMARY KEY,status VARCHAR(16),tenant_no"
            + " VARCHAR(32),deleted INT)");
    jdbc.execute(
        "CREATE TABLE mch_account(mch_account_no VARCHAR(64) PRIMARY KEY,status"
            + " VARCHAR(16),tenant_no VARCHAR(32),deleted INT)");
    jdbc.update(
        "INSERT INTO usr_account VALUES"
            + " ('SAME','NORMAL','MAIN',0),('MCH-ADMIN','NORMAL','MAIN',0),('OTHER','NORMAL','OTHER',0)");
    jdbc.update(
        "INSERT INTO mch_account VALUES"
            + " ('MCH-ADMIN','ACTIVE','MAIN',0),('CASHIER','ACTIVE','MAIN',0),('SAME','ACTIVE','MAIN',0)");
    var ready = new LinkedHashMap<String, Object>();
    ready.put("hostOrigin", "http://127.0.0.1:" + port);
    String[][] users = {
      {"userToken", "USR", "SAME"},
      {"staffToken", "MCH", "MCH-ADMIN"},
      {"sameRefStaffToken", "MCH", "SAME"},
      {"wrongTypeToken", "USR", "MCH-ADMIN"},
      {"cashierToken", "MCH", "CASHIER"},
      {"otherTenantToken", "USR", "OTHER"},
      {"expiredToken", "USR", "SAME"}
    };
    for (String[] user : users) {
      String token = TokenStore.newToken(ai.neargo.shop.auth.Realm.MERCHANT);
      jdbc.update(
          "INSERT INTO mch_session VALUES(?,?,?,?,NULL)",
          TokenHash.of(token),
          user[2],
          user[1],
          LocalDateTime.now().plusHours(user[0].equals("expiredToken") ? -1 : 1));
      ready.put(user[0], token);
    }
    TokenStore operators = context.getBean(TokenStore.class);
    ready.put(
        "opsToken",
        operators.issue(
            TokenStore.SessionData.of(
                LoginUser.operator(
                    "alignment-operator", "本机录入员", List.of("SUPER_ADMIN"), List.of("*")))));
    ready.put(
        "reviewerToken",
        operators.issue(
            TokenStore.SessionData.of(
                LoginUser.operator(
                    "alignment-reviewer", "本机复核员", List.of("SUPER_ADMIN"), List.of("*")))));
    Path file = Path.of(System.getenv("ALIGNMENT_HOST_READY_FILE"));
    Files.createFile(
        file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    new ObjectMapper().writeValue(file.toFile(), ready);
    System.out.println("AI_SHOP_ALIGNMENT_READY");
    new CountDownLatch(1).await();
  }
}
