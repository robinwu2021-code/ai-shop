package ai.neargo.commerce.starter.security;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StoreOpeningRoutePolicyTest {
 @Test void openingRecoveryIsAccountScopedAndSettingsNeverBecomeAStoreIdentifier() {
  var p=new CommerceRoutePolicy();
  assertEquals(CommerceRoutePolicy.Lane.ACCOUNT,p.match("POST","/biz/merchant/store-openings").lane());
  assertEquals(CommerceRoutePolicy.Lane.ACCOUNT,p.match("GET","/biz/merchant/store-openings/OPEN-1").lane());
  for(String endpoint:new String[]{"GET /biz/store/settings","POST /biz/store/settings/basic","POST /biz/store/settings/hours"}) {
   var parts=endpoint.split(" ");var rule=p.match(parts[0],parts[1]);
   assertEquals(CommerceRoutePolicy.Lane.BOOTSTRAP,rule.lane());assertNull(rule.pathStore());
   assertFalse(p.allowsAccountRecovery(rule,parts[0],parts[1],null,null));
  }
  assertEquals(CommerceRoutePolicy.Lane.DENY,p.match("PUT","/biz/merchant/store-openings/OPEN-1").lane());
  assertEquals(CommerceRoutePolicy.Lane.DENY,p.match("POST","/biz/store/settings/unknown").lane());
 }
}
