package ai.neargo.shop.pay;

import ai.neargo.shop.pay.mapper.entitlement.EntIssuePolicyMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntIssueRecipientMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.util.ArrayList;
import java.time.LocalDateTime;
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
import org.springframework.transaction.TransactionDefinition;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real migration/JSON/CHECK/conditional mapper transactions against a disposable native MySQL only. */
@EnabledIfEnvironmentVariable(named="ISSUANCE_NATIVE_MYSQL_URL",matches="jdbc:mysql://127\\.0\\.0\\.1:.*")
class EntitlementIssuanceNativeMysqlTest {
    @Test void nativeIssuanceCountersAndSourceClaimsAreAtomic() throws Exception {
        String url=System.getenv("ISSUANCE_NATIVE_MYSQL_URL");
        assertThat(url).matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/issuance_native_fixture\\?.*");
        var ds=new DriverManagerDataSource(url,"root","");
        var jdbc=new JdbcTemplate(ds);
        new ResourceDatabasePopulator(
                new ClassPathResource("db/migration/V425__entitlement_catalog.sql"),
                new ClassPathResource("db/migration/V433__entitlement_issue_capacity.sql"),
                new ClassPathResource("db/migration/V434__entitlement_grants_and_ledger.sql"),
                new ClassPathResource("db/migration/V435__entitlement_issue_batch.sql")).execute(ds);
        seedBudget(jdbc,"A1",20,2000,2);
        assertThatThrownBy(()->jdbc.update("UPDATE ent_issue_policy SET issued_count=21 WHERE template_no='A1'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE ent_issue_policy SET held_exposure_minor=-1 WHERE template_no='A1'"))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO ent_grant (tenant_no,grant_no,source_key,source_snapshot,"
                + "template_version_no,user_no,entity_no,quantity,payload_hash,status,prepared_snapshot,"
                + "created_at,updated_at,created_by,updated_by) VALUES ('MAIN','BAD',REPEAT('a',64),'not-json',"
                + "'V1','U1','M1',1,REPEAT('b',64),'PREPARED','{}',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),'T','T')"))
                .isInstanceOf(DataAccessException.class);
        String grantSql = "INSERT INTO ent_grant (tenant_no,grant_no,source_key,source_snapshot,template_version_no,"
                + "user_no,entity_no,quantity,payload_hash,status,prepared_snapshot,created_at,updated_at,created_by,"
                + "updated_by) VALUES ('MAIN',?,REPEAT('a',64),'{}','V1','U1','M1',1,REPEAT('b',64),'PREPARED',"
                + "'{}',CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),'T','T')";
        jdbc.update(grantSql, "G1");
        assertThatThrownBy(() -> jdbc.update(grantSql, "G2")).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant WHERE source_key=REPEAT('a',64)",
                Long.class)).isEqualTo(1);
        var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
        var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
        SqlSessionFactory sessions=factory.getObject();
        try(var context=new AnnotationConfigApplicationContext()) {
            registerMapper(context,EntIssuePolicyMapper.class,sessions);
            registerMapper(context,EntIssueRecipientMapper.class,sessions);
            context.refresh();
            var budgets=context.getBean(EntIssuePolicyMapper.class);
            var people=context.getBean(EntIssueRecipientMapper.class);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
            tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
            seedBudget(jdbc,"RR",10,1000,2);
            try (var concurrent = Executors.newSingleThreadExecutor()) {
                tx.executeWithoutResult(status -> {
                    assertThat(jdbc.queryForObject("SELECT issued_count FROM ent_issue_policy "
                            + "WHERE template_no='RR'", Long.class)).isZero();
                    try {
                        concurrent.submit(() -> tx.executeWithoutResult(other -> {
                            budgets.lock("MAIN", "RR");
                            people.ensure("MAIN", "RR", "U1", "TEST", LocalDateTime.now());
                            assertThat(budgets.reserve("MAIN", "RR", 2, 200, "TEST", LocalDateTime.now())).isEqualTo(1);
                            assertThat(people.reserve("MAIN", "RR", "U1", 2, 2, "TEST", LocalDateTime.now())).isEqualTo(1);
                            assertThat(budgets.settle("MAIN", "RR", 2, 200, 2, 200, "TEST", LocalDateTime.now())).isEqualTo(1);
                            assertThat(people.settle("MAIN", "RR", "U1", 2, 2, "TEST", LocalDateTime.now())).isEqualTo(1);
                        })).get(10, TimeUnit.SECONDS);
                    } catch (Exception failure) { throw new IllegalStateException(failure); }
                    budgets.lock("MAIN", "RR");
                    // A normal MAX still observes the old snapshot; the guard must use a current locking read.
                    assertThat(jdbc.queryForObject("SELECT COALESCE(MAX(held_count+issued_count),0) "
                            + "FROM ent_issue_recipient WHERE template_no='RR'", Long.class)).isZero();
                    assertThat(people.findExceeding("MAIN", "RR", 1).getIssuedCount()).isEqualTo(2);
                });
            }
            seedBudget(jdbc,"ROLLBACK",1,100,1);
            assertThatThrownBy(()->tx.executeWithoutResult(status->{
                budgets.lock("MAIN","ROLLBACK");
                assertThat(budgets.reserve("MAIN","ROLLBACK",1,100,"TEST",LocalDateTime.now())).isEqualTo(1);
                people.ensure("MAIN","ROLLBACK","U1","TEST",LocalDateTime.now());
                assertThat(people.reserve("MAIN","ROLLBACK","U1",1,1,"TEST",LocalDateTime.now())).isEqualTo(1);
                throw new IllegalStateException("simulated downstream failure");
            })).isInstanceOf(IllegalStateException.class);
            assertThat(jdbc.queryForObject("SELECT held_count FROM ent_issue_policy WHERE template_no='ROLLBACK'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_issue_recipient WHERE template_no='ROLLBACK'",Long.class)).isZero();
            List<Callable<Boolean>> attempts=new ArrayList<>();
            for(int i=0;i<100;i++) {
                String user="U"+(i%10);
                attempts.add(()->tx.execute(status->{
                    budgets.lock("MAIN","A1");
                    people.ensure("MAIN","A1",user,"TEST",LocalDateTime.now());
                    if(budgets.reserve("MAIN","A1",1,100,"TEST",LocalDateTime.now())!=1 || people.reserve("MAIN","A1",user,1,2,"TEST",LocalDateTime.now())!=1) {
                        status.setRollbackOnly(); return false;
                    }
                    assertThat(budgets.settle("MAIN","A1",1,100,1,100,"TEST",LocalDateTime.now())).isEqualTo(1);
                    assertThat(people.settle("MAIN","A1",user,1,1,"TEST",LocalDateTime.now())).isEqualTo(1);
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
            assertThat(jdbc.queryForObject("SELECT issued_count FROM ent_issue_policy WHERE template_no='A1'",Long.class)).isEqualTo(20);
            assertThat(jdbc.queryForObject("SELECT used_exposure_minor FROM ent_issue_policy WHERE template_no='A1'",Long.class)).isEqualTo(2000);
            assertThat(jdbc.queryForObject("SELECT held_count+held_exposure_minor FROM ent_issue_policy WHERE template_no='A1'",Long.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_issue_recipient WHERE template_no='A1' AND issued_count=2 AND held_count=0",Long.class)).isEqualTo(10);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_issue_recipient WHERE held_count<0 OR issued_count<0",Long.class)).isZero();
        }
    }
    private static void seedBudget(JdbcTemplate jdbc,String activity,int quota,long amount,int personal) {
        jdbc.update("INSERT INTO ent_issue_policy (tenant_no,template_no,limit_count,max_exposure_minor,per_user_limit,enabled,created_at,updated_at,created_by,updated_by) VALUES ('MAIN',?,?,?,?,1,CURRENT_TIMESTAMP(3),CURRENT_TIMESTAMP(3),'TEST','TEST')",activity,quota,amount,personal);
    }
    private static <T> void registerMapper(AnnotationConfigApplicationContext context,Class<T> type,SqlSessionFactory sessions) {
        context.registerBean(type.getSimpleName(),MapperFactoryBean.class,()->{
            var mapper=new MapperFactoryBean<>(type); mapper.setSqlSessionFactory(sessions); return mapper;
        });
    }
}
