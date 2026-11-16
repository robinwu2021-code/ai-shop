package ai.neargo.commerce.starter.security;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StoreOpeningRoutePolicyTest {
 @Test void openingRecoveryIsAccountScopedAndSettingsNeverBecomeAStoreIdentifier() {
  var p=new CommerceRoutePolicy();
  assertEquals(CommerceRoutePolicy.Lane.ACCOUNT,p.match("POST","/biz/merchant/store-openings").lane());
  assertEquals(CommerceRoutePolicy.Lane.ACCOUNT,p.match("GET","/biz/merchant/store-openings/OPEN-1").lane());
  assertEquals(CommerceRoutePolicy.Lane.ACCOUNT,p.match("POST","/biz/entities").lane());
  assertEquals(CommerceRoutePolicy.Lane.ACCOUNT,p.match("GET","/biz/entities").lane());
  assertEquals(CommerceRoutePolicy.Lane.ACCOUNT,p.match("GET","/biz/entities/create-results/CREATE-1").lane());
  for(String endpoint:new String[]{"GET /biz/store/settings","POST /biz/store/settings/basic","POST /biz/store/settings/hours","POST /biz/store/settings/contact"}) {
   var parts=endpoint.split(" ");var rule=p.match(parts[0],parts[1]);
   assertEquals(CommerceRoutePolicy.Lane.BOOTSTRAP,rule.lane());assertNull(rule.pathStore());
   assertFalse(p.allowsAccountRecovery(rule,parts[0],parts[1],null,null));
  }
  for(String endpoint:new String[]{"GET /biz/entities/E1/profile-template","GET /biz/entities/E1/profile-draft",
    "GET /biz/entities/E1/commands/C1","GET /biz/entities/E1/files/F1/preview","POST /biz/entities/E1/profile-draft",
    "POST /biz/entities/E1/review-submissions","POST /biz/entities/E1/files","POST /biz/entities/E1/revisions",
    "POST /biz/entities/E1/channel-requests","POST /biz/entities/E1/contact","POST /biz/entities/E1/revision-discards"}) {
   var parts=endpoint.split(" ");
   assertEquals(CommerceRoutePolicy.Lane.ACCOUNT,p.match(parts[0],parts[1]).lane(),endpoint);
  }
  assertEquals(CommerceRoutePolicy.Lane.DENY,p.match("PUT","/biz/merchant/store-openings/OPEN-1").lane());
  assertEquals(CommerceRoutePolicy.Lane.DENY,p.match("POST","/biz/store/settings/unknown").lane());
 }
}
