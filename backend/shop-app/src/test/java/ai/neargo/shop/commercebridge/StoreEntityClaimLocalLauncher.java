package ai.neargo.shop.commercebridge;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.neargo.commerce.core.subscription.StoreRelinkService;
import ai.neargo.commerce.persistence.CommerceMigrationRunner;
import ai.neargo.commerce.persistence.JdbcStoreRelinkRepository;
import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.*;
import ai.neargo.shop.merchant.paysync.NearPayStoreProfile;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import ai.neargo.shop.merchant.paysync.StoreSyncProperties;
import ai.neargo.shop.merchant.service.MerchantPlanService;
import ai.neargo.shop.merchant.service.impl.MerchantPlanServiceImpl;
import ai.neargo.shop.merchant.service.impl.StoreOwnershipWritePortImpl;
import ai.neargo.shop.merchantbridge.*;
import ai.neargo.shop.payclient.PayClientConfig;
import ai.neargo.shop.platform.OpsService;
import ai.neargo.shop.platform.port.StoreRelinkHostFactsPortImpl;
import ai.neargo.shop.portal.biz.BizStoreClaimRecoveryController;
import ai.neargo.shop.portal.biz.BizStoreEntityController;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.apache.catalina.startup.Tomcat;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import tools.jackson.databind.ObjectMapper;

/**
 * Test classpath only. Real DB sessions/security/claim policy/commerce and production PAY HTTP client.
 * Unrelated portal dependencies retain the existing fixture's stubs; no mocked history or PAY facts.
 * The schema is deliberately preserved for process restart and must be dropped by the local runner.
 */
public final class StoreEntityClaimLocalLauncher {
    private StoreEntityClaimLocalLauncher() {}

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("store.claim.local.acceptance"))
            throw new IllegalStateException("Explicit local claim acceptance opt-in required");
        String mysql = required("COMMERCE_MYSQL_URL");
        if (!mysql.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/\\?.*"))
            throw new IllegalArgumentException("Loopback MySQL 9 at 127.0.0.1:3308 required");
        loopback(required("N1_PAY_ORIGIN"));
        required("N1_INTERNAL_TOKEN"); required("N1_PAY_CREDENTIAL");
        String existing = System.getenv("N1_HOST_SCHEMA");
        if (existing != null && !existing.matches("commerce_test_[0-9a-f]{32}"))
            throw new IllegalArgumentException("Only the previous disposable host schema may be resumed");
        String password = existing == null ? System.getenv().getOrDefault("N1_OWNER_PASSWORD", UUID.randomUUID().toString())
                : required("N1_OWNER_PASSWORD");
        Path ready = Path.of(required("N1_HOST_READY_FILE")).toAbsolutePath();
        var fixture = new BizLoginStoreNativeMysqlTest().new LoginFixture(existing, true,
                f -> install(f, existing == null));
        var tomcat = new Tomcat();
        Path scratch = Files.createTempDirectory("n1-host-http-");
        AtomicBoolean closed = new AtomicBoolean();
        var syncControl = new java.util.concurrent.atomic.AtomicReference<com.sun.net.httpserver.HttpServer>();
        Runnable close = () -> {
            if (!closed.compareAndSet(false, true)) return;
            if (syncControl.get() != null) syncControl.get().stop(0);
            try { tomcat.stop(); tomcat.destroy(); }
            catch (Exception failure) { System.err.println("Local claim HTTP shutdown incomplete"); }
            finally {
                fixture.close(); // closes auth caches; intentionally retains the restart schema
                try {
                    Files.deleteIfExists(ready);
                    try (var paths = Files.walk(scratch)) {
                        for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
                    }
                } catch (IOException failure) { System.err.println("Local claim scratch cleanup incomplete"); }
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(close, "n1-host-cleanup"));
        try {
            if (!fixture.db.jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("9."))
                throw new IllegalStateException("MySQL 9 required");
            if (existing == null) {
                fixture.ownerPhone("15965040911");
                fixture.auth.setPassword("U", password);
                seed(fixture);
            }
            tomcat.setBaseDir(scratch.toString());
            tomcat.setPort(Integer.parseInt(System.getenv().getOrDefault("N1_HOST_PORT", "0")));
            tomcat.getConnector().setProperty("address", "127.0.0.1");
            var container = tomcat.addContext("", scratch.toString());
            container.setParentClassLoader(StoreEntityClaimLocalLauncher.class.getClassLoader());
            container.addServletContainerInitializer((classes, servlet) -> {
                fixture.web.setServletContext(servlet);
                servlet.addFilter("springSecurityFilterChain", new DelegatingFilterProxy("springSecurityFilterChain", fixture.web))
                        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
                var dispatcher = servlet.addServlet("dispatcher", new DispatcherServlet(fixture.web));
                dispatcher.addMapping("/"); dispatcher.setLoadOnStartup(1);
            }, Set.of());
            // Execute the real owner query before publishing readiness: missing history is fatal.
            fixture.web.getBean(ai.neargo.shop.spi.user.StoreRelinkHostFactsPort.class).inspect("MAIN", "M", "S");
            fixture.web.getBean(ai.neargo.shop.spi.user.StoreRelinkHostFactsPort.class).inspect("MAIN", "M2", "S2");
            tomcat.start();
            var control = syncControl(fixture);
            syncControl.set(control);
            var snapshot = new StoreSnapshotReader(fixture.db.ds).read("S").orElseThrow();
            Map<String,Object> state = new LinkedHashMap<>();
            state.put("hostOrigin", "http://127.0.0.1:" + tomcat.getConnector().getLocalPort());
            state.put("schema", fixture.db.schema); state.put("databasePreserved", true);
            state.put("syncControlOrigin", "http://127.0.0.1:" + control.getAddress().getPort());
            state.put("syncConsumerHook", true); state.put("outboxWorkerInstalled", false);
            state.put("historyInspected", true);
            state.put("principal", "15965040911"); state.put("password", password);
            state.put("ownerRef", "U"); state.put("sourceEntityNo", "M"); state.put("targetEntityNo", "T");
            state.put("storeNo", "S"); state.put("storeRef", Long.toString(snapshot.storeId()));
            state.put("sourceScope", "MAIN"); state.put("sourceCaller", "AI_SHOP_UME");
            state.put("storeVersion", fixture.db.jdbc.queryForObject("SELECT version FROM mch_store WHERE store_no='S'", Long.class));
            state.put("expectedPayRevision", snapshot.revision() + NearPayStoreProfile.PROFILE_VERSION);
            state.put("initialProjection", NearPayStoreProfile.request(snapshot, fixture.web.getBean(StoreSyncProperties.class)));
            var secondary = new StoreSnapshotReader(fixture.db.ds).read("S2").orElseThrow();
            state.put("secondary", Map.of("sourceEntityNo", "M2", "targetEntityNo", "T", "storeNo", "S2",
                    "storeRef", Long.toString(secondary.storeId()),
                    "storeVersion", fixture.db.jdbc.queryForObject("SELECT version FROM mch_store WHERE store_no='S2'", Long.class),
                    "expectedPayRevision", secondary.revision() + NearPayStoreProfile.PROFILE_VERSION));
            state.put("secondaryInitialProjection", NearPayStoreProfile.request(secondary, fixture.web.getBean(StoreSyncProperties.class)));
            state.put("pendingClaims", fixture.db.jdbc.queryForList("SELECT claim_no,request_no,state FROM mch_store_entity_claim ORDER BY id"));
            Path temporary = Files.createTempFile(ready.getParent(), ".n1-host-ready-", ".json",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(temporary, new ObjectMapper().writeValueAsString(state));
            Files.move(temporary, ready, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            System.out.println("N1_HOST_LOCAL_READY");
            new CountDownLatch(1).await();
        } finally { close.run(); }
    }

    private static void install(BizLoginStoreNativeMysqlTest.LoginFixture f, boolean fresh) {
        install(f, fresh, required("N1_PAY_ORIGIN"), required("N1_PAY_CREDENTIAL"), required("N1_INTERNAL_TOKEN"), true);
    }

    /** Shared test-only host bootstrap; N1 retains its original opt-in and environment contract. */
    static void install(BizLoginStoreNativeMysqlTest.LoginFixture f, boolean fresh,
            String origin, String credential, String internalToken, boolean storeClaims) {
        try {
            if (fresh) {
                historySchema(f);
                production(f, "V544__brand_management_organization.sql", "mch_brand_store_binding", "mch_brand_change");
                production(f, "V576__stored_value_financial_membership_saga.sql", "mch_brand_financial_change");
                new CommerceMigrationRunner(f.db.ds).migrate();
                new ResourceDatabasePopulator(new ClassPathResource("db/migration/V645__merchant_login_ticket.sql")).execute(f.db.ds);
            }
            // Fail startup on incomplete owned history instead of interpreting a missing table as clean.
            for (String table : List.of("ord_order", "ord_sub_order", "ord_offline_record", "ord_after_sale",
                    "mch_brand_store_binding", "mch_brand_change", "mch_brand_financial_change", "mch_store_entity_claim",
                    "mch_subscription_account", "mch_subscription_period", "usr_login_ticket",
                    "mch_service_area", "mch_fulfillment_channel", "mch_entity_community")) f.db.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            var sync = new StoreSyncProperties(true, origin, "AI_SHOP_UME", credential, "MAIN", "TW", null,
                    Duration.ofSeconds(60), Duration.ofSeconds(5));
            var locator = new ConfigServiceLocator(); locator.getTargets().put("PAY", origin);
            var commerce = new StoreRelinkService(new JdbcStoreRelinkRepository(f.db.ds), new ShopStoreRelinkAuthority(f.db.jdbc));
            var ops = mock(OpsService.class);
            doAnswer(call -> {
                f.db.jdbc.update("INSERT INTO sys_audit_log(staff_no,op_action,target,detail,at,created_at,critical,before_json,after_json) VALUES('U',?,?,?,0,NOW(),1,?,?)",
                        call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(4), call.getArgument(5));
                return null;
            }).when(ops).audit(anyString(),anyString(),anyString(),eq(true),anyString(),anyString());
            f.realServices.put(OpsService.class, ops);
            // Profile must query the selected real entity; unrelated C-side discovery ports are not exercised.
            f.realServices.put(ai.neargo.shop.merchant.service.MerchantService.class,
                    new ai.neargo.shop.merchant.service.impl.MerchantServiceImpl(
                            f.db.mybatis.getMapper(MchEntityMapper.class),
                            mock(ai.neargo.shop.spi.trade.PurchaseHistoryPort.class), new ObjectMapper(),
                            f.db.mybatis.getMapper(MchStoreMapper.class),
                            mock(ai.neargo.shop.merchant.service.impl.EntityReachability.class)));
            var messages = new org.springframework.context.support.ResourceBundleMessageSource();
            messages.setBasename("i18n/messages"); messages.setDefaultEncoding("UTF-8");
            var identities = new ai.neargo.shop.merchantbridge.LoginIdentityServiceImpl(
                    f.auth, new ai.neargo.shop.user.service.impl.LoginTicketServiceImpl(f.db.jdbc, f.db.manager, Duration.ofMinutes(5)),
                    f.tokens, mock(ai.neargo.shop.auth.LoginAuditor.class),
                    f.db.mybatis.getMapper(MchAccountMapper.class), f.db.mybatis.getMapper(MchEntityMapper.class),
                    f.db.mybatis.getMapper(MchStoreMapper.class), f.db.mybatis.getMapper(MchStoreRoleMapper.class),
                    f.db.mybatis.getMapper(ai.neargo.shop.user.mapper.UserMappers.IdentityMapper.class),
                    f.db.mybatis.getMapper(ai.neargo.shop.user.mapper.UserMappers.UserMapper.class), messages);
            f.realServices.put(ai.neargo.shop.merchant.service.LoginIdentityService.class, identities);
            Map<String,Object> props = new LinkedHashMap<>();
            props.put("shop.merchant.store-claim.enabled", Boolean.toString(storeClaims)); props.put("shop.pay.deployment", "standalone");
            props.put("shop.pay.internal-caller", "AI_SHOP_UME");
            props.put("shop.pay.internal-caller-credentials[AI_SHOP_UME]", credential);
            props.put("shop.services.internal-token", internalToken);
            props.put("shop.store.max-per-entity", "3");
            f.web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("n1LocalOnly", props));
            f.web.register(PayClientConfig.class, InternalHttp.class, StoreRelinkHostFactsPortImpl.class,
                    StoreRelinkLocalPolicy.class, StoreRelinkPrecheck.class, StoreEntityClaimServiceImpl.class,
                    BizStoreEntityController.class, BizStoreClaimRecoveryController.class, MerchantPlanServiceImpl.class,
                    StoreOwnershipWritePortImpl.class, ApiResponseWrapper.class);
            f.web.addBeanFactoryPostProcessor(beans -> {
                beans.registerSingleton("n1Json", new ObjectMapper());
                beans.registerSingleton("n1Jdbc", f.db.jdbc); beans.registerSingleton("n1StoreSync", sync);
                beans.registerSingleton("n1ServiceLocator", locator); beans.registerSingleton("n1CommerceRelink", commerce);
                beans.registerSingleton("n1Snapshots", new StoreSnapshotReader(f.db.ds));
                beans.registerSingleton("entityPlans", f.db.mybatis.getMapper(EntityPlanMapper.class));
                beans.registerSingleton("planDefinitions", f.db.mybatis.getMapper(PlanDefMapper.class));
            });
        } catch (IOException e) { throw new IllegalStateException("Cannot load local production schema", e); }
    }

    static void seed(BizLoginStoreNativeMysqlTest.LoginFixture f) {
        f.db.jdbc.update("UPDATE mch_entity SET status='PENDING_LICENSE',market='TW' WHERE entity_no='M'");
        f.db.jdbc.update("INSERT INTO mch_entity(entity_no,name,status,market,created_at,updated_at) VALUES('T','local target','ACTIVE','TW',NOW(),NOW()),('M2','second placeholder','PENDING_LICENSE','TW',NOW(),NOW())");
        f.db.jdbc.update("INSERT INTO mch_account(mch_account_no,user_no,entity_no,is_owner,status,created_at,updated_at) VALUES('TA','U','T',1,'ACTIVE',NOW(),NOW()),('STAFF',NULL,'M',0,'ACTIVE',NOW(),NOW()),('MA2','U','M2',1,'ACTIVE',NOW(),NOW()),('STAFF2',NULL,'M2',0,'ACTIVE',NOW(),NOW())");
        f.db.jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at) VALUES('STAFF','S','CLERK',NOW(),NOW()),('STAFF2','S2','CLERK',NOW(),NOW())");
        f.db.jdbc.update("UPDATE mch_store SET status='ACTIVE',version=7 WHERE store_no='S'");
        f.db.jdbc.update("INSERT INTO mch_store(entity_no,store_no,name,status,is_default,commerce_model,business_type_code,version,created_at,updated_at) VALUES('M2','S2','second local store','ACTIVE',1,'STORE_V1','RETAIL',7,NOW(),NOW())");
        f.db.jdbc.update("INSERT INTO sys_merchant_plan_def(plan_code,name,store_quota,staff_quota,created_at,updated_at) VALUES('FREE','local free',3,10,NOW(),NOW())");
        f.db.jdbc.update("INSERT INTO mch_entity_plan(entity_no,plan_code,store_quota,staff_quota,status,created_at,updated_at) VALUES('M','FREE',3,10,'ACTIVE',NOW(),NOW()),('M2','FREE',3,10,'ACTIVE',NOW(),NOW()),('T','FREE',3,10,'ACTIVE',NOW(),NOW())");
        f.db.jdbc.update("INSERT INTO mch_subscription_account(tenant_no,store_no,entity_no,business_type_code,entitlement_version,created_at,updated_at,version,deleted) VALUES('MAIN','S','M','RETAIL',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0),('MAIN','S2','M2','RETAIL',0,UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0)");
        f.db.jdbc.update("""
                INSERT INTO mch_subscription_period(tenant_no,period_no,store_no,entity_no,business_type_code,stream_key,source_type,source_ref_no,tier_code,delivered_at,delivery_seq,revoked_duration_ms,original_start_at,effective_start_at,eligible_basis_minor,cash_basis_minor,credit_basis_minor,currency,state,source_policy_snapshot,created_at,updated_at,version,deleted)
                VALUES('MAIN','FREE-S','S','M','RETAIL','FREE','FREE','init-S','FREE',1,1,0,1,1,0,0,0,'TWD','NORMAL','{}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0),('MAIN','FREE-S2','S2','M2','RETAIL','FREE','FREE','init-S2','FREE',1,1,0,1,1,0,0,0,'TWD','NORMAL','{}',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3),0,0)
                """);
    }


    /** Test-only delivery trigger; real consumer reads current DB snapshot and calls real loopback PAY. */
    private static com.sun.net.httpserver.HttpServer syncControl(BizLoginStoreNativeMysqlTest.LoginFixture f) throws IOException {
        var server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var consumer = new ai.neargo.shop.merchant.paysync.NearPayStoreSyncConsumer(
                new StoreSnapshotReader(f.db.ds), f.web.getBean(StoreSyncProperties.class), required("N1_INTERNAL_TOKEN"));
        server.createContext("/sync", exchange -> {
            int code = 400;
            String answer = "{\"status\":\"REJECTED\"}";
            String query = exchange.getRequestURI().getRawQuery();
            if (!required("N1_INTERNAL_TOKEN").equals(exchange.getRequestHeaders().getFirst("X-Local-Control"))) code = 403;
            else if ("POST".equals(exchange.getRequestMethod()) && ("storeNo=S".equals(query) || "storeNo=S2".equals(query))) {
                String store = query.substring("storeNo=".length());
                if (f.db.jdbc.queryForObject("SELECT COUNT(*) FROM mch_store_entity_claim WHERE store_no=? AND state='COMPLETED'", Integer.class, store) == 0) code = 409;
                else {
                    try {
                        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
                            throw new IllegalStateException("Projection delivery cannot run inside claim transaction");
                        var event = new ai.neargo.shop.event.SysOutbox(); event.setAggregateId(store);
                        consumer.consume(event);
                        code = 200; answer = "{\"status\":\"CONSUMER_INVOKED\"}";
                    } catch (RuntimeException failure) { code = 502; answer = "{\"status\":\"DELIVERY_FAILED\"}"; }
                }
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, bytes.length);
            try (var response = exchange.getResponseBody()) { response.write(bytes); }
        });
        server.start(); return server;
    }

    /** Physical owner facts only, extracted from frozen production SQL without H2 conversion. */
    private static void historySchema(BizLoginStoreNativeMysqlTest.LoginFixture f) throws IOException {
        production(f, "V1__baseline.sql", "ord_order", "ord_sub_order", "ord_after_sale", "mkt_coupon", "prd_goods", "prd_sku");
        production(f, "V637__ord_offline_record.sql", "ord_offline_record");
        production(f, "V599__checkout_source_foundation.sql", "ord_checkout_operation", "ord_checkout_confirmation");
        production(f, "V495__entitlement_service_order.sql", "ord_ent_service_order");
        production(f, "V612__external_certificate_consumption.sql", "ord_external_certificate_use");
        production(f, "V231__pmt_coupon.sql", "pmt_apply", "pmt_coupon", "pmt_user_coupon", "pmt_coupon_issue");
        production(f, "V562__industry_order_source_binding.sql", "ord_industry_order_source");
        production(f, "V607__industry_channel_order_admission.sql", "ord_channel_import");
        production(f, "V608__industry_channel_admission_guard.sql", "ord_channel_admission_guard");
        production(f, "V527__legacy_coupon_issue_projection.sql", "pmt_pay_coupon_projection");
        production(f, "V523__legacy_coupon_source_commit.sql", "pmt_migration_decision");
        production(f, "V453__order_points_binding.sql", "ord_points_binding");
        production(f, "V454__order_points_source_facts.sql", "ord_points_source_fact");
        production(f, "V13__store_stock.sql", "prd_store_stock");
        production(f, "V15__store_goods.sql", "prd_store_goods");
        production(f, "V170__store_price.sql", "prd_store_price");
        production(f, "V279__goods_draft.sql", "prd_goods_draft");
        production(f, "V33__service_area.sql", "mch_service_area");
        production(f, "V187__store_fulfillment_channel.sql", "mch_fulfillment_channel");
        production(f, "V1__baseline.sql", "mch_entity_community");
        // Retain original full ALTER constraints; only these physical scope additions are needed.
        firstAlter(f, "V601__manual_service_admission.sql", "ord_order");
        firstAlter(f, "V602__shared_manual_order_customer_party.sql", "ord_order");
        firstAlter(f, "V602__shared_manual_order_customer_party.sql", "ord_checkout_confirmation");
        firstAlter(f, "V384__goods_store_no.sql", "prd_goods");
        firstAlter(f, "V381__service_area_store_no.sql", "mch_service_area");
    }

    private static void firstAlter(BizLoginStoreNativeMysqlTest.LoginFixture f, String migration, String table) throws IOException {
        String sql = new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8);
        var match = Pattern.compile("(?s)ALTER TABLE\\s+" + Pattern.quote(table) + "\\s+.*?;").matcher(sql);
        if (!match.find()) throw new IllegalStateException("Missing production ALTER: " + migration + "/" + table);
        f.db.jdbc.execute(mysqlDdl(match.group()));
    }

    private static String mysqlDdl(String sql) {
        // Execution-only adaptation of frozen MariaDB-era collation. JSON/CHECK/ENGINE stay original.
        return sql.replace("utf8mb4_uca1400_ai_ci", "utf8mb4_0900_ai_ci");
    }

    private static void production(BizLoginStoreNativeMysqlTest.LoginFixture f,String migration,String... tables) throws IOException {
        String sql = new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8);
        for (String table : tables) {
            var match = Pattern.compile("(?s)CREATE TABLE(?: IF NOT EXISTS)?\\s+" + Pattern.quote(table) + "\\s*\\(.*?\n\\)(?:\\s*ENGINE[^;]*)?\\s*;").matcher(sql);
            if (!match.find()) throw new IllegalStateException("Missing production CREATE: " + table);
            f.db.jdbc.execute(mysqlDdl(match.group()));
        }
    }
    private static void loopback(String value) {
        URI uri=URI.create(value);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost()) || uri.getPort()<1
                || uri.getUserInfo()!=null || uri.getQuery()!=null || uri.getFragment()!=null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath())))
            throw new IllegalArgumentException("PAY origin must be a fixed loopback HTTP origin");
    }
    private static String required(String name) {
        String value=System.getenv(name);
        if (value==null || value.isBlank()) throw new IllegalArgumentException("Required local environment: " + name);
        return value;
    }
}
