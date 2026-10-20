package ai.neargo.shop.pay.points;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.pay.points.earn.impl.EarnServiceImpl;
import ai.neargo.shop.pay.points.lifecycle.AccountExpiryGuard;
import ai.neargo.shop.pay.points.posting.PointsPostingService;
import ai.neargo.shop.pay.points.store.*;
import ai.neargo.shop.pay.points.model.*;
import ai.neargo.shop.spi.points.PointsEarnPort.*;
import org.junit.jupiter.api.Test;
import java.util.*;
class AcceptedEarnBudgetTest {
 @Test void acceptedGrantWithInsufficientBudgetRemainsRetryableAndDoesNotPostSkippedVoucher(){
  var read=mock(PointsReadStore.class);var config=mock(PointsConfigurationStore.class);var posting=mock(PointsPostingService.class);
  when(read.position(anyString(),anyString())).thenReturn(null);
  when(config.lockLifecycle(anyString(),anyString())).thenReturn(null);
  when(read.type("P")).thenReturn(Optional.of(new PointType("P","P","CN","CNY",2,new RedeemRate(1,1),3000,365,PointType.Status.ACTIVE)));
  when(read.currentUser("P","U")).thenReturn(new AccountSnapshot("P",HolderType.USER,"U",Map.of(),false,null,0));
  when(read.pool("P")).thenReturn(new FundPool("P","CNY",0,0,0,0,0,0,0));
  var service=new EarnServiceImpl(read,config,posting,mock(AccountExpiryGuard.class),true);
  var command=new GrantCommand("E","O","U","P","S","WECHAT","ONLINE",1,List.of(new EarnLine("I","G","C",1,10000,0,new EarnRule(RuleMode.RATIO,100))));
  assertThatThrownBy(()->service.grantAccepted(command)).hasMessage("POINTS_POOL_INSUFFICIENT");
  verifyNoInteractions(posting);
 }
}
