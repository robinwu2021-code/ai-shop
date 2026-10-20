package ai.neargo.shop.config;
import static org.assertj.core.api.Assertions.*;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import javax.sql.DataSource;

/** Full actual Flyway, including Java migrations, on a disposable MariaDB supplied by the rehearsal launcher. */
@EnabledIfEnvironmentVariable(named="ENTITLEMENT_SCHEMA_MARIADB_URL",matches=".+")
class EntitlementSchemaUpgradeNativeMariaDbTest {
    private DataSource data(String database) {
        String url=System.getenv("ENTITLEMENT_SCHEMA_MARIADB_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ent_fresh.*");
        return new DriverManagerDataSource(url.replace("/ent_fresh?","/"+database+"?"),"root","");
    }
    private Flyway flyway(DataSource source,String target) {
        var config=Flyway.configure().dataSource(source).locations("classpath:db/migration");
        new FlywayDialectConfig().migrationDialect().customize(config);
        if(target!=null) config.target(target);return config.load();
    }
    @Test void frozenChecksumsFreshDatabaseAndUpgradePreserveOriginalSourceAndReachSameSchema() {
        var fresh=data("ent_fresh");var upgrade=data("ent_upgrade");
        var current=flyway(fresh,null);current.migrate();current.validate();
        var old=flyway(upgrade,"424");old.migrate();
        var jdbc=new JdbcTemplate(upgrade);
        jdbc.update("INSERT INTO mkt_coupon(coupon_no,title,type,face_minor,funder,entity_no,status,start_at,end_at,total_count,received_count,per_user_limit,budget_minor,tenant_no,created_at,updated_at) VALUES('UPGRADE-COUPON','preserve old terms','FULL_CUT',100,'MERCHANT','UPGRADE-MERCHANT','ACTIVE',1,1800000000000,10,1,2,1000,'MAIN',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        jdbc.update("INSERT INTO mkt_user_coupon(user_coupon_no,coupon_no,user_no,status,received_at,tenant_no,created_at,updated_at) VALUES('UPGRADE-INSTANCE','UPGRADE-COUPON','UPGRADE-USER','UNUSED',1,'MAIN',UTC_TIMESTAMP(),UTC_TIMESTAMP())");
        var migrated=flyway(upgrade,null);migrated.migrate();migrated.validate();assertThat(migrated.migrate().migrationsExecuted).isZero();
        assertThat(current.info().current().getVersion().getVersion()).isEqualTo("474");
        assertThat(migrated.info().current().getVersion().getVersion()).isEqualTo("474");
        String columns="SELECT table_name,column_name,column_type,is_nullable,COALESCE(column_default,'<NULL>') AS default_value,COALESCE(collation_name,'<NULL>') AS collation_name,extra FROM information_schema.columns WHERE table_schema=? AND table_name<>'flyway_schema_history' ORDER BY table_name,ordinal_position";
        assertThat(jdbc.queryForList(columns,"ent_upgrade")).isEqualTo(new JdbcTemplate(fresh).queryForList(columns,"ent_fresh"));
        String indexes="SELECT table_name,index_name,non_unique,seq_in_index,column_name,COALESCE(sub_part,0) AS prefix_length FROM information_schema.statistics WHERE table_schema=? AND table_name<>'flyway_schema_history' ORDER BY table_name,index_name,seq_in_index";
        assertThat(jdbc.queryForList(indexes,"ent_upgrade")).isEqualTo(new JdbcTemplate(fresh).queryForList(indexes,"ent_fresh"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='ent_upgrade' AND table_name<>'flyway_schema_history'",Integer.class)).isEqualTo(269);
        assertThat(jdbc.queryForObject("SELECT CONCAT(face_minor,':',received_count,':',status,':',migration_state) FROM mkt_coupon WHERE coupon_no='UPGRADE-COUPON'",String.class)).isEqualTo("100:1:ACTIVE:OPEN");
        assertThat(jdbc.queryForObject("SELECT CONCAT(status,':',received_at) FROM mkt_user_coupon WHERE user_coupon_no='UPGRADE-INSTANCE'",String.class)).isEqualTo("UNUSED:1");
        for(var ds:java.util.List.of(fresh,upgrade)) {
            var db=new JdbcTemplate(ds);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ent_instance",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ent_owner_registry",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM ent_ledger",Integer.class)).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='461'",Integer.class)).isEqualTo(new JdbcTemplate(fresh).queryForObject("SELECT checksum FROM flyway_schema_history WHERE version='461'",Integer.class));
    }
}
