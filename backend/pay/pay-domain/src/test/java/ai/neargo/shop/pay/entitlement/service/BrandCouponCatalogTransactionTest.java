package ai.neargo.shop.pay.entitlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.entity.EntTemplate;
import ai.neargo.shop.pay.entitlement.entity.EntTemplateVersion;
import ai.neargo.shop.pay.entitlement.entity.PayEntCommandDedup;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.pay.entitlement.service.impl.EntitlementCatalogServiceImpl;
import ai.neargo.shop.pay.mapper.entitlement.EntTemplateMapper;
import ai.neargo.shop.pay.mapper.entitlement.EntTemplateVersionMapper;
import ai.neargo.shop.pay.mapper.entitlement.PayEntCommandDedupMapper;
import ai.neargo.shop.spi.entitlement.BrandCouponSourcePort;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.AudienceSnapshot;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Real Spring transaction advice proves the remote preflight and local command have separate boundaries. */
@EnabledIfEnvironmentVariable(named="BRAND_AUDIENCE_MYSQL_URL",
        matches="jdbc:mysql://127\\.0\\.0\\.1:3308/.*")
class BrandCouponCatalogTransactionTest {
    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    static class Transactions { }

    @Test void sourceRunsOutsideTransactionWhileDedupAndAllLocalWritesShareThePayTransaction() {
        try(var f=new Fixture()) {
            assertThat(AopUtils.isAopProxy(f.commands)).isTrue();
            assertThat(AopUtils.isAopProxy(f.catalog)).isTrue(); // Existing status writes retain transaction advice.
            var d=f.definition("coupon");
            var created=f.catalog.create(f.scope,d,"CREATE");
            var saved=f.catalog.saveDraft(f.scope,created.templateNo(),created.revision(),d,"DRAFT");
            var published=f.catalog.publish(f.scope,created.templateNo(),saved.revision(),"PUBLISH");
            assertThat(f.source.calls).isEqualTo(3);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM command_probe WHERE result_status='COMPLETED'",
                    Long.class)).isEqualTo(3);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM write_probe",Long.class)).isEqualTo(4);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();

            f.source.refuse=true;
            assertThat(f.catalog.create(f.scope,d,"CREATE")).isEqualTo(created);
            assertThat(f.catalog.saveDraft(f.scope,created.templateNo(),created.revision(),d,"DRAFT")).isEqualTo(saved);
            assertThat(f.catalog.publish(f.scope,created.templateNo(),saved.revision(),"PUBLISH")).isEqualTo(published);
            assertThat(f.source.calls).isEqualTo(3);
        }
    }

    @Test void publishRejectsAChangedLockedDraftAndRollsBackItsCommandClaim() {
        try(var f=new Fixture()) {
            var created=f.catalog.create(f.scope,f.definition("coupon"),"CREATE");
            f.source.afterCheck=()->f.head.setDraftSnapshot(f.json.writeValueAsString(f.definition("changed")));
            assertThatThrownBy(()->f.catalog.publish(f.scope,created.templateNo(),0,"PUBLISH"))
                    .isInstanceOf(BizException.class);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM command_probe",Long.class)).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT COUNT(*) FROM write_probe",Long.class)).isEqualTo(1);
        }
    }

    private static class Source implements BrandCouponSourcePort {
        int calls;
        boolean refuse;
        Runnable afterCheck=()->{ };
        @Override public void validateTemplate(String tenant,String issuer,EntitlementDefinition d) {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if(refuse)throw new IllegalStateException("source unavailable during replay");
            calls++;
            afterCheck.run();
        }
        @Override public boolean permitsUse(String tenant,String issuer,EntitlementDefinition d,
                                             String version,String consumer,String store) {
            throw new UnsupportedOperationException();
        }
        @Override public AudienceSnapshot audience(String tenant,String issuer,EntitlementDefinition d,
                                                   String version,String audience,String actor,String command) {
            throw new UnsupportedOperationException();
        }
    }

    private static class Fixture implements AutoCloseable {
        final ObjectMapper json=new ObjectMapper();
        final CatalogScope scope=new CatalogScope("MAIN","ISSUER","ACTOR",false);
        final Source source=new Source();
        final AnnotationConfigApplicationContext context=new AnnotationConfigApplicationContext();
        final JdbcTemplate jdbc;
        final JdbcTemplate admin;
        final String database="cm_catalog_"+UUID.randomUUID().toString().replace("-","");
        final EntitlementCatalogService catalog;
        final EntitlementCommandDeduplicator commands;
        EntTemplate head;

        Fixture() {
            String url=System.getenv("BRAND_AUDIENCE_MYSQL_URL");
            if(!url.matches("jdbc:mysql://127\\.0\\.0\\.1:3308/(?:\\?.*)?")) {
                throw new IllegalArgumentException("Only the local 3308 server root URL is accepted");
            }
            String user=System.getenv("BRAND_AUDIENCE_MYSQL_USER");
            String password=System.getenv("BRAND_AUDIENCE_MYSQL_PASSWORD");
            admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
            if(!admin.queryForObject("SELECT VERSION()",String.class).startsWith("9.")) {
                throw new IllegalStateException("MySQL 9 is required");
            }
            admin.execute("CREATE DATABASE "+database+" CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            var ds=new DriverManagerDataSource(url.replace(":3308/",":3308/"+database),user,password);
            jdbc=new JdbcTemplate(ds);
            try {
                jdbc.execute("CREATE TABLE command_probe(id BIGINT AUTO_INCREMENT PRIMARY KEY,tenant_no VARCHAR(32),"
                        + "operation_kind VARCHAR(64),request_key VARCHAR(64),payload_hash VARCHAR(64),resource_no VARCHAR(64),"
                        + "result_status VARCHAR(24),response_snapshot LONGTEXT,UNIQUE(tenant_no,operation_kind,request_key)) ENGINE=InnoDB");
                jdbc.execute("CREATE TABLE write_probe(kind VARCHAR(24)) ENGINE=InnoDB");
                var receipts=mock(PayEntCommandDedupMapper.class);
                when(receipts.find(anyString(),anyString(),anyString())).thenAnswer(c->read(c.getArgument(0),
                        c.getArgument(1),c.getArgument(2)));
                when(receipts.lock(anyString(),anyString(),anyString())).thenAnswer(c->{
                    localTransaction();
                    return read(c.getArgument(0),c.getArgument(1),c.getArgument(2));
                });
                when(receipts.claim(any(PayEntCommandDedup.class))).thenAnswer(c->{
                    localTransaction();
                    PayEntCommandDedup r=c.getArgument(0);
                    return jdbc.update("INSERT INTO command_probe(tenant_no,operation_kind,request_key,payload_hash,"
                            + "resource_no,result_status) VALUES(?,?,?,?,?,'PENDING') ON DUPLICATE KEY UPDATE id=id",
                            r.getTenantNo(),r.getOperationKind(),r.getRequestKey(),r.getPayloadHash(),r.getResourceNo());
                });
                when(receipts.complete(anyLong(),anyString(),anyString(),anyString(),any())).thenAnswer(c->{
                    localTransaction();
                    return jdbc.update("UPDATE command_probe SET result_status='COMPLETED',response_snapshot=? "
                            + "WHERE id=? AND tenant_no=?",c.getArgument(2),c.getArgument(0),c.getArgument(1));
                });
                var heads=mock(EntTemplateMapper.class);
                when(heads.insert(any(EntTemplate.class))).thenAnswer(c->{
                    localTransaction();head=c.getArgument(0);return jdbc.update("INSERT INTO write_probe VALUES('CREATE')");
                });
                when(heads.findOwned(anyString(),anyString(),anyString())).thenAnswer(c->head);
                when(heads.lockOwned(anyString(),anyString(),anyString())).thenAnswer(c->{localTransaction();return head;});
                when(heads.saveDraft(anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),anyString(),any()))
                        .thenAnswer(c->{localTransaction();head.setDraftSnapshot(c.getArgument(5));
                            head.setVersion(head.getVersion()+1);return jdbc.update("INSERT INTO write_probe VALUES('DRAFT')");});
                when(heads.publish(anyString(),anyString(),anyString(),anyLong(),anyString(),anyString(),any()))
                        .thenAnswer(c->{localTransaction();return jdbc.update("INSERT INTO write_probe VALUES('PUBLISH')");});
                var versions=mock(EntTemplateVersionMapper.class);
                when(versions.insert(any(EntTemplateVersion.class))).thenAnswer(c->{
                    localTransaction();return jdbc.update("INSERT INTO write_probe VALUES('VERSION')");
                });
                context.register(Transactions.class);
                context.registerBean("payTxManager",DataSourceTransactionManager.class,()->new DataSourceTransactionManager(ds));
                context.registerBean(BrandCouponSourcePort.class,()->source);
                context.registerBean(EntitlementCommandDeduplicator.class,()->new EntitlementCommandDeduplicator(receipts,json));
                context.registerBean(EntitlementCatalogService.class,()->new EntitlementCatalogServiceImpl(heads,versions,
                        context.getBean(EntitlementCommandDeduplicator.class),json,mock(PayEntitlementFeatureGate.class),
                        context.getBeanProvider(BrandCouponSourcePort.class)));
                context.refresh();
                catalog=context.getBean(EntitlementCatalogService.class);
                commands=context.getBean(EntitlementCommandDeduplicator.class);
            } catch(RuntimeException | Error failure) {
                close();
                throw failure;
            }
        }
        private PayEntCommandDedup read(String tenant,String op,String key) {
            var rows=jdbc.query("SELECT * FROM command_probe WHERE tenant_no=? AND operation_kind=? AND request_key=?",
                    (rs,index)->{var r=new PayEntCommandDedup();r.setId(rs.getLong("id"));
                        r.setPayloadHash(rs.getString("payload_hash"));r.setResultStatus(rs.getString("result_status"));
                        r.setResponseSnapshot(rs.getString("response_snapshot"));return r;},tenant,op,key);
            return rows.isEmpty()?null:rows.getFirst();
        }
        private void localTransaction() {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(TransactionSynchronizationManager.hasResource(jdbc.getDataSource())).isTrue();
        }
        EntitlementDefinition definition(String title) {
            return new EntitlementDefinition(title,"ORDER_COUPON","MERCHANT","CNY","CASH",100L,null,null,0L,
                    null,"COUNT",1,null,new EntitlementDefinition.Validity("RELATIVE",null,null,30,"GRANTED",
                    "ROLLING_DAYS","UTC"),new EntitlementDefinition.RedemptionScope("BRAND_STORES",List.of("SOURCE")),
                    List.of("TARGETED"));
        }
        @Override public void close() {
            try { context.close(); }
            finally { admin.execute("DROP DATABASE "+database); }
        }
    }
}
