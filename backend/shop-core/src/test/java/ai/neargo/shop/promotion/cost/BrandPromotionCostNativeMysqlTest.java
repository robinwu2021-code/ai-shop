package ai.neargo.shop.promotion.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.GenerateStatement;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.RevisionCommand;
import ai.neargo.shop.promotion.cost.BrandPromotionCostModels.SaveRule;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.mapper.PmtCommandDedupMapper;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.spi.marketing.BrandPromotionScopePort;
import ai.neargo.shop.trade.order.refund.model.RefundLine;
import ai.neargo.shop.trade.order.refund.model.RefundPlan;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Explicitly opt in with an isolated local MySQL9 database; no payment owner or in-memory financial surrogate. */
@EnabledIfSystemProperty(named="promotion.cost.mysql.url",matches="jdbc:mysql://127\\.0\\.0\\.1:3308/cm_cost_[a-z0-9_]+\\?.*")
class BrandPromotionCostNativeMysqlTest {
    @Test void frozenVersionPaidTruthCumulativeRefundsAndPermanentCommandsStayConserved() throws Exception {
        var f=new Fixture();
        // A current cross-entity member has a legitimate empty financial history before first publication.
        assertThat(f.cost.list(f.actor,"E2")).isEmpty();
        assertThatThrownBy(()->f.cost.list(f.actor,"FOREIGN")).isInstanceOf(BizException.class);
        var draft=f.run(()->f.cost.saveRule(f.actor,new SaveRule("first","CNY",3300,6700),"rule-save"));
        assertThatThrownBy(()->f.cost.validate(f.actor,draft.ruleVersionNo(),List.of("S2")))
                .isInstanceOf(BizException.class);
        var rule=f.run(()->f.cost.publishRule(f.actor,draft.ruleVersionNo(),0,"rule-publish"));
        f.run(()->{f.cost.freeze(f.actor,rule.ruleVersionNo(),"ACTIVITY","A1","AV1",List.of("S2"));return null;});
        f.run(()->{f.cost.freeze(f.actor,rule.ruleVersionNo(),"ACTIVITY","A1","AV1",List.of("S2"));return null;});
        f.db.update("INSERT INTO ord_order VALUES('MAIN','O1','CNY',NULL)");
        f.db.update("INSERT INTO ord_item VALUES(1,'MAIN','SUB1',3,900,0)");
        f.db.update("INSERT INTO pmt_apply VALUES('MAIN','AP1','ACTIVITY','A1','O1','SUB1','E2','S2',101)");
        f.run(()->{f.cost.captureActivity("MAIN","AP1","AV1");return null;});
        var period=new GenerateStatement("CNY",Instant.now().plusSeconds(600));
        assertThatThrownBy(()->f.run(()->f.cost.generate(f.actor,"HQ",period,"unpaid")))
                .isInstanceOf(BizException.class);
        assertThat(f.db.queryForObject("SELECT COUNT(*) FROM pmt_brand_cost_fact",Long.class)).isZero();
        f.db.update("UPDATE ord_order SET paid_at=?",Instant.now().toEpochMilli());
        var hq=f.run(()->f.cost.generate(f.actor,"HQ",period,"hq-generate"));
        var store=f.run(()->f.cost.generate(f.actor,"E2",period,"store-generate"));
        assertThat(hq.totalMinor()).isEqualTo(33);
        assertThat(store.totalMinor()).isEqualTo(68);
        var confirmed=f.run(()->f.cost.confirm(f.actor,"HQ",hq.statementNo(),new RevisionCommand(0),"hq-confirm"));
        f.run(()->f.cost.confirm(f.actor,"E2",store.statementNo(),new RevisionCommand(0),"store-confirm"));
        assertThat(f.run(()->f.cost.confirm(f.actor,"HQ",hq.statementNo(),new RevisionCommand(0),"hq-confirm")))
                .isEqualTo(confirmed);
        assertThat(f.run(()->f.cost.generate(f.actor,"HQ",period,"hq-generate"))).isEqualTo(hq);
        assertThatThrownBy(()->f.cost.get(f.actor,"E2",hq.statementNo())).isInstanceOf(BizException.class);

        var next=f.run(()->f.cost.saveRule(f.actor,new SaveRule("new","CNY",10000,0),"rule-next"));
        f.run(()->f.cost.publishRule(f.actor,next.ruleVersionNo(),0,"next-publish"));
        f.refund("R1",1,Instant.now().toEpochMilli());
        var adjustment=f.run(()->f.cost.generate(f.actor,"HQ",period,"refund-1"));
        assertThat(adjustment.totalMinor()).isEqualTo(-10);
        assertThat(adjustment.facts()).allMatch(x->x.costRuleVersionNo().equals(rule.ruleVersionNo()));
        assertThat(f.cost.get(f.actor,"HQ",hq.statementNo())).isEqualTo(confirmed);
        var voided=f.run(()->f.cost.voidStatement(f.actor,"HQ",adjustment.statementNo(),new RevisionCommand(0),"void"));
        assertThat(voided.status()).isEqualTo("VOID");
        assertThat(voided.facts()).isEqualTo(adjustment.facts());
        assertThat(voided.discrepancyMinor()).isZero();
        var recovered=f.run(()->f.cost.generate(f.actor,"HQ",period,"refund-1-reissued"));
        assertThat(recovered.totalMinor()).isEqualTo(-10);
        f.run(()->f.cost.confirm(f.actor,"HQ",recovered.statementNo(),new RevisionCommand(0),"reissued-confirm"));
        // A newly committed success may carry an older business time; it must not reorder prior rounding.
        f.refund("R2",1,Instant.now().minusSeconds(60).toEpochMilli());
        f.refund("R3",1,Instant.now().toEpochMilli());
        var remaining=f.run(()->f.cost.generate(f.actor,"HQ",period,"refund-rest"));
        assertThat(remaining.totalMinor()).isEqualTo(-23);
        var storeRefund=f.run(()->f.cost.generate(f.actor,"E2",period,"store-refund"));
        assertThat(storeRefund.totalMinor()).isEqualTo(-68);
        assertThat(f.db.queryForObject("SELECT SUM(amount_minor) FROM pmt_brand_cost_fact",Long.class)).isZero();
        assertThat(f.db.queryForObject("SELECT SUM(headquarters_minor) FROM pmt_brand_cost_fact",Long.class)).isZero();
        assertThat(f.db.queryForObject("SELECT SUM(store_minor) FROM pmt_brand_cost_fact",Long.class)).isZero();
        assertThatThrownBy(()->f.run(()->{f.cost.freeze(f.actor,next.ruleVersionNo(),"ACTIVITY","A1","AV1",
                List.of("S2"));return null;})).isInstanceOf(BizException.class);
        var other=new ActivityActor("MAIN","HQ","ACTOR",Set.of("S2"),"M1","OTHER");
        assertThatThrownBy(()->f.cost.get(other,"HQ",hq.statementNo())).isInstanceOf(BizException.class);
        // Only cost/source/statement tables exist: confirmation cannot call or create a payment ledger.
        assertThat(remaining.discrepancyMinor()).isZero();
        f.db.update("UPDATE pmt_brand_cost_fact SET headquarters_minor=headquarters_minor+1,store_minor=store_minor-1 "
                + "WHERE statement_no=? LIMIT 1",remaining.statementNo());
        assertThat(f.cost.get(f.actor,"HQ",remaining.statementNo()).discrepancyMinor()).isPositive();
        assertThatThrownBy(()->f.run(()->f.cost.confirm(f.actor,"HQ",remaining.statementNo(),
                new RevisionCommand(0),"discrepancy"))).isInstanceOf(BizException.class);
    }

    private static class Fixture {
        final ObjectMapper json=new ObjectMapper();
        final JdbcTemplate db;
        final TransactionTemplate tx;
        final BrandPromotionCostServiceImpl cost;
        final ActivityActor actor=new ActivityActor("MAIN","HQ","ACTOR",Set.of("S2"),"M1","B1");
        Fixture() throws Exception {
            var ds=new DriverManagerDataSource(System.getProperty("promotion.cost.mysql.url"),
                    System.getProperty("promotion.cost.mysql.user"),Files.readString(Path.of(
                    System.getProperty("promotion.cost.mysql.password-file"))).trim());
            db=new JdbcTemplate(ds);
            for(String table:List.of("pmt_brand_cost_fact","pmt_brand_cost_statement","pmt_brand_cost_source",
                    "pmt_brand_cost_snapshot","pmt_brand_cost_rule","pmt_command_dedup","ord_refund_execution",
                    "ord_order","ord_item","pmt_apply"))db.execute("DROP TABLE IF EXISTS "+table);
            String ddl=Files.readString(Path.of("../shop-app/src/main/resources/db/migration/V652__brand_promotion.sql"));
            for(String sql:ddl.split(";"))if(sql.contains("CREATE TABLE pmt_brand_cost_"))db.execute(sql);
            String commands=Files.readString(Path.of("../shop-app/src/main/resources/db/migration/V484__promotion_version_participation.sql"));
            db.execute(commands.substring(commands.indexOf("CREATE TABLE pmt_command_dedup")).split(";")[0]);
            db.execute("CREATE TABLE ord_order(tenant_no varchar(32),order_no varchar(64),currency varchar(8),paid_at bigint)");
            db.execute("CREATE TABLE ord_item(id bigint,tenant_no varchar(32),sub_order_no varchar(64),qty int,amount bigint,deleted int)");
            db.execute("CREATE TABLE pmt_apply(tenant_no varchar(32),apply_no varchar(64),promo_type varchar(16),promo_no varchar(64),"
                    + "order_no varchar(64),sub_order_no varchar(64),entity_no varchar(64),store_no varchar(64),amount_minor bigint)");
            db.execute("CREATE TABLE ord_refund_execution(id bigint auto_increment primary key,tenant_no varchar(32),"
                    + "execution_no varchar(64),sub_order_no varchar(64),status varchar(16),succeeded_at bigint,plan_json json)");
            var config=new MybatisConfiguration(); config.setMapUnderscoreToCamelCase(true);
            config.addMapper(PmtCommandDedupMapper.class);
            var factory=new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(config);
            var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(PmtCommandDedupMapper.class);
            var manager=new DataSourceTransactionManager(ds); tx=new TransactionTemplate(manager);
            var organization=mock(BrandPromotionScopePort.class);
            when(organization.find("MAIN","M1","B1","HQ")).thenReturn(new BrandPromotionScopePort.Brand("M1","B1",
                    "HQ",1,1,List.of(new BrandPromotionScopePort.Store("S2","E2",1,"BIND",1,"CN"))));
            cost=new BrandPromotionCostServiceImpl(manager,organization,new ActivityCommandDeduplicator(mapper,json),json);
        }
        <T>T run(Supplier<T> action) { return tx.execute(status->action.get()); }
        void refund(String no,int qty,long at) {
            db.update("INSERT INTO ord_refund_execution(tenant_no,execution_no,sub_order_no,status,succeeded_at,plan_json)"
                    + " VALUES('MAIN',?,'SUB1','SUCCEEDED',?,?)",no,at,
                    json.writeValueAsString(new RefundPlan("SUB1",List.of(new RefundLine(1,qty,200)),0)));
        }
    }
}
