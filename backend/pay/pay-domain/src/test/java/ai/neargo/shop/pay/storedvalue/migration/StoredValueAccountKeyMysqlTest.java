package ai.neargo.shop.pay.storedvalue.migration;

import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.spi.storedvalue.StoredValueMigrationReadiness.Proof;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Opt-in MySQL-only adapter/DDL acceptance. Global authorization is separately tested with real ledger fixtures. */
@EnabledIfEnvironmentVariable(named="SV_KEY_MYSQL_JDBC",matches=".+")
class StoredValueAccountKeyMysqlTest {
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    IssuerRepository db;
    StoredValueSchemaDdl ddl;
    StoredValueAccountKeyInspector inspector;
    @BeforeEach void setup()throws Exception{
        String url=System.getenv("SV_KEY_MYSQL_JDBC");
        if(url==null||!url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/sv_key_test_[a-z0-9]+\\?.*"))throw new IllegalArgumentException("Only an explicit isolated loopback test database is allowed");
        source=new DriverManagerDataSource(url,"root","");jdbc=new JdbcTemplate(source);
        for(String table:List.of("sv_release_tier","sv_release","sv_issuer_store_access","pay_ent_command_dedup","sv_account","sv_issuer","sv_issuer_version"))jdbc.execute("DROP TABLE IF EXISTS "+table);
        jdbc.execute("""
            CREATE TABLE sv_account(
              id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) NOT NULL,account_no VARCHAR(64) NOT NULL,
              user_no VARCHAR(64),entity_no VARCHAR(64) NOT NULL,store_no VARCHAR(64),customer_no VARCHAR(64),
              issuer_no VARCHAR(64),issuer_version_no VARCHAR(64),currency VARCHAR(8) NOT NULL,
              principal_minor BIGINT NOT NULL DEFAULT 0,bonus_minor BIGINT NOT NULL DEFAULT 0,account_seq BIGINT NOT NULL DEFAULT 0,deleted TINYINT NOT NULL DEFAULT 0,
              UNIQUE KEY uk_sv_account(tenant_no,account_no),
              UNIQUE KEY uk_sv_owner(tenant_no,user_no,entity_no,store_no,currency),
              UNIQUE KEY uk_sv_customer_owner(tenant_no,customer_no,entity_no,store_no,currency)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci
            """);
        jdbc.execute("CREATE TABLE sv_issuer(tenant_no VARCHAR(32),issuer_no VARCHAR(64),scope_type VARCHAR(24),scope_no VARCHAR(64),write_mode VARCHAR(24),deleted TINYINT,status VARCHAR(24) DEFAULT 'DRAFT',version BIGINT DEFAULT 0,updated_at DATETIME(3),updated_by VARCHAR(64)) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci");
        jdbc.execute("CREATE TABLE sv_issuer_version(tenant_no VARCHAR(32),issuer_no VARCHAR(64),issuer_version_no VARCHAR(64),currency VARCHAR(8),deleted TINYINT) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci");
        String migration=Files.readString(Path.of("../../shop-app/src/main/resources/db/migration/V478__entitlement_catalog.sql"));
        int start=migration.indexOf("CREATE TABLE IF NOT EXISTS pay_ent_command_dedup");
        jdbc.execute(migration.substring(start,migration.indexOf(';',start)));
        jdbc.update("INSERT INTO sv_issuer(tenant_no,issuer_no,scope_type,scope_no,write_mode,deleted) VALUES('MAIN','I1','STORE','S1','FENCED',0)");
        jdbc.update("INSERT INTO sv_issuer_version VALUES('MAIN','I1','IV1','CNY',0)");
        jdbc.update("INSERT INTO sv_account(tenant_no,account_no,user_no,entity_no,store_no,customer_no,issuer_no,issuer_version_no,currency,principal_minor,bonus_minor,account_seq) VALUES('MAIN','ORIGINAL','U1','E1','S1','P1','I1','IV1','CNY',11,3,1)");
        jdbc.execute("CREATE TABLE sv_issuer_store_access(tenant_no VARCHAR(32),issuer_no VARCHAR(64),store_no VARCHAR(64),status VARCHAR(24),can_issue INT DEFAULT 0,can_redeem INT DEFAULT 0,version BIGINT DEFAULT 0,updated_at DATETIME(3),updated_by VARCHAR(64),deleted TINYINT DEFAULT 0)");
        jdbc.update("INSERT INTO sv_issuer_store_access(tenant_no,issuer_no,store_no,status) VALUES('MAIN','I1','S1','PREPARING')");
        var manager=new DataSourceTransactionManager(source);db=new IssuerRepository(manager,new ObjectMapper());
        ddl=new StoredValueSchemaDdl(manager);inspector=new StoredValueAccountKeyInspector(db);
    }
    @Test void committedMysqlDdlSurvivesLostResponseAndDurableIntentRecoversWithoutReplay(){
        var authorization=mock(StoredValueSchemaAuthorization.class);
        var approval=new StoredValueSchemaAuthorization.Approval("DBONLY","OPERATOR","a".repeat(64),"b".repeat(64),Instant.now().plusSeconds(600),true,List.of());
        when(authorization.authorize(anyString(),anyString(),nullable(Proof.class))).thenReturn(new StoredValueSchemaAuthorization.Authorized(approval,"c".repeat(64),1,1));
        var interrupted=mock(StoredValueSchemaDdl.class);var failOnce=new AtomicBoolean(true);
        when(interrupted.locked(any())).thenAnswer(call->{
            Function<Consumer<String>,Object> action=call.getArgument(0);
            return ddl.locked(execute->action.apply(sql->{
                execute.accept(sql);
                if(failOnce.getAndSet(false))throw new IllegalStateException("simulated response lost after real DDL");
            }));
        });
        var runner=new StoredValueAccountKeyCutover(db,authorization,inspector,interrupted);
        assertThat(inspector.inspect().nextStep()).isEqualTo("ADD_ISSUER_KEY");
        assertThatThrownBy(()->runner.step("OPERATOR","DBONLY",null)).isInstanceOf(IllegalStateException.class);
        assertThat(inspector.inspect().nextStep()).isEqualTo("DROP_LEGACY_KEYS");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_SCHEMA_KEY_STEP' AND result_status='PENDING'",Long.class)).isEqualTo(1);
        var restarted=new StoredValueAccountKeyCutover(db,authorization,inspector,ddl);
        assertThat(restarted.step("OPERATOR","DBONLY",null).state()).isEqualTo("RECOVERED");
        assertThat(restarted.step("OPERATOR","DBONLY",null).state()).isEqualTo("APPLIED");
        assertThat(restarted.step("OPERATOR","DBONLY",null).state()).isEqualTo("KEYS_READY");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE result_status='PENDING'",Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT account_no FROM sv_account",String.class)).isEqualTo("ORIGINAL");
        assertThat(jdbc.queryForObject("SELECT principal_minor+bonus_minor FROM sv_account",Long.class)).isEqualTo(14);
        assertThat(jdbc.queryForObject("SELECT account_seq FROM sv_account",Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT write_mode FROM sv_issuer",String.class)).isEqualTo("FENCED");
        assertThatThrownBy(()->jdbc.update("INSERT INTO sv_account(tenant_no,account_no,user_no,entity_no,store_no,customer_no,issuer_no,issuer_version_no,currency,deleted) VALUES('MAIN','DUPLICATE','U2','E2','S2','p1','I1','IV1','CNY',1)"))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void actualMysqlLockExcludesCompetingSessionsAndReleasesAfterCompletion()throws Exception{
        String lock="CONCAT('sv-key-',SHA2(DATABASE(),224))";
        try(var connection=source.getConnection();var statement=connection.createStatement()){
            ddl.locked(execute->{
                try(var result=statement.executeQuery("SELECT GET_LOCK("+lock+",0)")){
                    assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isZero();
                }catch(java.sql.SQLException error){throw new IllegalStateException(error);}
                return null;
            });
            try(var result=statement.executeQuery("SELECT GET_LOCK("+lock+",0)")){assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isEqualTo(1);}
            try(var result=statement.executeQuery("SELECT RELEASE_LOCK("+lock+")")){assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isEqualTo(1);}
        }
    }

    private StoredValueSchemaAuthorization approved(){
        var authorization=mock(StoredValueSchemaAuthorization.class);
        var approval=new StoredValueSchemaAuthorization.Approval("DBONLY","OPERATOR","a".repeat(64),"b".repeat(64),Instant.now().plusSeconds(600),true,List.of());
        var allowed=new StoredValueSchemaAuthorization.Authorized(approval,"c".repeat(64),1,1);
        when(authorization.authorize(anyString(),anyString(),nullable(Proof.class))).thenReturn(allowed);
        when(authorization.authorizeApproval(anyString(),anyString(),nullable(Proof.class))).thenReturn(allowed);
        return authorization;
    }
    @Test void databaseDimensionsMustBeEnforcedBeforeStoreWritersResumeAndBrandDraftStaysClosed(){
        var authorization=approved();var keys=new StoredValueAccountKeyCutover(db,authorization,inspector,ddl);
        keys.step("OPERATOR","DBONLY",null);keys.step("OPERATOR","DBONLY",null);
        var dimensions=new StoredValueAccountDimensionGuard(db,authorization,inspector,ddl);
        var resume=new StoredValueMigrationResumeService(db,authorization,inspector,dimensions,ddl);
        assertThatThrownBy(()->resume.resume("OPERATOR","DBONLY",null)).isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThat(jdbc.queryForObject("SELECT write_mode FROM sv_issuer WHERE issuer_no='I1'",String.class)).isEqualTo("FENCED");
        String guard=dimensions.install("OPERATOR","DBONLY",null);assertThat(dimensions.install("OPERATOR","DBONLY",null)).isEqualTo(guard);
        assertThat(dimensions.installed()).isTrue();
        for(String[] values:List.of(new String[]{null,"IV1","P2"},new String[]{"I1",null,"P2"},new String[]{"I1","IV1"," "})) {
            assertThatThrownBy(()->jdbc.update("INSERT INTO sv_account(tenant_no,account_no,entity_no,store_no,issuer_no,issuer_version_no,customer_no,currency) VALUES('MAIN','INVALID','E1','S1',?,?,?,'CNY')",(Object[])values)).rootCause().isInstanceOf(java.sql.SQLException.class).extracting(error->((java.sql.SQLException)error).getErrorCode()).isEqualTo(3819);
        }
        jdbc.update("INSERT INTO sv_issuer(tenant_no,issuer_no,scope_type,scope_no,write_mode,status,deleted) VALUES('MAIN','BRAND_DRAFT','BRAND','B1','LEGACY','DRAFT',0)");
        assertThat(inspector.inspect().ready()).isTrue();
        var result=resume.resume("OPERATOR","DBONLY",null);
        assertThat(result.issuerCount()).isEqualTo(1);assertThat(resume.resume("OPERATOR","DBONLY",null)).isEqualTo(result);
        assertThat(jdbc.queryForObject("SELECT write_mode FROM sv_issuer WHERE issuer_no='I1'",String.class)).isEqualTo("ISSUER");
        assertThat(jdbc.queryForObject("SELECT status FROM sv_issuer WHERE issuer_no='BRAND_DRAFT'",String.class)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT write_mode FROM sv_issuer WHERE issuer_no='BRAND_DRAFT'",String.class)).isEqualTo("LEGACY");
        assertThat(jdbc.queryForObject("SELECT can_issue+can_redeem FROM sv_issuer_store_access WHERE issuer_no='I1'",Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT principal_minor+bonus_minor FROM sv_account WHERE account_no='ORIGINAL'",Long.class)).isEqualTo(14);
    }
    @Test void additiveLegacyActivityMigrationKeepsBothOldSourcesAndRejectsMissingFrozenVersion()throws Exception{
        String release=Files.readString(Path.of("../../shop-app/src/main/resources/db/migration/V549__stored_value_issuer_ledger_dimensions.sql"));
        for(String table:List.of("sv_release","sv_release_tier")){
            int start=release.indexOf("CREATE TABLE "+table+" (");jdbc.execute(release.substring(start,release.indexOf(';',start)));
        }
        String migration=Files.readString(Path.of("../../shop-app/src/main/resources/db/migration/V550__stored_value_legacy_activity_release.sql")).replaceAll("(?m)^--.*$","");
        try(var connection=source.getConnection();var statement=connection.createStatement()){for(String sql:migration.split(";"))if(!sql.isBlank())statement.execute(sql);}
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='sv_release' AND COLUMN_NAME='legacy_activity_version_no'",Long.class)).isEqualTo(1);
        String insert="INSERT INTO sv_release(tenant_no,release_no,issuer_no,issuer_version_no,activity_no,activity_version_no,legacy_activity_version_no,release_index,source_kind,currency,starts_at,state,redemption_scope_mode,scope_snapshot,policy_versions,content_hash,created_at,updated_at,created_by,updated_by) VALUES('MAIN',?,'I1','IV1','A1',?,'AV1',?,'LEGACY_ACTIVITY','CNY',CURRENT_TIMESTAMP,'ACTIVE','STORE_ONLY','{}','[]',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'OP','OP')";
        jdbc.update(insert,"REL1","AV1",1,"a".repeat(64));
        assertThatThrownBy(()->jdbc.update(insert,"REL2",null,2,"a".repeat(64))).rootCause().isInstanceOf(java.sql.SQLException.class).extracting(error->((java.sql.SQLException)error).getErrorCode()).isEqualTo(3819);
        assertThat(jdbc.queryForObject("SELECT source_kind FROM sv_release",String.class)).isEqualTo("LEGACY_ACTIVITY");
    }

    @Test void anotherApprovalCannotStrandAnExistingDimensionIntent(){
        var authorization=approved();var keys=new StoredValueAccountKeyCutover(db,authorization,inspector,ddl);keys.step("OPERATOR","DBONLY",null);keys.step("OPERATOR","DBONLY",null);
        jdbc.update("INSERT INTO pay_ent_command_dedup(tenant_no,operation_kind,request_key,payload_hash,resource_no,result_status,version,deleted,created_at,updated_at,created_by,updated_by) VALUES('__LEDGER__','SV_SCHEMA_DIMENSION',?,?,'OLD','PENDING',0,0,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'OPERATOR','OPERATOR')",StoredValueSchemaAuthorization.hash("OLD:DIMENSIONS"),"a".repeat(64));
        jdbc.execute(StoredValueAccountDimensionGuard.SQL);
        var dimensions=new StoredValueAccountDimensionGuard(db,authorization,inspector,ddl);
        assertThatThrownBy(()->dimensions.install("OPERATOR","DBONLY",null)).isInstanceOf(ai.neargo.shop.common.BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_command_dedup WHERE operation_kind='SV_SCHEMA_DIMENSION'",Long.class)).isEqualTo(1);
        var oldApproval=new StoredValueSchemaAuthorization.Approval("OLD","OPERATOR","a".repeat(64),"b".repeat(64),Instant.now().plusSeconds(600),true,List.of());var old=new StoredValueSchemaAuthorization.Authorized(oldApproval,"a".repeat(64),1,1);
        when(authorization.authorize("OPERATOR","OLD",null)).thenReturn(old);
        assertThat(dimensions.install("OPERATOR","OLD",null)).isEqualTo("OLD");
        assertThat(jdbc.queryForObject("SELECT result_status FROM pay_ent_command_dedup WHERE resource_no='OLD'",String.class)).isEqualTo("COMPLETED");
    }
}
