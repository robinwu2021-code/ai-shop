package ai.neargo.inventory.svc;

import ai.neargo.inventory.domain.config.InventoryFlywayDataSource;
import ai.neargo.inventory.domain.policy.ExecutionStockRetentionSchemaVerifier;
import ai.neargo.inventory.domain.policy.ExecutionStockRetentionSchemaVerifier.Installation;
import ai.neargo.inventory.domain.service.InboundService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.inventory.domain.service.ServicePlatformService;
import java.sql.DriverManager;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.assertj.core.api.Assertions.*;

/** Setup admin only provisions users; migrations and every assertion use separate non-admin accounts. */
@EnabledIfEnvironmentVariable(named="INVENTORY_DISPOSITION_NATIVE",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RetentionMetadataPrivilegesNativeMysqlTest {
    private final String token=UUID.randomUUID().toString().replace("-", "");
    // An apostrophe also exercises schema-qualified CALL identifier handling.
    private final String catalog="invmeta'"+token;
    private final String migrator="inv_m_"+token.substring(0,20);
    private final String runtime="inv_r_"+token.substring(0,20);
    private final String migrationPassword=UUID.randomUUID().toString();
    private final String runtimePassword=UUID.randomUUID().toString();
    private final String adminUrl=System.getenv("INVENTORY_DISPOSITION_TEST_URL");
    private final String adminUser=System.getenv("INVENTORY_DISPOSITION_TEST_USER");
    private final String adminPassword=System.getenv("INVENTORY_DISPOSITION_TEST_PASSWORD");
    private ServletWebServerApplicationContext context;
    private Connection migrationConnection;
    private JdbcTemplate migration;
    private JdbcTemplate runtimeDb;

    @BeforeAll void open() throws Exception {
        try (var admin=DriverManager.getConnection(adminUrl,adminUser,adminPassword); var sql=admin.createStatement()) {
            try(var version=sql.executeQuery("SELECT VERSION()")){version.next();assertThat(version.getString(1)).startsWith("9.");}
            sql.execute("CREATE DATABASE `"+catalog+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_520_ci");
            sql.execute("CREATE USER '"+migrator+"'@'127.0.0.1' IDENTIFIED BY '"+migrationPassword+"'");
            sql.execute("CREATE USER '"+runtime+"'@'127.0.0.1' IDENTIFIED BY '"+runtimePassword+"'");
            sql.execute("GRANT ALL ON `"+catalog+"`.* TO '"+migrator+"'@'127.0.0.1'");
            // Bootstrap old frozen trigger migrations under this server's binlog policy only.
            sql.execute("GRANT SUPER ON *.* TO '"+migrator+"'@'127.0.0.1'");
            sql.execute("GRANT SELECT,INSERT,UPDATE,DELETE ON `"+catalog+"`.* TO '"+runtime+"'@'127.0.0.1'");
        }
        String url=adminUrl.substring(0,adminUrl.lastIndexOf('/')+1)
                + java.net.URLEncoder.encode(catalog,java.nio.charset.StandardCharsets.UTF_8)
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";
        var migrationSource=new DriverManagerDataSource(url,migrator,migrationPassword);
        Flyway.configure().dataSource(new InventoryFlywayDataSource(migrationSource))
                .locations("classpath:db/inventory").table("inv_flyway_history").target("27").load().migrate();
        migrationConnection=migrationSource.getConnection();
        migration=new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(migrationConnection,true));
        try (var admin=DriverManager.getConnection(adminUrl,adminUser,adminPassword); var sql=admin.createStatement()) {
            sql.execute("REVOKE SUPER ON *.* FROM '"+migrator+"'@'127.0.0.1'");
        }
        // Fresh Flyway connections have schema-only grants: V28 itself requires no SUPER.
        Flyway.configure().dataSource(new InventoryFlywayDataSource(migrationSource))
                .locations("classpath:db/inventory").table("inv_flyway_history").load().migrate();
        try (var admin=DriverManager.getConnection(adminUrl,adminUser,adminPassword); var sql=admin.createStatement()) {
            sql.execute("GRANT EXECUTE ON PROCEDURE `"+catalog+"`.inv_execution_retention_trigger_metadata TO '"+runtime+"'@'127.0.0.1'");
            sql.execute("ALTER USER '"+migrator+"'@'127.0.0.1' ACCOUNT LOCK");
        }
        var app=new SpringApplication(InventoryApplication.class);
        app.addInitializers(c->c.getEnvironment().getPropertySources().addFirst(new MapPropertySource("dml-only",Map.of(
                "server.port",0,"server.address","127.0.0.1","inventory.datasource.url",url,
                "inventory.datasource.username",runtime,"inventory.datasource.password",runtimePassword,
                "inventory.flyway-enabled",false,"inventory.instance.code","SHOP",
                "inventory.instance.allowed-systems","SHOP","inventory.jobs.enabled",false))));
        context=(ServletWebServerApplicationContext)app.run();
        runtimeDb=new JdbcTemplate(context.getBean("invDataSource",DataSource.class));
    }

    @AfterAll void close() throws Exception {
        if(context!=null)context.close();
        if(migrationConnection!=null)migrationConnection.close();
        try(var admin=DriverManager.getConnection(adminUrl,adminUser,adminPassword);var sql=admin.createStatement()) {
            sql.execute("DROP DATABASE IF EXISTS `"+catalog+"`");
            sql.execute("DROP USER IF EXISTS '"+runtime+"'@'127.0.0.1'");
            sql.execute("DROP USER IF EXISTS '"+migrator+"'@'127.0.0.1'");
        }
    }
    private ExecutionStockRetentionSchemaVerifier verifier(){return context.getBean(ExecutionStockRetentionSchemaVerifier.class);}

    @Test void dmlRuntimeWithOnlyMetadataExecuteSeesRealFactsAndCanReserveWithoutTriggerOrDdlPrivilege() throws Exception {
        assertThat(runtimeDb.queryForObject("SELECT CURRENT_USER()",String.class)).startsWith(runtime+"@");
        assertThat(runtimeDb.queryForObject("SELECT COUNT(*) FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE()",Integer.class)).isZero();
        assertThat(runtimeDb.queryForList("CALL inv_execution_retention_trigger_metadata()")).hasSize(4);
        try(var connection=context.getBean("invDataSource",DataSource.class).getConnection()) {
            String initial=connection.getCatalog();
            try {
                connection.setCatalog("information_schema");
                try(var call=connection.prepareStatement("CALL `"+catalog+"`.inv_execution_retention_trigger_metadata()");
                    var rows=call.executeQuery()) {
                    int count=0;
                    while(rows.next()) { assertThat(rows.getString("TRIGGER_SCHEMA")).isEqualTo(catalog); count++; }
                    assertThat(count).isEqualTo(4);
                }
            } finally { connection.setCatalog(initial); }
        }
        assertThat(migration.queryForObject("SELECT DEFINER FROM information_schema.ROUTINES WHERE ROUTINE_SCHEMA=DATABASE() AND ROUTINE_NAME='inv_execution_retention_trigger_metadata'",String.class))
                .startsWith(migrator+"@");
        assertThat(verifier().installation()).isEqualTo(Installation.INSTALLED);
        assertThatThrownBy(()->runtimeDb.execute("DROP TRIGGER trg_ierc_no_delete")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->runtimeDb.execute("ALTER PROCEDURE inv_execution_retention_trigger_metadata SQL SECURITY INVOKER"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->runtimeDb.execute("CREATE TRIGGER forbidden_trigger BEFORE DELETE ON inv_owner FOR EACH ROW SIGNAL SQLSTATE '45000'"))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        String owner=context.getBean(ServicePlatformService.class).openOwner("SHOP",null,"DML_OWNER","DML fixture","IT");
        String location=context.getBean(LocationService.class).createWarehouse(owner,"Store","IT");
        runtimeDb.update("INSERT INTO inv_item(item_id,owner_id,name,base_uom) VALUES('DML_ITEM',?,'DML fixture','EA')",owner);
        context.getBean(InboundService.class).postDirectly(new InboundService.Draft(owner,location,"PURCHASE",
                "DML_SEED",null,null,LocalDateTime.now(),null,List.of(new InboundService.Line("DML_ITEM",5,"EA",100L))),"IT");
        String reservation=context.getBean(ReservationService.class).reserve(owner,"DML_ORDER",
                List.of(new ReservationService.Line("DML_ITEM",location,2,"DML_F","DML_SKU",null)),3600,1);
        assertThat(reservation).isNotBlank();
        assertThat(runtimeDb.queryForObject("SELECT reserved FROM inv_stock_balance WHERE owner_id=?",Integer.class,owner)).isEqualTo(2);
        assertThat(verifier().installation()).isEqualTo(Installation.INSTALLED);
    }

    @Test void droppedOrChangedTriggerAndMissingMetadataPermissionFailClosedForTheSameDmlAccount() throws Exception {
        String body=migration.queryForObject("SELECT ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME='trg_ierc_no_delete'",String.class);
        migration.execute("DROP TRIGGER trg_ierc_no_delete");
        try {
            assertThat(verifier().installation()).isEqualTo(Installation.CORRUPT);
            migration.execute("CREATE TRIGGER trg_ierc_no_delete BEFORE DELETE ON inv_execution_retention_claim FOR EACH ROW BEGIN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='changed trigger body'; END");
            assertThat(runtimeDb.queryForList("CALL inv_execution_retention_trigger_metadata()")).hasSize(4);
            assertThat(verifier().installation()).isEqualTo(Installation.CORRUPT);
        } finally {
            migration.execute("DROP TRIGGER IF EXISTS trg_ierc_no_delete");
            migration.execute("CREATE TRIGGER trg_ierc_no_delete BEFORE DELETE ON inv_execution_retention_claim FOR EACH ROW "+body);
        }
        assertThat(verifier().installation()).isEqualTo(Installation.INSTALLED);
        try(var admin=DriverManager.getConnection(adminUrl,adminUser,adminPassword);var statement=admin.createStatement()) {
            statement.execute("REVOKE EXECUTE ON PROCEDURE `"+catalog+"`.inv_execution_retention_trigger_metadata FROM '"+runtime+"'@'127.0.0.1'");
            try { assertThat(verifier().installation()).isEqualTo(Installation.CORRUPT); }
            finally { statement.execute("GRANT EXECUTE ON PROCEDURE `"+catalog+"`.inv_execution_retention_trigger_metadata TO '"+runtime+"'@'127.0.0.1'"); }
        }
        assertThat(verifier().installation()).isEqualTo(Installation.INSTALLED);
    }
}
