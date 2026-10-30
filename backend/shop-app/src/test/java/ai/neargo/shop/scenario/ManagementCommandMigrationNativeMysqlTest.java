package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.brand.ManagementRepository;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import java.sql.DriverManager;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named="OUTBOX_SLIM_NATIVE",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagementCommandMigrationNativeMysqlTest {
    private final String admin=System.getenv("INVENTORY_PROJECTION_TEST_URL");
    private final String user=System.getenv("INVENTORY_PROJECTION_TEST_USER"),password=System.getenv("INVENTORY_PROJECTION_TEST_PASSWORD");
    private final String database="management_command_it_"+UUID.randomUUID().toString().replace("-","");
    private boolean created;
    private JdbcTemplate sql;
    private ManagementRepository repository;
    @BeforeAll void setup() throws Exception {
        assertThat(admin).startsWith("jdbc:mysql://127.0.0.1:3308/information_schema");
        try(var c=DriverManager.getConnection(admin,user,password);var statement=c.createStatement()){
            try(var result=statement.executeQuery("SELECT VERSION()")){result.next();assertThat(result.getString(1)).startsWith("9.");}
            statement.execute("CREATE DATABASE `"+database+"` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");created=true;
        }
        String url=admin.substring(0,admin.lastIndexOf('/')+1)+database+"?allowPublicKeyRetrieval=true&useSSL=false";
        var source=new DriverManagerDataSource(url,user,password);sql=new JdbcTemplate(source);
        sql.execute("CREATE TABLE sys_outbox(id BIGINT AUTO_INCREMENT PRIMARY KEY,event_no VARCHAR(64) UNIQUE,aggregate_type VARCHAR(32),aggregate_id VARCHAR(128),event_type VARCHAR(64),payload LONGTEXT,status VARCHAR(16),retry_count INT DEFAULT 0,next_retry_at DATETIME,last_error VARCHAR(512),created_at DATETIME,sent_at DATETIME)");
        new ResourceDatabasePopulator(new FileSystemResource("src/main/resources/db/migration/V471__outbox_delivery.sql")).execute(source);
        String payload="{\"tenantNo\":\"MAIN\",\"actorKey\":\"MCH:M1\",\"action\":\"BRAND_EDIT\",\"target\":\"BR1\",\"commandHash\":\"H1\",\"resultSnapshot\":{\"brandNo\":\"BR1\",\"version\":2}}";
        sql.update("INSERT INTO sys_outbox(id,event_no,aggregate_type,aggregate_id,event_type,payload,status,created_at) VALUES(10,'LEGACY','MANAGEMENT','MAIN|BR1','MANAGEMENT_CHANGED',?,'PENDING',UTC_TIMESTAMP())",payload);
        sql.update("INSERT INTO sys_outbox(id,event_no,source_system,source_event_no,aggregate_type,aggregate_id,event_type,payload,status,created_at) VALUES(20,'ENVELOPE','PLATFORM','ORIGINAL','MANAGEMENT','MAIN|BR1','MANAGEMENT_CHANGED',?,'PENDING',UTC_TIMESTAMP())",payload);
        sql.update("INSERT INTO sys_outbox(id,event_no,aggregate_type,aggregate_id,event_type,payload,status,created_at) VALUES(30,'OTHER','ORDER','O1','ORDER_CREATED','{}','PENDING',UTC_TIMESTAMP())");
        sql.update("INSERT INTO sys_outbox_delivery(event_no,handler_id,stream_key,stream_sequence,status,created_at) VALUES('ENVELOPE','management-audit-v1','MANAGEMENT:MAIN:BR1',1,'PENDING',UTC_TIMESTAMP())");
        new ResourceDatabasePopulator(new FileSystemResource("src/main/resources/db/migration/V622__management_command_journal.sql")).execute(source);
        repository=new ManagementRepository(sql,new DataSourceTransactionManager(source),JsonMapper.builder().build());
    }
    @Test void realMigrationPreservesReplayAuditIdsAndRetiresOnlyTheEmptyDelivery() {
        assertThat(sql.queryForList("SELECT id FROM mch_management_command ORDER BY id",Long.class)).containsExactly(10L,20L);
        assertThat(repository.replay("LEGACY","H1")).containsEntry("brandNo","BR1").containsEntry("version",2);
        assertThat(repository.replay("ORIGINAL","H1")).isEqualTo(repository.replay("LEGACY","H1"));
        assertThatThrownBy(()->repository.replay("ORIGINAL","OTHER")).isInstanceOf(BizException.class);
        assertThat(sql.queryForObject("SELECT status FROM sys_outbox_delivery",String.class)).isEqualTo("SKIPPED");
        assertThat(sql.queryForObject("SELECT status FROM sys_outbox WHERE event_no='LEGACY'",String.class)).isEqualTo("ARCHIVED");
        assertThat(sql.queryForObject("SELECT status FROM sys_outbox WHERE event_no='ENVELOPE'",String.class)).isEqualTo("SKIPPED");
        assertThat(sql.queryForObject("SELECT status FROM sys_outbox WHERE event_no='OTHER'",String.class)).isEqualTo("PENDING");
        var actor=new Actor("MAIN","MCH:M1");
        repository.write(()->{repository.emit(actor,"NEW","BR1","BRAND_EDIT","H2",Map.of("version",3));return null;});
        assertThat(repository.replay("NEW","H2")).containsEntry("version",3);
        assertThat(sql.queryForObject("SELECT COUNT(*) FROM sys_outbox",Long.class)).isEqualTo(3);
        assertThatThrownBy(()->repository.write(()->{repository.emit(actor,"ROLLBACK","BR1","BRAND_EDIT","H3",Map.of());throw new IllegalStateException("rollback");})).hasMessage("rollback");
        assertThat(repository.replay("ROLLBACK","H3")).isNull();
    }
    @AfterAll void cleanup() throws Exception {
        if(created)try(var c=DriverManager.getConnection(admin,user,password);var statement=c.createStatement()){
            statement.execute("DROP DATABASE `"+database+"`");
        }
    }
}
