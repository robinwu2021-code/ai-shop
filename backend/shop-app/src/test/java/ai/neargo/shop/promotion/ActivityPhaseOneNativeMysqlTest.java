package ai.neargo.shop.promotion;

import ai.neargo.shop.promotion.mapper.PmtActivityBudgetMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityParticipantMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.mapper.MapperFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real migration/JSON/CHECK/conditional mapper transactions against a disposable native MySQL only. */
@EnabledIfEnvironmentVariable(named="ACTIVITY_NATIVE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:.*")
class ActivityPhaseOneNativeMysqlTest {
    @Test void migrationsAndConcurrentMarketingCapacityAreAtomic() throws Exception {
        String url=System.getenv("ACTIVITY_NATIVE_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/activity_native_fixture\\?.*");
        var ds=new DriverManagerDataSource(url,"root","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE pmt_activity (id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32) NOT NULL,entity_no VARCHAR(64) NOT NULL,status VARCHAR(24) NOT NULL) ENGINE=InnoDB");
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V483__promotion_preset_catalog.sql"),
                new ClassPathResource("db/migration/V484__promotion_version_participation.sql"),
                new ClassPathResource("db/migration/V485__promotion_effect_store_scope.sql")).execute(ds);
        jdbc.update("INSERT INTO pmt_activity_budget (tenant_no,activity_no,currency,quota_limit,budget_limit_minor,per_user_limit,quota_used,budget_used_minor,"
                + "created_at,updated_at,created_by,updated_by) VALUES "
                + "('MAIN','HISTORY','CNY',20,2000,2,1,100,NOW(),NOW(),'TEST','TEST')");
        jdbc.update("INSERT INTO pmt_activity_participant (tenant_no,activity_no,user_no,held_count,used_count,"
                + "created_at,updated_at,created_by,updated_by) VALUES "
                + "('MAIN','HISTORY','LEGACY',0,1,NOW(),NOW(),'TEST','TEST')");
        jdbc.update("""
                INSERT INTO pmt_activity_reservation
                    (tenant_no,reservation_no,source_key,activity_no,version_no,user_no,entity_no,store_no,currency,
                     quota_units,budget_minor,status,permit_snapshot,payload_hash,deadline,pay_fact_no,
                     created_at,updated_at,created_by,updated_by)
                VALUES ('MAIN','R1',REPEAT('a',64),'HISTORY','V1','LEGACY','E1','S1','CNY',1,100,'COMMITTED',
                     JSON_OBJECT('sourceRef',JSON_OBJECT('sourceType','RECHARGE','sourceNo','RC1')),
                     REPEAT('b',64),NOW(),'GRANT1',NOW(),NOW(),'TEST','TEST')
                """);
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V706__activity_quota_reservation.sql")).execute(ds);
        assertThat(jdbc.queryForObject("SELECT quota_used FROM pmt_quota WHERE quota_dimension='PER_USER' "
                + "AND target_no='HISTORY' AND user_no='LEGACY'", Long.class)).isEqualTo(1);

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pmt_reservation r JOIN pmt_quota q
                  ON r.tenant_no=q.tenant_no AND r.quota_no=q.quota_no
                WHERE q.target_no='HISTORY' AND q.quota_dimension='TOTAL' AND r.reservation_type='ACTIVITY'
                  AND r.reservation_no='R1' AND r.source_type='RECHARGE' AND r.source_no='RC1'
                  AND r.source_key=REPEAT('a',64) AND r.payload_hash=REPEAT('b',64)
                  AND q.quota_used=1 AND q.budget_used_minor=100
                  AND r.status='COMMITTED' AND r.pay_fact_no='GRANT1'
                """, Integer.class)).isOne();
        seedBudget(jdbc,"A1",20,2000,2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_preset_template",Integer.class)).isEqualTo(46);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_preset_template WHERE enabled<>0",Integer.class)).isZero();
        assertThatThrownBy(()->jdbc.update("UPDATE pmt_preset_template SET draft_schema='invalid-json' WHERE template_code='AP001'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE pmt_quota SET quota_used=21 WHERE activity_no='A1'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE pmt_quota SET budget_held_minor=-1 WHERE activity_no='A1'"))
                .isInstanceOf(DataAccessException.class);
        var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        SqlSessionFactory sessions=factory.getObject();
        try(var context=new AnnotationConfigApplicationContext()) {
            registerMapper(context,PmtActivityBudgetMapper.class,sessions);
            registerMapper(context,PmtActivityParticipantMapper.class,sessions);
            context.refresh();
            var budgets=context.getBean(PmtActivityBudgetMapper.class);
            var people=context.getBean(PmtActivityParticipantMapper.class);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            seedBudget(jdbc,"ROLLBACK",1,100,1);
            assertThatThrownBy(()->tx.executeWithoutResult(status->{
                budgets.lock("MAIN","ROLLBACK");
                assertThat(budgets.reserve("MAIN","ROLLBACK",1,100)).isEqualTo(1);
                people.ensure("MAIN","ROLLBACK","U1");
                assertThat(people.reserve("MAIN","ROLLBACK","U1",1,1)).isEqualTo(1);
                throw new IllegalStateException("simulated downstream failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT quota_held FROM pmt_quota WHERE quota_dimension='TOTAL' AND activity_no='ROLLBACK'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_quota WHERE quota_dimension='PER_USER' AND activity_no='ROLLBACK'",Long.class)).isZero();
            List<Callable<Boolean>> attempts=new ArrayList<>();
            for(int i=0;i<100;i++) {
                String user="U"+(i%10);
                attempts.add(()->tx.execute(status->{
                    budgets.lock("MAIN","A1");
                    people.ensure("MAIN","A1",user);
                    if(budgets.reserve("MAIN","A1",1,100)!=1 || people.reserve("MAIN","A1",user,1,2)!=1) {
                        status.setRollbackOnly(); return false;
                    }
                    assertThat(budgets.settle("MAIN","A1",1,100,1,100)).isEqualTo(1);
                    assertThat(people.settle("MAIN","A1",user,1,1)).isEqualTo(1);
                    return true;
                }));
            }
            var pool=Executors.newFixedThreadPool(10);
            try {
                long succeeded=0;
                for(var result:pool.invokeAll(attempts,60,TimeUnit.SECONDS)) {
                    assertThat(result.isCancelled()).isFalse();
                    if(Boolean.TRUE.equals(result.get())) succeeded++;
                }
                assertThat(succeeded).isEqualTo(20);
            } finally { pool.shutdownNow(); }
            assertThat(jdbc.queryForObject("SELECT quota_used FROM pmt_quota WHERE quota_dimension='TOTAL' AND activity_no='A1'",Long.class)).isEqualTo(20);
            assertThat(jdbc.queryForObject("SELECT budget_used_minor FROM pmt_quota WHERE quota_dimension='TOTAL' AND activity_no='A1'",Long.class)).isEqualTo(2000);
            assertThat(jdbc.queryForObject("SELECT quota_held+budget_held_minor FROM pmt_quota WHERE quota_dimension='TOTAL' AND activity_no='A1'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_quota WHERE quota_dimension='PER_USER' AND activity_no='A1' AND quota_used=2 AND quota_held=0",Long.class)).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_quota WHERE quota_dimension='PER_USER' AND (quota_held<0 OR quota_used<0)",Long.class)).isZero();
        }
    }
    private static void seedBudget(JdbcTemplate jdbc,String activity,int quota,long amount,int personal) {
        jdbc.update("INSERT INTO pmt_quota (tenant_no,quota_no,target_type,target_no,quota_dimension,activity_no,currency,quota_limit,budget_limit_minor,per_user_limit,created_at,updated_at,created_by,updated_by) VALUES ('MAIN',UUID(),'ACTIVITY',?,'TOTAL',?,'CNY',?,?,?,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),'TEST','TEST')",activity,activity,quota,amount,personal);
    }
    private static <T> void registerMapper(AnnotationConfigApplicationContext context,Class<T> type,SqlSessionFactory sessions) {
        context.registerBean(type.getSimpleName(),MapperFactoryBean.class,()->{
            var mapper=new MapperFactoryBean<>(type); mapper.setSqlSessionFactory(sessions); return mapper;
        });
    }
}
