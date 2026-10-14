package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.earn.EarnService;
import ai.neargo.shop.pay.points.pool.PoolRequestService;
import ai.neargo.shop.pay.points.type.PointTypeService;
import ai.neargo.shop.spi.points.PointsEarnPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties={"shop.points.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:points_earn;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EarnServiceTest {
    @Autowired private ApplicationContext context;
    @Autowired private PointTypeService types;
    @Autowired private PoolRequestService requests;
    @Autowired @Qualifier("payDataSource") private DataSource dataSource;

    @Test
    void race_for_last_budget_only_one_wins() throws Exception {
        assertThat(context.getBeanNamesForType(EarnService.class)).as("backed earning service required").isNotEmpty();
        String type="EN"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        types.create(new PointTypeService.CreateType(type,"CN",new RedeemRate(1,1),3000,365),"maker");
        types.activate(type,"maker");
        try {
            jdbc.update("INSERT INTO pts_fund_evidence(evidence_no,currency,external_minor,verified)"
                    + " VALUES(?,'CNY',100,1)","E:"+type);
            requests.submit(new PoolRequest("U:"+type,type,PoolRequest.Action.TOPUP_U,100,"maker","E:"+type));
            requests.approve("U:"+type,"checker");
            CyclicBarrier start=new CyclicBarrier(2);
            EarnService earning=context.getBean(EarnService.class);
            try (var workers=Executors.newFixedThreadPool(2)) {
                var results=workers.invokeAll(List.<Callable<PointsEarnPort.GrantOutcome>>of(
                        () -> { start.await(); return earning.grant(command(type,"S1")); },
                        () -> { start.await(); return earning.grant(command(type,"S2")); }));
                var statuses=List.of(results.get(0).get().status(),results.get(1).get().status());
                assertThat(statuses).containsExactlyInAnyOrder(PointsEarnPort.GrantStatus.GRANTED,
                        PointsEarnPort.GrantStatus.SKIPPED);
            }
            assertThat(jdbc.queryForObject("SELECT c_minor FROM pts_fund_pool WHERE type_code=?",Long.class,type))
                    .isEqualTo(100L);
            assertThat(jdbc.queryForObject("SELECT SUM(pending_points) FROM pts_account WHERE type_code=?"
                    + " AND holder_type='USER'",Long.class,type)).isEqualTo(100L);
        } finally { jdbc.update("UPDATE pts_point_type SET active_market=NULL WHERE type_code=?",type); }
    }
    private PointsEarnPort.GrantCommand command(String type,String sub) {
        return new PointsEarnPort.GrantCommand("EV:"+type+":"+sub,type+sub,"U",type,"WECHAT","MP",1,
                List.of(new PointsEarnPort.EarnLine("I", "G", "C",1,100,0,
                        new PointsEarnPort.EarnRule(PointsEarnPort.RuleMode.FIXED,100))));
    }
}
