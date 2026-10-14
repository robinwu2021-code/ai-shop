package ai.neargo.shop.pay.points;

import ai.neargo.shop.pay.points.model.RedeemRate;
import ai.neargo.shop.pay.points.policy.RedeemPolicy;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class RedeemPolicyTest {
    @Test
    void eligible_shares_are_aligned() {
        var result=policy().calculate(99,99,5000,List.of(new RedeemPolicy.Target("S1","A",10,true),
                new RedeemPolicy.Target("S2","B",10,true)),new RedeemRate(3,2));
        assertThat(result.points()).isEqualTo(15);
        assertThat(result.minor()).isEqualTo(10);
        assertThat(result.shares().getFirst().points()).isEqualTo(9);
        assertThat(result.shares().get(1).points()).isEqualTo(6);
    }
    @Test
    void ineligible_stores_and_nonredeemable_minor_remainders_are_excluded() {
        var result=policy().calculate(99,99,10000,List.of(new RedeemPolicy.Target("S1","A",1,true),
                new RedeemPolicy.Target("S2","B",2,true),new RedeemPolicy.Target("S3","C",999,false)),
                new RedeemRate(3,2));
        assertThat(result.points()).isEqualTo(3);
        assertThat(result.minor()).isEqualTo(2);
        assertThat(result.shares()).hasSize(1);
        assertThat(result.shares().getFirst().subOrderNo()).isEqualTo("S2");
    }
    private RedeemPolicy policy() {
        try { return (RedeemPolicy)Class.forName("ai.neargo.shop.pay.points.policy.DefaultRedeemPolicy")
                .getConstructor().newInstance(); }
        catch (ReflectiveOperationException e) { throw new AssertionError("Integer redemption policy missing",e); }
    }
}
