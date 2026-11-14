package ai.neargo.shop.pay.points;
import static org.assertj.core.api.Assertions.*;
import ai.neargo.shop.pay.points.policy.*;
import ai.neargo.shop.pay.points.model.RedeemRate;
import java.util.*;
import org.junit.jupiter.api.Test;
class DelayedEarnPolicyTest {
 @Test void refundBeforeRetryReducesGrantUsingOriginalGoodsBaseline(){
  var original=new EarnPolicy.EarnDecision(80,80,List.of(new EarnPolicy.LineDecision("I",80,80,8000,0,8000)));
  var result=DelayedEarnPolicy.apply(original,Map.of("I",10000L),Map.of("I",2500L),new RedeemRate(1,1));
  assertThat(result.grantedPoints()).isEqualTo(60);
  assertThat(result.lines().getFirst().originalGoodsMinor()).isEqualTo(10000);
  assertThat(result.lines().getFirst().refundedBeforeGrantMinor()).isEqualTo(2500);
 }
 @Test void fullRefundBeforeRetryGrantsNothing(){
  var original=new EarnPolicy.EarnDecision(80,80,List.of(new EarnPolicy.LineDecision("I",80,80,8000,0,8000)));
  assertThat(DelayedEarnPolicy.apply(original,Map.of("I",10000L),Map.of("I",10000L),new RedeemRate(1,1)).grantedPoints()).isZero();
 }
 @Test void laterRefundOnlyReclaimsTheDeltaAfterDelayedGrant(){
  var original=new EarnPolicy.EarnDecision(80,80,List.of(new EarnPolicy.LineDecision("I",80,80,8000,0,8000)));
  var line=DelayedEarnPolicy.apply(original,Map.of("I",10000L),Map.of("I",2500L),new RedeemRate(1,1)).lines().getFirst();
  var policy=new RefundRatioPolicy();
  assertThat(policy.reclaimDelta(new RedeemRate(1,1),line.grantedPoints(),line.originalGoodsMinor(),line.refundedBeforeGrantMinor(),line.grantBasisMinor(),5000,0)).isEqualTo(20);
  assertThat(policy.reclaimDelta(new RedeemRate(1,1),line.grantedPoints(),line.originalGoodsMinor(),line.refundedBeforeGrantMinor(),line.grantBasisMinor(),5000,20)).isZero();
 }

}
