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
        for(String table:List.of("pay_ent_command_dedup","sv_account","sv_issuer","sv_issuer_version"))jdbc.execute("DROP TABLE IF EXISTS "+table);
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
        jdbc.execute("CREATE TABLE sv_issuer(tenant_no VARCHAR(32),issuer_no VARCHAR(64),scope_type VARCHAR(24),scope_no VARCHAR(64),write_mode VARCHAR(24),deleted TINYINT) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci");
        jdbc.execute("CREATE TABLE sv_issuer_version(tenant_no VARCHAR(32),issuer_no VARCHAR(64),issuer_version_no VARCHAR(64),currency VARCHAR(8),deleted TINYINT) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci");
        String migration=Files.readString(Path.of("../../shop-app/src/main/resources/db/migration/V478__entitlement_catalog.sql"));
        int start=migration.indexOf("CREATE TABLE IF NOT EXISTS pay_ent_command_dedup");
        jdbc.execute(migration.substring(start,migration.indexOf(';',start)));
        jdbc.update("INSERT INTO sv_issuer VALUES('MAIN','I1','STORE','S1','FENCED',0)");
        jdbc.update("INSERT INTO sv_issuer_version VALUES('MAIN','I1','IV1','CNY',0)");
        jdbc.update("INSERT INTO sv_account(tenant_no,account_no,user_no,entity_no,store_no,customer_no,issuer_no,issuer_version_no,currency,principal_minor,bonus_minor,account_seq) VALUES('MAIN','ORIGINAL','U1','E1','S1','P1','I1','IV1','CNY',11,3,1)");
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
}
