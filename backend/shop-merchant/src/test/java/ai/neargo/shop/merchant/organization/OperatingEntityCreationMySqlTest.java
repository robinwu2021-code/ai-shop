package ai.neargo.shop.merchant.organization;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.shop.merchant.organization.impl.OperatingEntityServiceImpl;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.organization.OperatingEntityModels.*;
import java.nio.file.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="OPERATING_MYSQL_URL", matches=".+")
class OperatingEntityCreationMySqlTest {
    JdbcTemplate admin, jdbc;
    String schema;
    OperatingEntityService service;
    final Actor owner = new Actor("MAIN","USER:U1");
    @BeforeEach void setup() throws Exception {
        String url=System.getenv("OPERATING_MYSQL_URL");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:3308/?"));
        String user=System.getenv("OPERATING_MYSQL_USER"), password=System.getenv("OPERATING_MYSQL_PASSWORD");
        admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        schema="operating_create_"+UUID.randomUUID().toString().replace("-","");
        admin.execute("CREATE DATABASE "+schema+" CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_520_ci");
        var ds=new DriverManagerDataSource(url.replace("/?","/"+schema+"?"),user,password);
        jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE usr_account(tenant_no varchar(32),user_no varchar(64),market varchar(8),status varchar(16),deleted int,PRIMARY KEY(tenant_no,user_no))");
        jdbc.execute("CREATE TABLE mch_account(tenant_no varchar(32),mch_account_no varchar(64),user_no varchar(64),entity_no varchar(64),is_owner boolean,status varchar(16),deleted int,PRIMARY KEY(tenant_no,mch_account_no))");
        jdbc.execute("CREATE TABLE mch_entity(id bigint auto_increment primary key,tenant_no varchar(32),entity_no varchar(64),name varchar(100),market varchar(8),legal_form varchar(16),version bigint,status varchar(16),verified boolean,deleted int)");
        String migration=Files.readString(Path.of("../shop-app/src/main/resources/db/migration/V648__operating_entity_create.sql"));
        for (String sql:migration.replaceAll("(?m)^--.*$","").split(";")) if (!sql.isBlank()) jdbc.execute(sql);
        jdbc.update("INSERT INTO usr_account VALUES ('MAIN','U1','TW','NORMAL',0),('MAIN','U2','TW','NORMAL',0),('OTHER','U1','TW','NORMAL',0)");
        jdbc.update("INSERT INTO mch_account VALUES ('MAIN','MA1','U1','L1',1,'ACTIVE',0),('MAIN','STAFF','U1','L1',0,'ACTIVE',0),('MAIN','MA2','U2','L2',1,'ACTIVE',0),('MAIN','MA3','U1','CN1',1,'ACTIVE',0)");
        jdbc.update("INSERT INTO mch_entity(tenant_no,entity_no,name,market,legal_form,version,status,verified,deleted) VALUES ('MAIN','L1','本人证照','TW','COMPANY',0,'ACTIVE',1,0),('MAIN','L2','别人证照','TW','COMPANY',0,'ACTIVE',1,0),('MAIN','CN1','大陆证照','CN','COMPANY',0,'ACTIVE',1,0)");
        service=new OperatingEntityServiceImpl(new ManagementRepository(jdbc,new DataSourceTransactionManager(ds),new ObjectMapper()));
    }
    @AfterEach void cleanup() { if (schema!=null) admin.execute("DROP DATABASE "+schema); }
    Create draft(String legal) { return new Create("  经营主体  ",Market.TW,legal); }
    void rejects(ErrorCode code,Runnable action) { assertEquals(code,assertThrows(BizException.class,action::run).errorCode()); }
    @Test void requestValidationRejectsBlankNameAndMissingMarket() {
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            assertFalse(validator.validate(new Create(" ",Market.TW,null)).isEmpty());
            assertFalse(validator.validate(new Create("valid",null,null)).isEmpty());
            assertTrue(validator.validate(draft(null)).isEmpty());
        }
    }
    @Test void accountWithoutStoreCanCreateAndReturnToItsOwnDirectory() {
        assertTrue(service.context(owner).canCreate());
        var made=service.create(owner,draft(null),"create-one");
        assertTrue(made.operatingEntityId().startsWith("OE"));
        assertEquals("经营主体",made.name());assertNull(made.legalSubjectId());
        assertEquals("ENABLED",made.status());
        assertEquals(made,service.list(owner).getFirst());
        assertEquals(made,service.get(owner,made.operatingEntityId()));
        assertEquals(3,jdbc.queryForObject("SELECT COUNT(*) FROM mch_entity",Integer.class));
    }
    @Test void replayAndOriginalResultNeverCreateASecondObject() {
        var first=service.create(owner,draft("L1"),"original");
        jdbc.update("UPDATE mch_entity SET status='SUSPENDED' WHERE entity_no='L1'");
        assertEquals(first,service.create(owner,draft("L1"),"original"));
        assertEquals(first,service.result(owner,"original"));
        rejects(ErrorCode.CONFLICT,()->service.create(owner,new Create("changed",Market.TW,"L1"),"original"));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM mch_operating_entity",Integer.class));
    }
    @Test void approvedOwnerLegalReferencesMustMatchMarket() {
        assertEquals(2,service.legalSubjects(owner).size());
        rejects(ErrorCode.NOT_FOUND,()->service.create(owner,draft("L2"),"foreign"));
        rejects(ErrorCode.CONFLICT,()->service.create(owner,draft("CN1"),"market"));
        jdbc.update("UPDATE mch_entity SET verified=0 WHERE entity_no='L1'");
        rejects(ErrorCode.NOT_FOUND,()->service.create(owner,draft("L1"),"unverified"));
        rejects(ErrorCode.CONFLICT,()->service.create(owner,new Create("CN",Market.CN,null),"account-market"));
        assertTrue(service.list(owner).isEmpty());
    }
    @Test void typedStaffIdentityCannotCreateAndOwnerIdentityUsesCanonicalAccount() {
        rejects(ErrorCode.FORBIDDEN,()->service.create(new Actor("MAIN","MCH:STAFF"),draft(null),"staff"));
        var result=service.create(new Actor("MAIN","MCH:MA1"),draft(null),"owner-link");
        assertEquals(result,service.result(owner,"owner-link"));
        jdbc.update("UPDATE mch_account SET status='REVOKED' WHERE mch_account_no='MA1'");
        rejects(ErrorCode.FORBIDDEN,()->service.list(new Actor("MAIN","MCH:MA1")));
    }
    @Test void resultDirectoryAndDetailsStayWithinAccountAndTenant() {
        var made=service.create(owner,draft(null),"private");
        for (Actor other: new Actor[]{new Actor("MAIN","USER:U2"),new Actor("OTHER","USER:U1")}) {
            assertTrue(service.list(other).isEmpty());
            rejects(ErrorCode.NOT_FOUND,()->service.result(other,"private"));
            rejects(ErrorCode.NOT_FOUND,()->service.get(other,made.operatingEntityId()));
        }
    }
    @Test void inactiveAccountAndMissingMarketCannotWrite() {
        jdbc.update("UPDATE usr_account SET market=NULL WHERE tenant_no='MAIN' AND user_no='U1'");
        assertFalse(service.context(owner).canCreate());
        rejects(ErrorCode.CONFLICT,()->service.create(owner,draft(null),"missing-market"));
        jdbc.update("UPDATE usr_account SET status='BANNED' WHERE tenant_no='MAIN' AND user_no='U1'");
        rejects(ErrorCode.FORBIDDEN,()->service.list(owner));
    }
}
