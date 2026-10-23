package ai.neargo.shop.food.foodclient;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.food.dto.CreateMenuChannelBindingReq;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.dto.UpdateMenuChannelBindingReq;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.food.spi.MenuChannelBindingInternalApi;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import ai.neargo.svc.client.ServiceCalls;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

/** Rejects malformed binding receipts without retrying account-sensitive mutation calls. */
public class RemoteMenuChannelBindingService implements MenuChannelBindingService {
    private final MenuChannelBindingInternalApi api;
    public RemoteMenuChannelBindingService(MenuChannelBindingInternalApi api) { this.api=api; }
    @Override public MenuChannelBindingVO createBinding(String entity,String store,String actor,
            CreateMenuChannelBindingReq req,String key) {
        var v=invoke(() -> api.createBinding(
                new MenuChannelBindingInternalApi.CreateCommand(entity,store,actor,key,req)));
        if(!valid(v,store) || req==null || !req.menuNo().equals(v.menuNo()) || !req.provider().equals(v.provider())
                || !req.channelAccountRef().equals(v.channelAccountRef())
                || !req.externalStoreRef().equals(v.externalStoreRef()) || v.version()!=0
                || !"DRAFT".equals(v.status())) invalid(); return v;
    }
    @Override public MenuChannelBindingVO updateBinding(String entity,String store,String number,String actor,
            UpdateMenuChannelBindingReq req,String key) {
        var v=invoke(() -> api.updateBinding(
                new MenuChannelBindingInternalApi.UpdateCommand(entity,store,number,actor,key,req)));
        if(!valid(v,store) || !number.equals(v.bindingNo()) || req==null || req.version()==null
                || req.version()<0 || req.version()==Long.MAX_VALUE || v.version()!=req.version()+1
                || !req.status().equals(v.status()) || !req.externalStoreRef().equals(v.externalStoreRef())) invalid();
        return v;
    }
    @Override public MenuChannelBindingVO getBinding(String entity,String store,String number) {
        var v=invoke(() -> api.getBinding(new MenuChannelBindingInternalApi.GetQuery(entity,store,number)));
        if(!valid(v,store) || !number.equals(v.bindingNo())) invalid(); return v;
    }
    @Override public PageData<MenuChannelBindingVO> listBindings(String entity,String store,Integer page,Integer size,
            String provider,String menuNo) {
        var v=invoke(() -> api.listBindings(
                new MenuChannelBindingInternalApi.Lookup(entity,store,page,size,provider,menuNo)));
        int p=page==null?1:page; int s=size==null?20:size; var seen=new HashSet<String>();
        if(v==null || v.records()==null || v.page()!=p || v.size()!=s || v.total()<v.records().size()
                || v.records().size()>s || v.records().stream().anyMatch(b -> !valid(b,store)
                || !seen.add(b.bindingNo()) || provider!=null && !provider.equals(b.provider())
                || menuNo!=null && !menuNo.equals(b.menuNo()))) invalid(); return v;
    }
    private static boolean valid(MenuChannelBindingVO v,String store) {
        return v!=null && id(v.bindingNo(),32) && id(v.menuNo(),32) && store.equals(v.storeNo())
                && v.provider()!=null && v.provider().matches("[A-Z][A-Z0-9_]{1,31}")
                && id(v.channelAccountRef(),64) && id(v.externalStoreRef(),64) && v.version()!=null && v.version()>=0
                && v.status()!=null && Set.of("DRAFT","ACTIVE","DISABLED").contains(v.status());
    }
    private static boolean id(String v,int max) {
        return v!=null && !v.isBlank() && v.length()<=max && v.equals(v.trim());
    }
    private static void invalid() { throw new IllegalStateException("Invalid menu channel binding receipt"); }
    private <T> T invoke(Supplier<T> call) {
        try { return ServiceCalls.call(ServiceName.FOOD,call); }
        catch(ServiceCallException failure) {
            ErrorCode code=switch(failure.statusCode()) {
                case 400 -> ErrorCode.BAD_REQUEST;
                case 403 -> ErrorCode.FORBIDDEN;
                case 404 -> ErrorCode.NOT_FOUND;
                case 409 -> ErrorCode.CONFLICT;
                default -> null;
            };
            if(code!=null) throw BizException.of(code); throw failure;
        }
    }
}
