package ai.neargo.shop.scenario;

import ai.neargo.shop.pay.points.model.Bucket;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PoolRequest;
import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.pool.PoolRequestService;
import ai.neargo.shop.pay.points.store.PointsReadStore;
import ai.neargo.shop.pay.points.type.PointTypeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PoolServiceTest {
    @Autowired private PointTypeService types;
    @Autowired private PoolRequestService requests;
    @Autowired private PointsReadStore read;
    @Autowired @Qualifier("payDataSource") private DataSource dataSource;

    @Test
    void reserve_funding_does_not_mint() {
        String type = type();
        approved(type,PoolRequest.Action.TOPUP_B,100,"B");
        assertThat(read.pool(type).b()).isEqualTo(100L);
        assertThat(read.account(type,HolderType.MINT,"PLATFORM").points(Bucket.ISSUED)).isZero();
        assertThat(read.account(type,HolderType.ISSUE,"PLATFORM").points(Bucket.ISSUABLE)).isZero();
    }

    @Test
    void internal_budget_transfer_and_free_receiving_reuse_do_not_mint_twice() {
        String type = type();
        approved(type,PoolRequest.Action.TOPUP_U,100,"U1");
        approved(type,PoolRequest.Action.TRANSFER_U_TO_B,100,"MOVE");
        assertThat(read.pool(type).u()).isZero();
        assertThat(read.pool(type).b()).isEqualTo(100L);
        assertThat(read.account(type,HolderType.RECEIVE,"PLATFORM").points(Bucket.RECIRCULATABLE)).isEqualTo(100L);
        approved(type,PoolRequest.Action.TOPUP_U,100,"U2");
        assertThat(read.pool(type).u()).isEqualTo(100L);
        assertThat(read.account(type,HolderType.RECEIVE,"PLATFORM").points(Bucket.RECIRCULATABLE)).isZero();
        assertThat(read.account(type,HolderType.MINT,"PLATFORM").points(Bucket.ISSUED)).isEqualTo(-100L);
    }

    @Test
    void refund_buffer_cash_need_not_be_divisible_by_the_points_rate() {
        String no="PS"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        types.create(new PointTypeService.CreateType(no,"CN",new RedeemRate(2,3),3000,365),"maker");
        approved(no,PoolRequest.Action.TOPUP_B,2,"B");
        assertThat(read.pool(no).b()).isEqualTo(2);
        assertThat(read.account(no,HolderType.MINT,"PLATFORM").points(Bucket.ISSUED)).isZero();
    }

    private String type() {
        String no="PS"+UUID.randomUUID().toString().replace("-", "").substring(0,20);
        types.create(new PointTypeService.CreateType(no,"CN",new RedeemRate(1,1),3000,365),"maker");
        return no;
    }
    private void approved(String type, PoolRequest.Action action, long amount, String suffix) {
        String no=suffix+":"+type;
        String receipt=action==PoolRequest.Action.TRANSFER_U_TO_B?null:"BANK:"+no;
        if (receipt!=null) {
            new JdbcTemplate(dataSource).update("INSERT INTO pts_fund_evidence(evidence_no,currency,external_minor,verified)"
                    + " VALUES(?,'CNY',?,1)",receipt,amount);
        }
        requests.submit(new PoolRequest(no,type,action,amount,"maker",receipt));
        requests.approve(no,"checker");
    }
}
