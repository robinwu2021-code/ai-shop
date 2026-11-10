package ai.neargo.shop.commercebridge;

import ai.neargo.shop.common.ApiResponseWrapper;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementTokenCodec;
import ai.neargo.shop.merchant.brand.impl.BrandServiceImpl;
import ai.neargo.shop.merchant.brand.impl.ManagementQueryServiceImpl;
import ai.neargo.shop.merchant.brand.impl.ManagementScopeServiceImpl;
import ai.neargo.shop.merchant.brand.impl.PaymentMerchantScopeServiceImpl;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import ai.neargo.shop.merchantbridge.port.BrandChangeImpactFacade;
import ai.neargo.shop.numbering.DocumentOwner;
import ai.neargo.shop.numbering.store.DocumentNumberCoordinator;
import ai.neargo.shop.numbering.store.DocumentNumberProperties;
import ai.neargo.shop.numbering.store.JdbcDocumentNumberRepository;
import ai.neargo.shop.numbering.store.SegmentDocumentNumberGenerator;
import ai.neargo.shop.pay.mapper.storedvalue.SvOrganizationImpactMapper;
import ai.neargo.shop.pay.storedvalue.service.impl.StoredValueOrganizationImpactServiceImpl;
import ai.neargo.shop.portal.biz.BizBrandController;
import ai.neargo.shop.portal.biz.BizManagementController;
import ai.neargo.shop.portal.biz.BizStoreOperatingContextController;
import jakarta.servlet.DispatcherType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZoneId;
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
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.filter.DelegatingFilterProxy;
import org.springframework.web.servlet.DispatcherServlet;
import tools.jackson.databind.ObjectMapper;

/** Test-classpath only: native MySQL 9, real credential login and P1 services over loopback HTTP. */
public final class CommerceChainLocalLauncher {
    private CommerceChainLocalLauncher() {}

    public static void main(String[] args) throws Exception {
        if (!Boolean.getBoolean("commerce.chain.local.acceptance"))
            throw new IllegalStateException("explicit local chain acceptance opt-in required");
        String url = System.getenv("COMMERCE_MYSQL_URL");
        if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/\\?.*"))
            throw new IllegalArgumentException("loopback MySQL 9 at 127.0.0.1:3308 required");
        Path readyFile = Path.of(System.getenv("COMMERCE_CHAIN_READY_FILE")).toAbsolutePath();
        int port = Integer.parseInt(System.getenv().getOrDefault("COMMERCE_CHAIN_PORT", "0"));
        var json = new ObjectMapper();
        var fixture = new BizLoginStoreNativeMysqlTest().new LoginFixture(
                CommerceChainLocalLauncher::installManagement);
        var tomcat = new Tomcat();
        Path scratch = Files.createTempDirectory("commerce-chain-local-");
        AtomicBoolean closed = new AtomicBoolean();
        Runnable cleanup = () -> {
            if (!closed.compareAndSet(false, true)) return;
            try {
                tomcat.stop();
                tomcat.destroy();
            } catch (LifecycleException failure) {
                System.err.println("Local chain HTTP shutdown incomplete");
            } finally {
                fixture.close();
                try {
                    Files.deleteIfExists(readyFile);
                    try (var files = Files.walk(scratch)) {
                        for (Path file : files.sorted(Comparator.reverseOrder()).toList())
                            Files.deleteIfExists(file);
                    }
                } catch (IOException failure) {
                    System.err.println("Local chain scratch cleanup incomplete");
                }
            }
        };
        Runtime.getRuntime().addShutdownHook(new Thread(cleanup, "commerce-chain-cleanup"));
        try {
            if (!fixture.db.jdbc.queryForObject("SELECT VERSION()", String.class).startsWith("9."))
                throw new IllegalStateException("MySQL 9 required");
            String ownerPhone = "15965040901";
            String staffPhone = "15965040902";
            String password = UUID.randomUUID().toString();
            fixture.ownerPhone(ownerPhone);
            fixture.auth.setPassword("U", password);
            fixture.secondEntity();
            fixture.db.jdbc.update("UPDATE mch_entity SET market='TW' WHERE entity_no IN ('M','M2')");
            fixture.db.jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,display_name,login_phone,created_at,updated_at)"
                    + " VALUES('STAFF','M','local delegated administrator',?,NOW(),NOW())", staffPhone);
            fixture.db.jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                    + " VALUES('M','LOCAL_ADMIN','local administrator','[\"biz:finance\"]',NOW(),NOW())");
            fixture.db.jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                    + " VALUES('STAFF','S','LOCAL_ADMIN',NOW(),NOW())");
            fixture.db.jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,display_name,login_phone,created_at,updated_at)"
                    + " VALUES('SWITCH','M2','local independent identity',?,NOW(),NOW())", ownerPhone);
            fixture.db.jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                    + " VALUES('SWITCH','S2','CLERK',NOW(),NOW())");
            // Local authorization facts, not sessions: both clients still log in through real HTTP credentials.
            var scopes = fixture.web.getBean(ManagementScopeServiceImpl.class);
            Map<String, Object> space = scopes.create(new Actor("MAIN", "USER:U"), "Local native management", "MA", "local-native-space");
            String management = (String) space.get("managementNo");
            var owner = new Actor("MAIN", "USER:U");
            for (String permission : List.of("management.enter", "brand.manage"))
                scopes.grant(owner, management, new ai.neargo.shop.merchant.brand.ManagementModels.GrantCommand(
                        "MCH:STAFF", "SPACE", management, permission, null, null, false, null, false));
            // Read and configure S2 without STAFF membership there; management grants are the authority.
            for (String permission : List.of("store.read", "store.brand.change"))
                scopes.grant(owner, management, new ai.neargo.shop.merchant.brand.ManagementModels.GrantCommand(
                        "MCH:STAFF", "STORE", "S2", permission, null, null, false, null, false));
            fixture.postJson("/biz/auth/otp/send", Map.of("phone", staffPhone), null);
            String staffOtp = fixture.otp.peek(staffPhone).orElseThrow();

            tomcat.setBaseDir(scratch.toString());
            tomcat.setPort(port);
            tomcat.getConnector().setProperty("address", "127.0.0.1");
            var container = tomcat.addContext("", scratch.toString());
            container.setParentClassLoader(CommerceChainLocalLauncher.class.getClassLoader());
            container.addServletContainerInitializer((classes, servlet) -> {
                fixture.web.setServletContext(servlet);
                servlet.addFilter("springSecurityFilterChain", new DelegatingFilterProxy("springSecurityFilterChain", fixture.web))
                        .addMappingForUrlPatterns(EnumSet.of(DispatcherType.REQUEST), false, "/*");
                var dispatcher = servlet.addServlet("dispatcher", new DispatcherServlet(fixture.web));
                dispatcher.addMapping("/");
                dispatcher.setLoadOnStartup(1);
            }, Set.of());
            tomcat.start();
            Map<String, Object> ready = new LinkedHashMap<>();
            ready.put("hostOrigin", "http://127.0.0.1:" + tomcat.getConnector().getLocalPort());
            ready.put("ownerPrincipal", ownerPhone);
            ready.put("ownerPassword", password);
            ready.put("staffPrincipal", staffPhone);
            ready.put("staffOtp", staffOtp);
            ready.put("managementNo", management);
            ready.put("sourceAccountNo", "MA");
            ready.put("storeNo", "S");
            ready.put("crossStoreNo", "S2");
            ready.put("entityNo", "M");
            ready.put("database", fixture.db.schema);
            ready.put("ownerRef", "U");
            ready.put("linkedStaffRef", "SWITCH");
            ready.put("linkedStaffIdentity", "STAFF-SWITCH");
            Files.createFile(readyFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(readyFile, json.writeValueAsString(ready));
            System.out.println("COMMERCE_CHAIN_LOCAL_READY");
            new CountDownLatch(1).await();
        } finally {
            cleanup.run();
        }
    }

    private static void installManagement(BizLoginStoreNativeMysqlTest.LoginFixture fixture) {
        alignHostFixtureCollation(fixture);
        // Native acceptance uses frozen production DDL, retaining generated columns, UNIQUEs and CHECKs.
        // The two Pay tables are the exact original ledger structures needed by the read-only impact query.
        createFromMigration(fixture, "V498__stored_value_credit_ledger.sql", "sv_account", "sv_recharge");
        migrate(fixture, "V532__stored_value_store_accounts.sql");
        migrate(fixture, "V399__sys_sequence.sql");
        migrate(fixture, "V542__document_numbers.sql");
        migrate(fixture, "V544__brand_management_organization.sql");
        migrate(fixture, "V573__brand_financial_activation_fence.sql");
        createFromMigration(fixture, "V548__stored_value_issuer_foundation.sql", "sv_issuer");
        createFromMigration(fixture, "V576__stored_value_financial_membership_saga.sql", "mch_brand_financial_change");
        // A fresh local schema has no legacy outbox rows to migrate; use the original journal CREATE only.
        createFromMigration(fixture, "V622__management_command_journal.sql", "mch_management_command");
        migrate(fixture, "V645__merchant_login_ticket.sql");
        var tickets = new ai.neargo.shop.user.service.impl.LoginTicketServiceImpl(
                fixture.db.jdbc, fixture.db.manager, java.time.Duration.ofMinutes(5));
        var messages = new org.springframework.context.support.ResourceBundleMessageSource();
        messages.setBasename("i18n/messages");
        messages.setDefaultEncoding("UTF-8");
        var identities = new ai.neargo.shop.merchantbridge.LoginIdentityServiceImpl(
                fixture.auth, tickets, fixture.tokens, org.mockito.Mockito.mock(ai.neargo.shop.auth.LoginAuditor.class),
                fixture.db.mybatis.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper.class),
                fixture.db.mybatis.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper.class),
                fixture.db.mybatis.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper.class),
                fixture.db.mybatis.getMapper(ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreRoleMapper.class),
                fixture.db.mybatis.getMapper(ai.neargo.shop.user.mapper.UserMappers.IdentityMapper.class),
                fixture.db.mybatis.getMapper(ai.neargo.shop.user.mapper.UserMappers.UserMapper.class), messages);
        fixture.realServices.put(ai.neargo.shop.merchant.service.LoginIdentityService.class, identities);
        fixture.web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("localBizRelay", Map.of(
                "shop.pay.biz-onboarding.enabled", "true",
                "shop.auth.token-store-by-realm.merchant", "db",
                "shop.pay.biz-onboarding.base-url", System.getenv().getOrDefault("COMMERCE_CHAIN_PAY_ORIGIN", "http://127.0.0.1:1"))));
        fixture.web.register(ai.neargo.shop.config.PayBizOnboardingConfiguration.class,
                ai.neargo.shop.portal.biz.BizPaySessionController.class,
                ai.neargo.shop.portal.biz.BizPayOnboardingController.class,
                ai.neargo.shop.payclient.PayBizExceptionHandler.class);
        fixture.web.addBeanFactoryPostProcessor(beans -> beans.registerSingleton("authJdbcClient",
                org.springframework.jdbc.core.simple.JdbcClient.create(fixture.db.ds)));

        ObjectMapper json = new ObjectMapper();
        var repository = new ManagementRepository(fixture.db.jdbc, fixture.db.manager, json);
        var scopes = new ManagementScopeServiceImpl(repository);
        var codec = new ManagementTokenCodec(UUID.randomUUID().toString(), json);
        var numberRepository = new JdbcDocumentNumberRepository(DocumentOwner.CORE, fixture.db.manager);
        var generator = new SegmentDocumentNumberGenerator(numberRepository,
                new DocumentNumberProperties(ZoneId.of("Asia/Shanghai"), 10));
        var numbers = new DocumentNumberCoordinator(numberRepository, generator, fixture.db.manager);
        fixture.db.mybatis.getConfiguration().addMapper(SvOrganizationImpactMapper.class);
        var impact = new StoredValueOrganizationImpactServiceImpl(fixture.db.mybatis.getMapper(SvOrganizationImpactMapper.class));
        fixture.web.getEnvironment().getPropertySources().addFirst(new MapPropertySource("localChain", Map.of("shop.pay.deployment", "embedded")));
        fixture.web.register(ManagementAccess.class, BrandChangeImpactFacade.class, BizManagementController.class,
                BizBrandController.class, BizStoreOperatingContextController.class, ApiResponseWrapper.class, BrandServiceImpl.class);
        fixture.web.addBeanFactoryPostProcessor(beans -> {
            beans.registerSingleton("managementRepository", repository);
            beans.registerSingleton("managementScopeService", scopes);
            beans.registerSingleton("managementTokenCodec", codec);
            beans.registerSingleton("managementQueryService", new ManagementQueryServiceImpl(repository, scopes, codec));
            beans.registerSingleton("paymentMerchantScopeService", new PaymentMerchantScopeServiceImpl(repository, scopes));
            beans.registerSingleton("storedValueOrganizationImpactService", impact);
            beans.registerSingleton("jdbcTemplate", fixture.db.jdbc);
            beans.registerSingleton("coreDocumentNumberCoordinator", numbers);
        });
    }

    private static void alignHostFixtureCollation(BizLoginStoreNativeMysqlTest.LoginFixture fixture) {
        // AccessMysqlFixture inherits MySQL 9's default 0900 collation. Its host rows join the
        // frozen P1 unicode_520 tables, so align only this disposable launcher database first.
        fixture.db.jdbc.execute("ALTER DATABASE `" + fixture.db.schema
                + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_520_ci");
        List<String> tables = fixture.db.jdbc.queryForList(
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA=? AND TABLE_TYPE='BASE TABLE'",
                String.class, fixture.db.schema);
        for (String table : tables)
            fixture.db.jdbc.execute("ALTER TABLE `" + table.replace("`", "``")
                    + "` CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_520_ci");
    }

    private static void migrate(BizLoginStoreNativeMysqlTest.LoginFixture fixture, String migration) {
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/" + migration)).execute(fixture.db.ds);
    }

    private static void createFromMigration(BizLoginStoreNativeMysqlTest.LoginFixture fixture,
            String migration, String... tables) {
        try {
            String sql = new ClassPathResource("db/migration/" + migration).getContentAsString(StandardCharsets.UTF_8);
            for (String table : tables) {
                var create = Pattern.compile("(?s)CREATE TABLE " + Pattern.quote(table)
                        + "\\s*\\(.*?\n\\)\\s*ENGINE[^;]*;").matcher(sql);
                if (!create.find()) throw new IllegalStateException("Missing production CREATE: " + migration + "/" + table);
                new ResourceDatabasePopulator(new ByteArrayResource(create.group().getBytes(StandardCharsets.UTF_8)))
                        .execute(fixture.db.ds);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load production migration " + migration, failure);
        }
    }

}
