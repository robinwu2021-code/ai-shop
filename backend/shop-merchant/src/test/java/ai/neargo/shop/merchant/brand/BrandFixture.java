package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.merchant.brand.impl.ManagementScopeServiceImpl;

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
        String mysql=System.getProperty("p1.mysql.url");
        if(mysql!=null&&!mysql.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/p1_brand_[0-9_]+\\?.*"))throw new IllegalArgumentException("Only isolated P1 MySQL test databases are allowed");
        var ds = mysql==null?new DriverManagerDataSource("jdbc:h2:mem:brand-"+UUID.randomUUID()+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", ""):new DriverManagerDataSource(mysql,System.getProperty("p1.mysql.user"),Files.readString(Path.of(System.getProperty("p1.mysql.password-file"))).trim());
        jdbc = new JdbcTemplate(ds);
        var ddl = new JdbcTemplate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(ds.getConnection(),true));
        if(mysql!=null)for(String table:List.of("mch_brand_change","mch_brand_store_binding","mch_brand","mch_management_grant","mch_management_space","core_document_number","sys_sequence","sys_outbox","mch_payment_merchant","mch_store","mch_entity","mch_account"))jdbc.execute("DROP TABLE IF EXISTS "+table);
        var sql=Files.readString(Path.of("../shop-app/src/main/resources/db/migration/V544__brand_management_organization.sql"));
        if(mysql==null)sql=sql.replace("CASE WHEN status IN ('CHECKED','PREPARING') THEN store_no ELSE NULL END", "CASE WHEN status='CHECKED' THEN store_no WHEN status='PREPARING' THEN store_no ELSE NULL END").replace(" STORED", "").replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_520_ci", "");
        sql=sql.replaceAll("(?m)^--.*$", "");
        if(mysql==null)sql=sql.replace("CHECK (status IN ('PREPARING','ACTIVE','ENDED'))", "CHECK (CASE WHEN status='PREPARING' THEN TRUE WHEN status='ACTIVE' THEN TRUE WHEN status='ENDED' THEN TRUE ELSE FALSE END)");
        for(var part:sql.split(";")) if(!part.isBlank()) {
            var matcher=java.util.regex.Pattern.compile("CREATE TABLE ([a-z_]+)").matcher(part);
            if(mysql==null&&matcher.find())part=part.replace("KEY uk_", "KEY "+matcher.group(1)+"_uk_").replace("KEY ix_", "KEY "+matcher.group(1)+"_ix_");
            ddl.execute(part);
        }
        jdbc.execute("CREATE TABLE mch_account(tenant_no varchar(32),mch_account_no varchar(64),user_no varchar(64),entity_no varchar(64),display_name varchar(64),is_owner boolean,status varchar(24),deleted int default 0)");
        jdbc.execute("CREATE TABLE mch_entity(id bigint auto_increment primary key,version bigint default 0,tenant_no varchar(32),entity_no varchar(64),name varchar(100),status varchar(24),deleted int default 0)");
        jdbc.execute("CREATE TABLE mch_store(id bigint auto_increment primary key,tenant_no varchar(32),store_no varchar(64),entity_no varchar(64),name varchar(100),status varchar(24),version bigint default 0,deleted int default 0)");
        jdbc.execute("CREATE TABLE mch_payment_merchant(tenant_no varchar(32),pay_merchant_no varchar(64),entity_no varchar(64),store_no varchar(64),pay_channel varchar(24),apply_status varchar(24),pay_methods varchar(255),deleted int default 0)");
        jdbc.execute("CREATE TABLE sys_outbox(id bigint auto_increment primary key,event_no varchar(64),source_system varchar(32),source_event_no varchar(64),aggregate_type varchar(32),aggregate_id varchar(64),event_type varchar(64),payload longtext,status varchar(24),created_at timestamp,UNIQUE(source_system,source_event_no))");
        jdbc.update("INSERT INTO mch_account VALUES('MAIN','MA1','U1','E1','老板',true,'ACTIVE',0),('MAIN','MA2','U2','E2','另一老板',true,'ACTIVE',0),('MAIN','MA3',NULL,'E1','店员',false,'ACTIVE',0)");
        jdbc.update("INSERT INTO mch_entity(tenant_no,entity_no,name,status,deleted) VALUES('MAIN','E1','甲主体','ACTIVE',0),('MAIN','E2','乙主体','ACTIVE',0)");
        jdbc.update("INSERT INTO mch_store(tenant_no,store_no,entity_no,name,status) VALUES('MAIN','S1','E1','甲店','ACTIVE'),('MAIN','S2','E2','乙店','ACTIVE')");
        repo = new ManagementRepository(jdbc,new DataSourceTransactionManager(ds),new ObjectMapper(),e->{
            jdbc.update("INSERT INTO sys_outbox(event_no,source_system,source_event_no,aggregate_type,aggregate_id,event_type,payload,status,created_at) VALUES(?,?,?,?,?,?,?,'SENT',CURRENT_TIMESTAMP)",e.eventNo(),e.sourceSystem(),e.sourceEventNo(),e.aggregateType(),e.aggregateId(),e.eventType(),e.payload());
            return new ai.neargo.shop.event.outbox.PublishReceipt(e.eventNo(),true);
        });
        scopes = new ManagementScopeServiceImpl(repo);
    }
    ai.neargo.shop.numbering.store.DocumentNumberCoordinator numbers() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS sys_sequence(seq_name varchar(64) primary key,next_value bigint default 0,updated_at timestamp default CURRENT_TIMESTAMP)");
        jdbc.execute("CREATE TABLE IF NOT EXISTS core_document_number(id bigint auto_increment primary key,tenant_no varchar(64),document_type varchar(40),source_system varchar(32),source_resource_type varchar(64),source_no varchar(128),document_no varchar(32) UNIQUE,business_date date,created_at timestamp default CURRENT_TIMESTAMP,UNIQUE(tenant_no,document_type,source_system,source_resource_type,source_no))");
        var manager=new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource()));
        var store=new ai.neargo.shop.numbering.store.JdbcDocumentNumberRepository(ai.neargo.shop.numbering.DocumentOwner.CORE,manager);
        var gen=new ai.neargo.shop.numbering.store.SegmentDocumentNumberGenerator(store,new ai.neargo.shop.numbering.store.DocumentNumberProperties(ZoneId.of("Asia/Shanghai"),10));
        return new ai.neargo.shop.numbering.store.DocumentNumberCoordinator(store,gen,manager);
    }
    Map<String,Object> space() { return scopes.create(owner,"经营管理","MA1","boot-1"); }
    String no(Map<String,Object> row,String key) { return String.valueOf(row.get(key)); }
}
