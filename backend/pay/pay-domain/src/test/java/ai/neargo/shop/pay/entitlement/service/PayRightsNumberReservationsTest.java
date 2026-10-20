package ai.neargo.shop.pay.entitlement.service;
import ai.neargo.shop.numbering.DocumentType;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class PayRightsNumberReservationsTest {
 @Test void reservesBothConsumptionFactsOnlyOnCommit() {
  Object[] a={null,"op",null,null,true,null};
  assertEquals(Map.of(DocumentType.ENTITLEMENT_USAGE,1,DocumentType.SV_VOUCHER,1),PayRightsNumberReservations.counts("StoredValueRedemptionPostingServiceImpl","finish",a));
  a[4]=false; assertTrue(PayRightsNumberReservations.counts("StoredValueRedemptionPostingServiceImpl","finish",a).isEmpty());
 }
 @Test void reservesRefundReopeningAndPreparationTogether() {
  assertEquals(Map.of(DocumentType.POINTS_VOUCHER,2),PayRightsNumberReservations.counts("PointsRefundPreparationServiceImpl","prepare",new Object[]{null}));
 }
 @Test void reservesBoundedStatementPageAndPublication() {
  assertEquals(Map.of(DocumentType.POINTS_VOUCHER,200),PayRightsNumberReservations.counts("StatementBuildServiceImpl","advance",new Object[]{"type","no"}));
  assertEquals(Map.of(DocumentType.POINTS_VOUCHER,400),PayRightsNumberReservations.counts("StoreStatementServiceImpl","draftGroups",new Object[]{null,"actor"}));
 }
 @Test void readsAndUnregisteredServicesDoNotReserve() {
  assertTrue(PayRightsNumberReservations.counts("PointsQueryServiceImpl","page",new Object[0]).isEmpty());
  assertTrue(PayRightsNumberReservations.counts("RedeemServiceImpl","quote",new Object[0]).isEmpty());
  assertTrue(PayRightsNumberReservations.counts("OtherServiceImpl","post",new Object[0]).isEmpty());
 }
 @Test void batchBudgetCountsEligibleRecipientsAndHandlesZeroAndThousand() {
  for (int eligible : new int[]{0,1,1000}) {
   var recipients=new java.util.ArrayList<ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.Recipient>();
   for(int i=0;i<eligible;i++) recipients.add(new ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.Recipient("U"+i,true,null));
   recipients.add(new ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.Recipient("SKIP",false,"INELIGIBLE"));
   var audience=new ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.AudienceSnapshot("MAIN","ENTITY","FACT",recipients);
   var snapshot=new ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.BatchSnapshot(audience,"VERSION",null,"hash",java.time.Instant.now());
   assertEquals(Map.of(DocumentType.ENTITLEMENT_ISSUE_BATCH,1,DocumentType.ENTITLEMENT_GRANT,eligible),
    PayRightsNumberReservations.counts("EntitlementIssueBatchWriter","create",new Object[]{null,null,null,null,snapshot}));
  }
 }

}
