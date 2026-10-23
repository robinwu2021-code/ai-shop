package ai.neargo.shop.food.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.food.dto.CreateMenuChannelBindingReq;
import ai.neargo.shop.food.dto.MenuChannelBindingVO;
import ai.neargo.shop.food.dto.UpdateMenuChannelBindingReq;
import ai.neargo.shop.food.entity.FnbActionLog;
import ai.neargo.shop.food.entity.FnbMenu;
import ai.neargo.shop.food.entity.FnbMenuChannelBinding;
import ai.neargo.shop.food.entity.FnbMenuStore;
import ai.neargo.shop.food.mapper.FoodMappers.ActionLogMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuChannelBindingMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuMapper;
import ai.neargo.shop.food.mapper.FoodMappers.MenuStoreMapper;
import ai.neargo.shop.food.policy.FoodCommandFingerprint;
import ai.neargo.shop.food.service.MenuChannelBindingService;
import ai.neargo.shop.food.spi.MenuChannelAuthorityPort;
import ai.neargo.shop.idem.IdempotencyService;
import ai.neargo.shop.spi.food.MenuChannelBindingChanged;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Serializes owner-menu invalidation locally; actual account authority is queried outside the local transaction. */
public class MenuChannelBindingServiceImpl implements MenuChannelBindingService {
    private final MenuChannelBindingMapper bindings;
    private final MenuMapper menus;
    private final MenuStoreMapper stores;
    private final ActionLogMapper actions;
    private final IdempotencyService idem;
    private final OutboxPublisher outbox;
    private final ObjectMapper json;
    private final Supplier<MenuChannelAuthorityPort> authority;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    public MenuChannelBindingServiceImpl(MenuChannelBindingMapper bindings,MenuMapper menus,MenuStoreMapper stores,
            ActionLogMapper actions,IdempotencyService idem,OutboxPublisher outbox,ObjectMapper json,
            PlatformTransactionManager manager,Supplier<MenuChannelAuthorityPort> authority) {
        this.bindings=bindings; this.menus=menus; this.stores=stores; this.actions=actions;
        this.idem=idem; this.outbox=outbox; this.json=json; this.authority=authority;
        write=new TransactionTemplate(manager); write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        read=new TransactionTemplate(manager); read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public MenuChannelBindingVO createBinding(String entity,String store,String actor,
            CreateMenuChannelBindingReq req,String key) {
        context(entity,store,actor,key);
        if(req==null) bad(); id(req.menuNo(),32); provider(req.provider());
        id(req.channelAccountRef(),64); id(req.externalStoreRef(),64);
        String scope=FoodCommandFingerprint.scope("CREATE_MENU_CHANNEL_BINDING",entity,store);
        String hash=FoodCommandFingerprint.hash("channel-binding:v1",json.writeValueAsString(req));
        return command(key,scope,actor,hash,() -> {
            var menu=owner(entity,store,req.menuNo(),true);
            var row=new FnbMenuChannelBinding(); row.setEntityNo(entity); row.setStoreNo(store);
            row.setBindingNo(BizKey.next("FMCB")); row.setMenuNo(req.menuNo()); row.setProvider(req.provider());
            row.setChannelAccountRef(req.channelAccountRef()); row.setExternalStoreRef(req.externalStoreRef());
            row.setStatus("DRAFT"); row.setVersion(0L); bindings.insert(row); invalidate(menu);
            var after=view(row); record(entity,store,actor,key,"CREATE_MENU_CHANNEL_BINDING",null,after); return after;
        });
    }
    @Override public MenuChannelBindingVO updateBinding(String entity,String store,String number,String actor,
            UpdateMenuChannelBindingReq req,String key) {
        context(entity,store,actor,key); id(number,32);
        if(req==null || req.version()==null || req.version()<0 || req.version()==Long.MAX_VALUE
                || req.status()==null || !Set.of("ACTIVE","DISABLED").contains(req.status())) bad();
        id(req.externalStoreRef(),64);
        String scope=FoodCommandFingerprint.scope("UPDATE_MENU_CHANNEL_BINDING",entity,store);
        String hash=FoodCommandFingerprint.hash("update-channel-binding:v1",number,json.writeValueAsString(req));
        var replay=completed(key,scope,actor,hash);
        if(replay.isPresent()) return replay.get();
        var captured=getBinding(entity,store,number);
        if("ACTIVE".equals(req.status())) {
            var port=authority.get();
            if(port==null || !port.authorized(entity,store,captured.provider(),captured.channelAccountRef(),
                    req.externalStoreRef())) conflict();
        }
        return command(key,scope,actor,hash,() -> {
            var menu=owner(entity,store,captured.menuNo(),true);
            var row=row(entity,store,number,true); var before=view(row);
            if(!captured.equals(before) || !req.version().equals(row.getVersion())) conflict();
            row.setExternalStoreRef(req.externalStoreRef()); row.setStatus(req.status());
            if(bindings.updateById(row)!=1) conflict(); invalidate(menu);
            var after=view(row);
            record(entity,store,actor,key,"UPDATE_MENU_CHANNEL_BINDING",before,after); return after;
        });
    }
    @Override public MenuChannelBindingVO getBinding(String entity,String store,String number) {
        queryContext(entity,store); id(number,32);
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> view(row(entity,store,number,false))));
    }
    @Override public PageData<MenuChannelBindingVO> listBindings(String entity,String store,Integer p,Integer s,
            String provider,String menuNo) {
        queryContext(entity,store); int page=p==null?1:p; int size=s==null?20:s;
        if(page<1 || size<1 || size>100) bad();
        if(provider!=null) provider(provider); if(menuNo!=null) id(menuNo,32);
        return DataScopeContext.executeWithoutScope(() -> read.execute(tx -> {
            var q=Wrappers.<FnbMenuChannelBinding>lambdaQuery().eq(FnbMenuChannelBinding::getEntityNo,entity)
                    .eq(FnbMenuChannelBinding::getStoreNo,store)
                    .eq(provider!=null,FnbMenuChannelBinding::getProvider,provider)
                    .eq(menuNo!=null,FnbMenuChannelBinding::getMenuNo,menuNo)
                    .orderByAsc(FnbMenuChannelBinding::getBindingNo);
            var result=bindings.selectPage(new Page<FnbMenuChannelBinding>(page,size),q);
            return PageData.of(result.getRecords().stream().map(MenuChannelBindingServiceImpl::view).toList(),
                    result.getTotal(),page,size);
        }));
    }
    private FnbMenu owner(String entity,String store,String menu,boolean lock) {
        var q=Wrappers.<FnbMenu>lambdaQuery().eq(FnbMenu::getEntityNo,entity).eq(FnbMenu::getMenuNo,menu);
        if(lock) q.last("FOR UPDATE"); var row=menus.selectOne(q);
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND);
        var associations=Wrappers.<FnbMenuStore>lambdaQuery().eq(FnbMenuStore::getEntityNo,entity)
                .eq(FnbMenuStore::getMenuNo,menu).orderByAsc(FnbMenuStore::getStoreNo);
        if(lock) associations.last("FOR UPDATE");
        var all=stores.selectList(associations);
        if(all.stream().noneMatch(binding -> store.equals(binding.getStoreNo()))) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if(all.size()!=1 || all.getFirst().getOverrideRevisionNo()!=null
                || !Set.of("DRAFT","PUBLISHED").contains(row.getStatus()==null?"":row.getStatus())
                || row.getVersion()==null || row.getVersion()<0 || row.getVersion()==Long.MAX_VALUE
                || row.getConfigVersion()==null || row.getConfigVersion()<0
                || row.getConfigVersion()==Long.MAX_VALUE) conflict();
        return row;
    }
    private void invalidate(FnbMenu menu) {
        menu.setConfigVersion(menu.getConfigVersion()+1); menu.setStatus("DRAFT");
        if(menus.updateById(menu)!=1) conflict();
    }
    private FnbMenuChannelBinding row(String entity,String store,String number,boolean lock) {
        var q=Wrappers.<FnbMenuChannelBinding>lambdaQuery().eq(FnbMenuChannelBinding::getEntityNo,entity)
                .eq(FnbMenuChannelBinding::getStoreNo,store).eq(FnbMenuChannelBinding::getBindingNo,number);
        if(lock) q.last("FOR UPDATE"); var row=bindings.selectOne(q);
        if(row==null) throw BizException.of(ErrorCode.NOT_FOUND); return row;
    }
    private MenuChannelBindingVO command(String key,String scope,String actor,String hash,
            Supplier<MenuChannelBindingVO> action) {
        var replay=completed(key,scope,actor,hash);
        if(replay.isPresent()) return replay.get();
        try { return DataScopeContext.executeWithoutScope(() -> write.execute(tx ->
                idem.execute(key,scope,actor,hash,MenuChannelBindingVO.class,action))); }
        catch(ConcurrencyFailureException | DuplicateKeyException failure) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
    }
    private Optional<MenuChannelBindingVO> completed(String key,String scope,String actor,String hash) {
        return read.execute(tx -> idem.findCompleted(key,scope,actor,hash,MenuChannelBindingVO.class));
    }
    private void record(String entity,String store,String actor,String key,String action,
            MenuChannelBindingVO before,MenuChannelBindingVO after) {
        var log = new FnbActionLog();
        log.setEntityNo(entity);
        log.setStoreNo(store);
        log.setActionNo(BizKey.next("FACT"));
        log.setObjectType("MENU_CHANNEL_BINDING"); log.setObjectNo(after.bindingNo());
        log.setAction(action); log.setOperatorNo(actor);
        log.setRequestNo(FoodCommandFingerprint.hash(FoodCommandFingerprint.scope(action,entity,store),key));
        if(before!=null) log.setBeforeRef(FoodCommandFingerprint.hash(json.writeValueAsString(before)));
        log.setAfterRef(FoodCommandFingerprint.hash(json.writeValueAsString(after))); actions.insert(log);
        outbox.publish(new MenuChannelBindingChanged(entity,store,after.bindingNo(),action,after.version(),
                log.getActionNo(),after),new PublishOptions("FOOD_MENU_CHANNEL_BINDING:"+after.bindingNo(),1));
    }
    private static MenuChannelBindingVO view(FnbMenuChannelBinding row) {
        return new MenuChannelBindingVO(row.getBindingNo(),row.getMenuNo(),row.getStoreNo(),row.getProvider(),
                row.getChannelAccountRef(),row.getExternalStoreRef(),row.getStatus(),row.getVersion());
    }
    private static void provider(String value) { if(value==null || !value.matches("[A-Z][A-Z0-9_]{1,31}")) bad(); }
    private static void id(String value,int max) {
        if(value==null || value.isBlank() || value.length()>max || !value.equals(value.trim())) bad();
    }
    private static void context(String entity,String store,String actor,String key) {
        queryContext(entity,store); id(actor,32); id(key,128);
    }
    private static void queryContext(String entity,String store) {
        id(entity,32); id(store,32);
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Channel binding operations cannot join an existing transaction");
    }
    private static void bad() { throw BizException.of(ErrorCode.BAD_REQUEST); }
    private static void conflict() { throw BizException.of(ErrorCode.CONFLICT); }
}
