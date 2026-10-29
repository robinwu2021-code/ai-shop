package ai.neargo.shop.inventory.service.impl;

import ai.neargo.shop.common.*;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.inventory.mapper.FoodStockProjectionMapper;
import ai.neargo.shop.inventory.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.shop.inventory.entity.InvOwner;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort;
import ai.neargo.shop.spi.food.FoodStockFingerprint;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.*;

/** Optional incoming read-only mirrors. Never posts or reserves native inventory. */
@Service
@ConditionalOnInventory
@Transactional(transactionManager="invTransactionManager")
public class FoodStockProjectionServiceImpl implements InventoryFoodStockProjectionPort {
    private final FoodStockProjectionMapper rows;private final OwnerMapper owners;private final ObjectMapper json;
    private final ai.neargo.shop.inventory.service.InventoryAclService acl;
    public FoodStockProjectionServiceImpl(FoodStockProjectionMapper rows,OwnerMapper owners,ObjectMapper json,ai.neargo.shop.inventory.service.InventoryAclService acl){this.rows=rows;this.owners=owners;this.json=json;this.acl=acl;}
    @Override public List<MappingResult> validateMappings(Scope scope,List<Mapping> mappings) {
        lockOwner(scope);
        if(mappings==null||mappings.size()>200)throw bad();
        var seen=new HashSet<String>();var results=new ArrayList<MappingResult>();
        String owner=acl.findOwnerId(scope.entityNo());String location=acl.stockLocationOfExistingStore(scope.entityNo(),scope.storeNo());
        for(var mapping:mappings) {
            if(mapping==null)throw bad();text(mapping.itemNo(),32);text(mapping.baseUnit(),16);
            if(!seen.add(mapping.itemNo()))throw bad();
            String issue=mappingIssue(mapping,owner,location,scope.entityNo());
            results.add(new MappingResult(mapping.itemNo(),issue==null,issue));
        }
        return List.copyOf(results);
    }
    private String mappingIssue(Mapping mapping,String owner,String location,String entity) {
        boolean hasTarget=mapping.targetOwnerRef()!=null||mapping.targetLocationRef()!=null||mapping.targetItemRef()!=null;
        if(!hasTarget)return null; // Source-only mirrors do not claim a native identity.
        if(!Objects.equals(owner,mapping.targetOwnerRef()))return "TARGET_OWNER_MISMATCH";
        if(!Objects.equals(location,mapping.targetLocationRef()))return "TARGET_LOCATION_MISMATCH";
        if(mapping.targetItemRef()==null||mapping.targetItemRef().isBlank()||mapping.targetItemRef().length()>64)return "TARGET_ITEM_INVALID";
        var materials=acl.findMaterials(entity,List.of(mapping.targetItemRef()));
        if(materials==null||materials.size()!=1||!mapping.targetItemRef().equals(materials.getFirst().itemId()))return "TARGET_ITEM_NOT_FOUND";
        return mapping.baseUnit().equals(materials.getFirst().baseUom())?null:"TARGET_UNIT_MISMATCH";
    }
    @Override public Receipt activate(Scope s,long generation,String key){lockOwner(s);text(key,64);if(generation<=0)throw bad();
        String hash=FoodStockFingerprint.sha256("ACTIVATE:"+generation);var old=replay(s,key,hash);if(old!=null)return old;
        rows.ensureSession(s);var state=rows.session(s);if(generation<=state.generation())throw conflict();
        rows.setSession(s,generation,"ACTIVE");return save(s,generation,key,hash,"ACTIVE",null);}
    @Override public Receipt deactivate(Scope s,long generation,String key){lockOwner(s);text(key,64);
        String hash=FoodStockFingerprint.sha256("DEACTIVATE:"+generation);var old=replay(s,key,hash);if(old!=null)return old;
        var state=rows.session(s);if(state==null||state.generation()!=generation)throw conflict();
        rows.setSession(s,generation,"OFF");return save(s,generation,key,hash,"OFF",null);}
    @Override public Receipt apply(Scope s,long generation,String event,String hash,Snapshot p){lockOwner(s);text(event,64);validate(p);
        String actual=FoodStockFingerprint.sha256(json.writeValueAsString(new Object[]{s,generation,p}));if(!actual.equals(hash))throw bad();
        var old=replay(s,event,hash);if(old!=null)return old;
        var state=rows.session(s);if(state==null||state.generation()!=generation||!"ACTIVE".equals(state.status()))throw conflict();
        // Explicit target identities require verified mapping; source-only mirrors cannot forge native identities.
        if(mappingIssue(new Mapping(p.itemNo(),p.baseUnit(),p.targetOwnerRef(),p.targetLocationRef(),p.targetItemRef()),
                acl.findOwnerId(s.entityNo()),acl.stockLocationOfExistingStore(s.entityNo(),s.storeNo()),s.entityNo())!=null)throw bad();
        var prior=rows.projection(s,p.itemNo());String result="APPLIED";
        if(prior!=null&&prior.generation()==generation&&prior.version()>p.sourceVersion())result="STALE";
        else if(prior!=null&&prior.generation()==generation&&prior.version()==p.sourceVersion()){
            if(!prior.hash().equals(hash))throw conflict();result="UNCHANGED";
        }else if(prior==null)rows.insert(s,generation,hash,p);else rows.update(s,generation,hash,p);
        return save(s,generation,event,hash,result,"STALE".equals(result)?prior.version():p.sourceVersion());}
    @Override public Receipt queryReceipt(Scope s,String event,String hash){lockOwner(s);text(event,64);text(hash,64);return replay(s,event,hash);}
    @Override public List<Snapshot> queryProjection(Scope s,List<String> items){lockOwner(s);if(items==null||items.size()>200)throw bad();var session=rows.session(s);var result=new ArrayList<Snapshot>();for(var item:items){text(item,32);var p=rows.projection(s,item);if(p!=null&&session!=null&&p.generation()==session.generation())result.add(p.snapshot());}return List.copyOf(result);}
    private void lockOwner(Scope s){if(s==null)throw bad();text(s.entityNo(),32);text(s.storeNo(),32);
        // Existing owner identity must be provisioned; synchronization never manufactures one.
        String ownerId=acl.findOwnerId(s.entityNo());
        if(ownerId==null || acl.stockLocationOfExistingStore(s.entityNo(),s.storeNo())==null)throw conflict();
        var owner=owners.selectOne(Wrappers.<InvOwner>lambdaQuery().eq(InvOwner::getOwnerId,ownerId).last("FOR UPDATE"));
        if(owner==null||!"ACTIVE".equals(owner.getStatus()))throw conflict();}
    private Receipt replay(Scope s,String key,String hash){var r=rows.receipt(s,key);if(r==null)return null;if(!r.hash().equals(hash))throw conflict();return json.readValue(r.json(),Receipt.class);}
    private Receipt save(Scope s,long generation,String key,String hash,String status,Long version){var r=new Receipt(BizKey.next("IFR"),status,generation,version);rows.receipt(s,generation,key,hash,r.receiptNo(),version,json.writeValueAsString(r));return r;}
    private static void validate(Snapshot p){if(p==null)throw bad();text(p.itemNo(),32);text(p.baseUnit(),16);if(p.sourceVersion()<0||p.sourceUpdatedAt()==null||!Set.of("ACTIVE","DISABLED").contains(p.status()==null?"":p.status()))throw bad();
        if("UNTRACKED".equals(p.trackingMode())){if(p.onHand()!=null||p.reservedQty()!=null)throw bad();}
        else if("TRACKED".equals(p.trackingMode())){quantity(p.onHand());quantity(p.reservedQty());if(p.reservedQty().compareTo(p.onHand())>0)throw bad();}else throw bad();}
    private static void quantity(BigDecimal q){if(q==null||q.signum()<0||q.stripTrailingZeros().scale()>6||q.compareTo(new BigDecimal("999999999999999999.999999"))>0)throw bad();}
    private static void text(String s,int max){if(s==null||s.isBlank()||s.length()>max||!s.equals(s.trim()))throw bad();}
    private static BizException bad(){return BizException.of(ErrorCode.BAD_REQUEST);}
    private static BizException conflict(){return BizException.of(ErrorCode.CONFLICT);}
}
