package ai.neargo.shop.merchant.brand;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.time.*;
import java.util.*;

class BrandFixture {
    final JdbcTemplate jdbc;
    final ManagementRepository repo;
    final ManagementScopeService scopes;
    final ManagementModels.Actor owner = new ManagementModels.Actor("MAIN", "MCH:MA1");
    final ManagementModels.Actor other = new ManagementModels.Actor("MAIN", "MCH:MA2");
    BrandFixture() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:brand-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        var sql=Files.readString(Path.of("../../docs/technical/design/schema/brand-stored-value-p1-review.sql"));
        sql=sql.replace(" STORED", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        sql=sql.replaceAll("(?m)^--.*$", "");
        for(var part:sql.split(";")) if(!part.isBlank()) {
            var matcher=java.util.regex.Pattern.compile("CREATE TABLE ([a-z_]+)").matcher(part);
            if(matcher.find())part=part.replace("KEY uk_", "KEY "+matcher.group(1)+"_uk_").replace("KEY ix_", "KEY "+matcher.group(1)+"_ix_");
            jdbc.execute(part);
        }
        jdbc.execute("CREATE TABLE mch_account(tenant_no varchar(32),mch_account_no varchar(64),user_no varchar(64),entity_no varchar(64),display_name varchar(64),is_owner boolean,status varchar(24),deleted int default 0)");
        jdbc.execute("CREATE TABLE mch_entity(tenant_no varchar(32),entity_no varchar(64),name varchar(100),status varchar(24),deleted int default 0)");
        jdbc.execute("CREATE TABLE mch_store(tenant_no varchar(32),store_no varchar(64),entity_no varchar(64),name varchar(100),status varchar(24),version bigint default 0,deleted int default 0)");
        jdbc.execute("CREATE TABLE mch_payment_merchant(tenant_no varchar(32),pay_merchant_no varchar(64),entity_no varchar(64),store_no varchar(64),pay_channel varchar(24),apply_status varchar(24),pay_methods varchar(255),deleted int default 0)");
        jdbc.execute("CREATE TABLE sys_outbox(id bigint auto_increment primary key,event_no varchar(64),source_system varchar(32),source_event_no varchar(64),aggregate_type varchar(32),aggregate_id varchar(64),event_type varchar(64),payload clob,status varchar(24),created_at timestamp,UNIQUE(source_system,source_event_no))");
        jdbc.update("INSERT INTO mch_account VALUES('MAIN','MA1','U1','E1','老板',true,'ACTIVE',0),('MAIN','MA2','U2','E2','另一老板',true,'ACTIVE',0),('MAIN','MA3',NULL,'E1','店员',false,'ACTIVE',0)");
        jdbc.update("INSERT INTO mch_entity VALUES('MAIN','E1','甲主体','ACTIVE',0),('MAIN','E2','乙主体','ACTIVE',0)");
        jdbc.update("INSERT INTO mch_store(tenant_no,store_no,entity_no,name,status) VALUES('MAIN','S1','E1','甲店','ACTIVE'),('MAIN','S2','E2','乙店','ACTIVE')");
        repo = new ManagementRepository(jdbc,new DataSourceTransactionManager(ds),new ObjectMapper(),e->{
            jdbc.update("INSERT INTO sys_outbox(event_no,source_system,source_event_no,aggregate_type,aggregate_id,event_type,payload,status,created_at) VALUES(?,?,?,?,?,?,?,'SENT',CURRENT_TIMESTAMP)",e.eventNo(),e.sourceSystem(),e.sourceEventNo(),e.aggregateType(),e.aggregateId(),e.eventType(),e.payload());
            return new ai.neargo.shop.event.outbox.PublishReceipt(e.eventNo(),true);
        });
        scopes = new ManagementScopeService(repo);
    }
    Map<String,Object> space() { return scopes.create(owner,"经营管理","MA1","boot-1"); }
    String no(Map<String,Object> row,String key) { return String.valueOf(row.get(key)); }
}
