package ai.neargo.shop.pay.storedvalue.settlement.impl;

import static ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository.text;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.CursorPage;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.service.EntitlementRecords;
import ai.neargo.shop.pay.storedvalue.issuer.IssuerRepository;
import ai.neargo.shop.pay.storedvalue.issuer.StoredValueFinancialMembershipService;
import ai.neargo.shop.pay.storedvalue.report.StoredValueSummaryService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCursorCodec;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueCrossEntityReadiness;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueFinanceService;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementDecisionService;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementPaymentService;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementQueryService;
import ai.neargo.shop.pay.storedvalue.settlement.StoredValueSettlementService;
import ai.neargo.shop.spi.storedvalue.*;
import ai.neargo.shop.spi.storedvalue.StoredValueFinance;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership;
import ai.neargo.shop.spi.storedvalue.StoredValueReports;
import ai.neargo.shop.spi.storedvalue.StoredValueSettlements;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/** Same Pay-owned entry points are used by embedded calls and authenticated private HTTP adapters. */
@Service("storedValueFinanceService")
public class StoredValueFinanceServiceImpl implements StoredValueFinanceService {
 @org.springframework.beans.factory.annotation.Autowired private ai.neargo.shop.pay.storedvalue.issuer.StoredValueCollectionGuard collection;
 @org.springframework.beans.factory.annotation.Autowired private ai.neargo.shop.pay.storedvalue.issuer.StoredValueIssuerQueryService issuerQueries;
 @org.springframework.beans.factory.annotation.Autowired private ai.neargo.shop.pay.storedvalue.report.StoredValueDailySummaryProjector projector;
 private final StoredValueFinancialMembershipService membership;private final IssuerRepository db;private final StoredValueCrossEntityReadiness readiness;private final StoredValueSummaryService reports;private final StoredValueSettlementQueryService queries;private final StoredValueSettlementService bills;private final StoredValueSettlementDecisionService decisions;private final StoredValueSettlementPaymentService payments;private final StoredValueCursorCodec cursors;
 public StoredValueFinanceServiceImpl(IssuerRepository db,StoredValueCrossEntityReadiness readiness,StoredValueSummaryService reports,StoredValueSettlementQueryService queries,StoredValueSettlementService bills,StoredValueSettlementDecisionService decisions,StoredValueSettlementPaymentService payments,StoredValueCursorCodec cursors,StoredValueFinancialMembershipService membership){this.membership=membership;this.db=db;this.readiness=readiness;this.reports=reports;this.queries=queries;this.bills=bills;this.decisions=decisions;this.payments=payments;this.cursors=cursors;}
 public StoredValueFinance.Capabilities capabilities(){var c=readiness.capability();return new StoredValueFinance.Capabilities(2,readiness.reportsReady(),readiness.settlementReady(),c.ready(),false,c.blockers());}
 public List<StoredValueFinance.Resource> directory(StoredValueFinance.Directory c){
  if(c.scopes().isEmpty())return List.of();if("FINANCE".equals(c.purpose())&&c.entityNos().isEmpty())throw BizException.of(ErrorCode.FORBIDDEN);
  var args=new ArrayList<Object>();args.add(c.tenantNo());String scopeSql=String.join(" OR ",Collections.nCopies(c.scopes().size(),"(i.scope_type=? AND i.scope_no=?)"));for(var ref:c.scopes()){args.add(ref.scopeType());args.add(ref.scopeNo());}
  String sql="SELECT i.issuer_no,i.scope_type,i.scope_no,i.name,v.currency,v.market_zone FROM sv_issuer i JOIN sv_issuer_version v ON CAST(v.tenant_no AS BINARY(256))=CAST(i.tenant_no AS BINARY(256)) AND CAST(v.issuer_no AS BINARY(256))=CAST(i.issuer_no AS BINARY(256)) AND CAST(v.issuer_version_no AS BINARY(256))=CAST(i.current_issuer_version_no AS BINARY(256)) WHERE i.tenant_no=? AND i.deleted=0 AND v.deleted=0 AND ("+scopeSql+")";
  if("FINANCE".equals(c.purpose())){var entities=c.entityNos().stream().sorted().toList();String placeholders=String.join(",",Collections.nCopies(entities.size(),"?"));sql+=" AND (v.settlement_entity_no IN ("+placeholders+") OR EXISTS(SELECT 1 FROM sv_issuer_store_access s WHERE CAST(s.tenant_no AS BINARY(256))=CAST(i.tenant_no AS BINARY(256)) AND CAST(s.issuer_no AS BINARY(256))=CAST(i.issuer_no AS BINARY(256)) AND s.deleted=0 AND s.operating_entity_no IN ("+placeholders+")) OR EXISTS(SELECT 1 FROM sv_settlement_bill b WHERE CAST(b.tenant_no AS BINARY(256))=CAST(i.tenant_no AS BINARY(256)) AND CAST(b.issuer_no AS BINARY(256))=CAST(i.issuer_no AS BINARY(256)) AND b.deleted=0 AND (b.payer_entity_no IN ("+placeholders+") OR b.payee_entity_no IN ("+placeholders+"))))";for(int n=0;n<4;n++)args.addAll(entities);}
  return db.rows(sql+" ORDER BY i.scope_type,i.scope_no,i.issuer_no",args.toArray()).stream().map(r->new StoredValueFinance.Resource(text(r,"issuer_no"),text(r,"scope_type"),text(r,"scope_no"),text(r,"name"),text(r,"currency"),text(r,"market_zone"))).toList();
 }
 public StoredValueReports.Summary summary(StoredValueFinance.SummaryRequest c){return reports.summarize(c.scope(),c.period());}
 public CursorPage<StoredValueReports.StoreAttribution> stores(StoredValueReports.Query c){return reports.stores(c);}
 public CursorPage<StoredValueReports.ActivityAttribution> activities(StoredValueReports.Query c){return reports.activities(c);}
 public CursorPage<StoredValueReports.Summary> localIssuers(StoredValueFinance.BatchReports c){
  if(c.scopes().isEmpty())return new CursorPage<>(List.of(),null,false);var sorted=c.scopes().stream().sorted(Comparator.comparing(StoredValueReports.Scope::issuerNo)).toList();var first=sorted.getFirst();String filter=EntitlementRecords.digest(db.encode(List.of(sorted,c.period(),c.issuerPeriods())));var after=cursors.decode(c.cursor(),"SV_STORE_ISSUER_REPORTS",first.tenantNo(),first.actorNo(),filter);var candidates=sorted.stream().filter(s->after==null||s.issuerNo().compareTo(after.sortKey())>0).limit(c.size()+1L).toList();boolean more=candidates.size()>c.size();var shown=candidates.subList(0,Math.min(c.size(),candidates.size()));String next=more?cursors.encode("SV_STORE_ISSUER_REPORTS",first.tenantNo(),first.actorNo(),filter,Instant.now().toEpochMilli(),shown.getLast().issuerNo()):null;return new CursorPage<>(shown.stream().map(s->reports.summarize(s,c.issuerPeriods().getOrDefault(s.issuerNo(),c.period()))).toList(),next,more);
 }
 public StoredValueSettlements.Location locate(StoredValueSettlements.Resource c){return queries.locate(c);}
 public CursorPage<StoredValueSettlements.Bill> bills(StoredValueSettlements.Query c){return queries.bills(c);}
 public StoredValueSettlements.Bill detail(StoredValueFinance.BillRead c){return queries.detail(c.authority(),c.billNo());}
 public CursorPage<StoredValueSettlements.Item> items(StoredValueFinance.BillRead c){return queries.items(c.authority(),c.billNo(),c.cursor(),c.size());}
 public CursorPage<StoredValueSettlements.Event> events(StoredValueFinance.BillRead c){return queries.events(c.authority(),c.billNo(),c.cursor(),c.size());}
 public CursorPage<StoredValueSettlements.Payment> payments(StoredValueFinance.BillRead c){return queries.payments(c.authority(),c.billNo(),c.cursor(),c.size());}
 public StoredValueSettlements.Generated generate(StoredValueSettlements.Generation c){return bills.generate(c);}
 public StoredValueSettlements.Bill reconcile(StoredValueSettlements.Decision c){return decisions.reconcile(c);}
 public StoredValueSettlements.Bill dispute(StoredValueSettlements.Decision c){return decisions.dispute(c);}
 public StoredValueSettlements.Bill resolveDispute(StoredValueSettlements.Resolution c){return decisions.resolve(c);}
 public StoredValueSettlements.Bill adjust(StoredValueSettlements.Adjustment c){return decisions.adjust(c);}
 public StoredValueSettlements.Payment register(StoredValueSettlements.Registration c){return payments.register(c);}
 public StoredValueSettlements.Payment confirm(StoredValueSettlements.Receipt c){return payments.confirm(c);}
 public StoredValueSettlements.Payment voidPayment(StoredValueSettlements.VoidPayment c){return payments.voidRegistration(c);}
 public StoredValueFinance.Resolution resolvePayment(StoredValueFinance.ResolvePayment c){var p=payments.resolve(c.authority(),c.billNo(),c.commandKey());return new StoredValueFinance.Resolution(p!=null,p);}
 public List<StoredValueMembership.Target> membershipTargets(StoredValueMembership.Describe c){return membership.describe(c);}
 public StoredValueMembership.Ack prepareMembership(StoredValueMembership.Prepare c){return membership.prepare(c);}
 public StoredValueMembership.Ack commitMembership(StoredValueMembership.Commit c){return membership.commit(c);}


 public StoredValueCollections.Lookup collectionRequirement(StoredValueWallets.ExecutionScope c){return new StoredValueCollections.Lookup(collection.requirement(c));}
 public StoredValueIssuers.StoreOptions collectionOptions(StoredValueCollections.Options c){return issuerQueries.storeOptions(c.scope(),c.proof());}
 public StoredValueReports.ProjectionResult rebuild(StoredValueReports.Projection c){return projector.rebuild(c.scope(),c.date(),c.afterRelease(),c.afterStore(),c.size());}
}
