package ai.neargo.shop.commercebridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.commerce.api.mall.reporting.*;
import ai.neargo.commerce.api.mall.reporting.PurchaseReporting.*;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService;
import ai.neargo.shop.merchant.plan.EntityPlanPurchaseService.Actor;
import ai.neargo.shop.pay.service.PlatformCashReporting;
import ai.neargo.shop.commercebridge.PlatformMallAccess.Context;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

@Service
@Profile("ops")
@ConditionalOnProperty(name={"commerce.enabled","commerce.mall.http.enabled","commerce.mall.enabled","commerce.mall.payment.enabled","commerce.mall.offline.enabled"},havingValue="true")
public class PlatformMallReporting {
  private final PurchaseOrderQueryPort stores;
  private final EntityPlanPurchaseService entities;
  private final ObjectProvider<PlatformCashReporting> cash;
  public PlatformMallReporting(PurchaseOrderQueryPort stores,EntityPlanPurchaseService entities,ObjectProvider<PlatformCashReporting> cash) {
    this.stores=stores;this.entities=entities;this.cash=cash;
  }
  public record OrderPage(List<Row> items,String nextCursor,Set<Model> includedModels,long queriedAt) {}
  public record CashRow(String paymentNo,String orderNo,String originalPaymentNo,String direction,String currency,String amountMinor,long occurredAt,String classification,Model model,String entityNo,String storeNo) {}
  public record Summary(Model model,String currency,String classification,String receivedMinor,String refundedMinor,String netMinor,long receiptCount,long refundCount) {}
  public record CashPage(List<Summary> summary,List<CashRow> items,String nextCursor,long queriedAt,String coverage) {}
  private static final Comparator<CashRow> CASH_ORDER=Comparator.comparingLong(CashRow::occurredAt).reversed().thenComparing(CashRow::paymentNo,Comparator.reverseOrder());
  public OrderPage orders(Context context,Filter filter,String after,int limit) {
    validate(filter,limit);
    var query=new Query(context.access(),filter,decodeOrder(after),limit+1);
    var rows=new ArrayList<Row>();
    if(context.models().contains(Model.ENTITY)) rows.addAll(entities.searchOperatorOrders(actor(context),query));
    if(context.models().contains(Model.STORE)) rows.addAll(stores.search(query));
    rows.sort(PurchaseReporting.ORDER);
    boolean more=rows.size()>limit;
    var page=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
    return new OrderPage(page,more?encodeOrder(page.getLast()):null,context.models(),System.currentTimeMillis());
  }
  public CashPage cash(Context context,long from,long to,String currency,String entityNo,String storeNo,String after,int limit) {
    validate(new Filter(entityNo,storeNo,null,null,currency,null,null,false),limit);
    final PlatformCashReporting.Query query;
    try { query=new PlatformCashReporting.Query("MAIN",from,to,currency); }
    catch(IllegalArgumentException e) { throw bad(); }
    var provider=cash.getIfAvailable();
    if(provider==null) throw new IllegalStateException("Platform cash reporting unavailable");
    CashRow cursor=decodeCash(after);
    var page=new PriorityQueue<CashRow>(limit+2,CASH_ORDER.reversed());
    var sums=new TreeMap<String,Accumulator>();
    provider.scan(query,batch -> {
      var orderNos=batch.stream().map(PlatformCashReporting.Fact::orderNo).filter(Objects::nonNull).distinct().toList();
      var owners=new HashMap<String,List<Row>>();
      if(!orderNos.isEmpty()) {
        // Attribution requires BOTH sources, even if the caller may only view one. No raw owners escape.
        var all=new ArrayList<>(entities.findOperatorOrderOwners(actor(context),"MAIN",orderNos));
        all.addAll(stores.findByOrderNos("MAIN",orderNos));
        for(var row:all) owners.computeIfAbsent(row.orderNo(),k->new ArrayList<>()).add(row);
      }
      for(var fact:batch) {
        var matches=owners.getOrDefault(fact.orderNo(),List.of());
        var visible=matches.stream().filter(r -> context.models().contains(r.model()) && context.access().allows(r.entityNo())
          && (entityNo==null || entityNo.equals(r.entityNo())) && (storeNo==null || storeNo.equals(r.storeNo()))).toList();
        if(matches.isEmpty()) {
          if(context.access().allEntities() && context.models().size()==2 && entityNo==null && storeNo==null) throw inconsistent();
          continue;
        }
        if(visible.isEmpty()) continue;
        if(matches.size()!=1 || !fact.verified() || fact.amountMinor()<=0 || !Set.of("PAY","REFUND").contains(fact.direction())
          || fact.currency()==null || !fact.currency().matches("[A-Z]{3}") || !Set.of("NORMAL","UNMATCHED","CLOSED_ORDER").contains(fact.classification())) throw inconsistent();
        var owner=visible.getFirst();
        var key=owner.model()+"/"+fact.currency()+"/"+fact.classification();
        sums.computeIfAbsent(key,k->new Accumulator(owner.model(),fact.currency(),fact.classification())).add(fact);
        var row=new CashRow(fact.paymentNo(),fact.orderNo(),fact.originalPaymentNo(),fact.direction(),fact.currency(),Long.toString(fact.amountMinor()),fact.occurredAt(),fact.classification(),owner.model(),owner.entityNo(),owner.storeNo());
        if(cursor!=null && CASH_ORDER.compare(row,cursor)<=0) continue;
        page.add(row); if(page.size()>limit+1) page.poll();
      }
    });
    var rows=new ArrayList<>(page); rows.sort(CASH_ORDER);
    boolean more=rows.size()>limit;
    var items=List.copyOf(rows.subList(0,Math.min(limit,rows.size())));
    return new CashPage(sums.values().stream().map(Accumulator::summary).toList(),items,more?encodeCash(items.getLast()):null,System.currentTimeMillis(),"CONSISTENT_PER_REQUEST");
  }
  private static final class Accumulator {
    final Model model; final String currency,classification;
    BigInteger received=BigInteger.ZERO,refunded=BigInteger.ZERO; long receipts,refunds;
    Accumulator(Model model,String currency,String classification){this.model=model;this.currency=currency;this.classification=classification;}
    void add(PlatformCashReporting.Fact f){if("PAY".equals(f.direction())){received=received.add(BigInteger.valueOf(f.amountMinor()));receipts++;}else{refunded=refunded.add(BigInteger.valueOf(f.amountMinor()));refunds++;}}
    Summary summary(){return new Summary(model,currency,classification,received.toString(),refunded.toString(),received.subtract(refunded).toString(),receipts,refunds);}
  }
  private static Actor actor(Context c){return new Actor(c.operatorNo(),null,null,true);}
  private static void validate(Filter f,int limit){
    if(limit<1 || limit>100) throw bad();
    for(String value:Arrays.asList(f.entityNo(),f.storeNo(),f.orderNo(),f.status())) if(value!=null && (value.isBlank() || value.length()>64)) throw bad();
    if(f.currency()!=null && !f.currency().matches("[A-Z]{3}")) throw bad();
    if(f.createdFrom()!=null && f.createdFrom()<0 || f.createdTo()!=null && f.createdTo()<0 || f.createdFrom()!=null && f.createdTo()!=null && f.createdTo()<=f.createdFrom()) throw bad();
  }
  private static String encode(String raw){return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));}
  private static String decode(String raw){try{if(raw.length()>512)throw bad();return new String(Base64.getUrlDecoder().decode(raw),StandardCharsets.UTF_8);}catch(IllegalArgumentException e){throw bad();}}
  private static String encodeOrder(Row r){return encode(r.createdAt()+"\n"+r.model()+"\n"+r.orderNo());}
  private static Cursor decodeOrder(String raw){if(raw==null)return null;try{var p=decode(raw).split("\n",-1);if(p.length!=3 || p[2].isBlank() || p[2].length()>64)throw bad();long t=Long.parseLong(p[0]);if(t<0)throw bad();return new Cursor(t,Model.valueOf(p[1]),p[2]);}catch(IllegalArgumentException e){throw bad();}}
  private static String encodeCash(CashRow r){return encode(r.occurredAt()+"\n"+r.paymentNo());}
  private static CashRow decodeCash(String raw){if(raw==null)return null;try{var p=decode(raw).split("\n",-1);if(p.length!=2 || p[1].isBlank() || p[1].length()>64)throw bad();long t=Long.parseLong(p[0]);if(t<0)throw bad();return new CashRow(p[1],null,null,null,null,null,t,null,null,null,null);}catch(IllegalArgumentException e){throw bad();}}
  private static BizException bad(){return new BizException(ErrorCode.BAD_REQUEST,"Invalid platform mall reporting query");}
  private static BizException inconsistent(){return new BizException(ErrorCode.CONFLICT,"Platform cash facts require reconciliation");}
}
