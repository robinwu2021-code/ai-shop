package ai.neargo.shop.paybridge;

import ai.neargo.shop.payclient.StoredValuePlanRechargeAppService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.storedvalue.StoredValueCustomerBindingPort;
import ai.neargo.shop.spi.user.PersonPort;
import org.springframework.stereotype.Service;

@Service
public class StoredValueCustomerBindingBridge implements StoredValueCustomerBindingPort {
    private final PersonPort people;
    private final StoredValuePlanRechargeAppService pay;
    public StoredValueCustomerBindingBridge(PersonPort people,StoredValuePlanRechargeAppService pay){this.people=people;this.pay=pay;}
    @Override public void ensureByUser(EntitlementContext ctx,String user){
        if(user==null)return;
        people.findByUser(user).filter(p->user.equals(p.userNo())).ifPresent(p->pay.bindCustomer(ctx,p.personNo(),user));
    }
}
