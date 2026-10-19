package ai.neargo.shop.merchant.port;
import ai.neargo.shop.spi.user.StoreFinanceRecipientsPort;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchAccountMapper;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.common.data.scope.DataScopeContext;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import java.util.*;
@Component
public class StoreFinanceRecipientsPortImpl implements StoreFinanceRecipientsPort {
 private final MchAccountMapper accounts; private final BizIdentityResolver identities;
 public StoreFinanceRecipientsPortImpl(MchAccountMapper accounts,BizIdentityResolver identities){this.accounts=accounts;this.identities=identities;}
 public List<String> recipients(String merchant,String store){return DataScopeContext.executeWithoutScope(()->{
  if(merchant==null || store==null)return List.of();
  var active=accounts.selectList(Wrappers.<MchAccount>lambdaQuery().eq(MchAccount::getEntityNo,merchant).eq(MchAccount::getStatus,MchAccount.ACTIVE).last("LIMIT 501"));
  if(active.size()>500)throw new IllegalStateException("POINTS_FINANCE_RECIPIENT_LIMIT");
  Set<String> result=new LinkedHashSet<>();
  for(var account:active){String receiver=account.getUserNo();if(receiver==null || receiver.isBlank())receiver=account.getMchAccountNo();
   var context=identities.resolve(receiver,store);
   // The resolver selects the subject, while BizContextFilter selects an authorized store.
   // Check membership first, then evaluate the permission in the requested store.
   if(context!=null && merchant.equals(context.merchantNo()) && context.storeNos()!=null
       && context.storeNos().contains(store) && context.withStore(store).can(BizPerms.FINANCE))result.add(receiver);
  }
  return List.copyOf(result);
 });}
}
