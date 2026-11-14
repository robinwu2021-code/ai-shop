package ai.neargo.inventory.svc.api;

import ai.neargo.inventory.api.projection.StockProjectionContracts.Mapping;
import ai.neargo.inventory.api.projection.StockProjectionContracts.MappingQuery;
import ai.neargo.inventory.api.projection.StockProjectionContracts.SessionCommand;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Apply;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Query;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Items;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Request;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

/** All projection HTTP shape validation runs once through Spring MVC binding. */
final class StockProjectionValidator implements Validator {
    @Override public boolean supports(Class<?> type) { return Request.class.isAssignableFrom(type); }
    @Override public void validate(Object target,Errors errors) {
        Request request=(Request) target;
        if(request.scope()==null)errors.rejectValue("scope","required");
        else {
            text(errors,"scope.entityNo",request.scope().entityNo(),32);
            text(errors,"scope.storeNo",request.scope().storeNo(),32);
        }
        if(request instanceof SessionCommand s)text(errors,"operationKey",s.operationKey(),64);
        if(request instanceof Query q){text(errors,"eventNo",q.eventNo(),64);hash(errors,q.payloadHash());}
        if(request instanceof Apply a) {
            text(errors,"eventNo",a.eventNo(),64);hash(errors,a.payloadHash());
            if(a.snapshot()==null)errors.rejectValue("snapshot","required");
            else {
                text(errors,"snapshot.itemNo",a.snapshot().itemNo(),32);
                text(errors,"snapshot.baseUnit",a.snapshot().baseUnit(),16);
            }
        }
        if(request instanceof Items i) {
            if(i.itemNos()==null||i.itemNos().size()>200)errors.rejectValue("itemNos","size");
            else for(String item:i.itemNos())if(invalid(item,32)){errors.rejectValue("itemNos","item");break;}
        }
        if(request instanceof MappingQuery q) {
            if(q.mappings()==null||q.mappings().size()>200)errors.rejectValue("mappings","size");
            else for(int n=0;n<q.mappings().size();n++) {
                Mapping m=q.mappings().get(n);
                if(m==null){errors.rejectValue("mappings","required");continue;}
                text(errors,"mappings["+n+"].itemNo",m.itemNo(),32);
                text(errors,"mappings["+n+"].baseUnit",m.baseUnit(),16);
            }
        }
    }
    private static void hash(Errors e,String hash) {
        if(hash==null||!hash.matches("[a-f0-9]{64}"))e.rejectValue("payloadHash","hash");
    }
    private static boolean invalid(String s,int max){return s==null||s.isBlank()||s.length()>max||!s.equals(s.trim());}
    private static void text(Errors e,String path,String value,int max){if(invalid(value,max))e.rejectValue(path,"text");}
}
