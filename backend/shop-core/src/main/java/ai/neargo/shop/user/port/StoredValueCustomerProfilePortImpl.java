package ai.neargo.shop.user.port;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.StoredValueCustomerProfilePort;
import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.mapper.UserMappers.UserMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class StoredValueCustomerProfilePortImpl implements StoredValueCustomerProfilePort {
    private final ai.neargo.shop.spi.user.PersonPort people;
    private final UserMapper users;
    private final MerchantScopeFactsPort merchants;
    public StoredValueCustomerProfilePortImpl(UserMapper users,MerchantScopeFactsPort merchants,ai.neargo.shop.spi.user.PersonPort people){this.users=users;this.merchants=merchants;this.people=people;}
    @Override public List<String> search(String tenant,String entity,String store,String keyword,List<String> ids){
        owned(tenant,entity,store);
        if(keyword==null||keyword.isBlank()||keyword.length()>100)throw BizException.of(ErrorCode.BAD_REQUEST);
        if(ids==null||ids.size()>100)throw BizException.of(ErrorCode.BAD_REQUEST);
        if(ids.isEmpty())return List.of();
        String term=keyword.strip().replace("\\","\\\\").replace("%","\\%").replace("_","\\_");
        var rows=DataScopeContext.executeWithoutScope(()->users.selectList(Wrappers.<UsrAccount>lambdaQuery()
                .select(UsrAccount::getUserNo).eq(UsrAccount::getTenantNo,tenant).in(UsrAccount::getUserNo,ids)
                .and(q->q.like(UsrAccount::getNickname,term).or().likeLeft(UsrAccount::getPhone,term))
                .orderByAsc(UsrAccount::getUserNo)));
        var matches=new java.util.HashSet<>(rows.stream().map(UsrAccount::getUserNo).toList());
        String exact=keyword.matches("1[3-9][0-9]{9}")?people.findByPhone(keyword).map(ai.neargo.shop.spi.user.PersonPort.PersonView::personNo).orElse(null):null;
        for(String id:ids){var person=people.find(id);if(person.isPresent()&&(id.equals(exact)
                ||person.get().phoneMasked()!=null&&person.get().phoneMasked().endsWith(keyword)))matches.add(id);}
        return ids.stream().filter(matches::contains).toList();
    }
    @Override public Map<String,Profile> profiles(String tenant,String entity,String store,List<String> ids){
        owned(tenant,entity,store);if(ids==null||ids.size()>100)throw BizException.of(ErrorCode.BAD_REQUEST);
        if(ids.isEmpty())return Map.of();
        var result=DataScopeContext.executeWithoutScope(()->users.selectList(Wrappers.<UsrAccount>lambdaQuery()
                .select(UsrAccount::getUserNo,UsrAccount::getNickname,UsrAccount::getPhone)
                .eq(UsrAccount::getTenantNo,tenant).in(UsrAccount::getUserNo,ids)))
                .stream().collect(Collectors.toMap(UsrAccount::getUserNo,u->new Profile(
                        maskedName(u.getNickname()),masked(u.getPhone()))));
        for(String id:ids)if(!result.containsKey(id))people.find(id).ifPresent(p->result.put(id,new Profile("顾客",p.phoneMasked())));
        return result;
    }
    private void owned(String tenant,String entity,String store){
        if(!merchants.storesOwned(tenant,entity,Set.of(store)))throw BizException.of(ErrorCode.NOT_FOUND);
    }
    static String maskedName(String name){
        if(name==null||name.isBlank())return "顾客";
        return name.substring(0,name.offsetByCodePoints(0,1))+"**";
    }
    private static String masked(String phone){
        if(phone==null||phone.length()<4)return "";
        return (phone.length()>=7?phone.substring(0,3):"")+"****"+phone.substring(phone.length()-4);
    }
}
