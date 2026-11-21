package ai.neargo.shop.merchantbridge;
import ai.neargo.shop.merchant.brand.BrandFinancialChangeService;
import ai.neargo.shop.merchant.brand.ManagementModels.Actor;
import ai.neargo.shop.payclient.StoredValueFinanceAppService;
import ai.neargo.shop.spi.storedvalue.StoredValueMembership.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
/** Every Core state transition commits before the next Pay call; retries retain the original actor, command and binding. */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class BrandFinancialChangeCoordinator {
 private final BrandFinancialChangeService journal;private final StoredValueFinanceAppService pay;
 public BrandFinancialChangeCoordinator(BrandFinancialChangeService journal,StoredValueFinanceAppService pay){this.journal=journal;this.pay=pay;}
 public record View(String changeNo,String state,boolean staged){}
 public record Lookup(View original){}
 public Lookup findOriginal(Actor actor,String management,String store,String key){var p=journal.findOriginal(actor,management,store,key);return new Lookup(p==null?null:view(p));}
 public View start(Actor actor,String management,String store,String to,long expected,String reason,String key){
  var original=journal.byKey(actor,management,store,key,expected,to,reason);
  if(original==null){var source=journal.source(actor,management,store,to);var targets=pay.membershipTargets(new Describe(actor.tenantNo(),actor.actorKey(),management,store,source.entityNo(),source.brands()));original=journal.register(actor,management,store,to,expected,reason,key,targets);}
  return run(actor,management,original);
 }
 public View read(Actor actor,String management,String no){return view(journal.original(actor,management,no));}
 public View resume(Actor actor,String management,String no){return run(actor,management,journal.original(actor,management,no));}
 private View run(Actor actor,String management,BrandFinancialChangeService.Pending p){
  if("COMPLETED".equals(p.state()))return view(p);
  if("PREPARING".equals(p.state()))p=journal.prepared(actor,management,p.changeNo(),pay.prepareMembership(p.command()));
  p=journal.stage(actor,management,p.changeNo());var ack=pay.commitMembership(new Commit(p.command(),journal.expectedPayHash(p.command()),p.changeNo()));return view(journal.complete(actor,management,p.changeNo(),ack));
 }
 private View view(BrandFinancialChangeService.Pending p){return new View(p.changeNo(),p.state(),p.staged());}
}
