package ai.neargo.inventory.domain.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.domain.service.Caller;
import ai.neargo.inventory.domain.service.StockProjectionService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.support.InvKeys;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LocationMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ItemMapper;
import ai.neargo.inventory.domain.entity.InvLocation;
import ai.neargo.inventory.domain.entity.InvItem;
import org.springframework.beans.factory.annotation.Value;
import ai.neargo.inventory.domain.config.ConditionalOnInventory;
import ai.neargo.inventory.domain.mapper.StockProjectionMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OwnerMapper;
import ai.neargo.inventory.domain.entity.InvOwner;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Scope;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Snapshot;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Receipt;
import ai.neargo.inventory.api.projection.StockProjectionContracts.Mapping;
import ai.neargo.inventory.api.projection.StockProjectionContracts.MappingResult;
import ai.neargo.inventory.api.projection.StockProjectionContracts.ProjectionView;
import ai.neargo.inventory.api.projection.StockProjectionContracts.ProjectionPage;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;

/** Optional incoming read-only mirrors. Never posts or reserves native inventory. */
@Service
@ConditionalOnInventory
@Transactional(transactionManager="invTransactionManager")
public class StockProjectionServiceImpl implements StockProjectionService {
    private final StockProjectionMapper rows;
    private final OwnerMapper owners;
    private final LocationMapper locations;
    private final ItemMapper items;
    private final LocationService stockLocations;
    private final ObjectMapper json;
    private final String sourceSystem;
    public StockProjectionServiceImpl(StockProjectionMapper rows,OwnerMapper owners,ObjectMapper json,
            LocationMapper locations,ItemMapper items,LocationService stockLocations,
            @Value("${inventory.acl.system-code:SHOP}") String sourceSystem) {
        this.rows=rows;this.owners=owners;this.json=json;this.locations=locations;
        this.items=items;this.stockLocations=stockLocations;this.sourceSystem=sourceSystem;
    }
    @Override public List<MappingResult> validateMappings(Caller caller,Scope scope,List<Mapping> mappings) {
        String location=lockOwner(caller,scope);
        if(mappings==null||mappings.size()>200)throw bad();
        var seen=new HashSet<String>();var results=new ArrayList<MappingResult>();
        String owner=caller.ownerId();
        for(var mapping:mappings) {
            if(mapping==null)throw bad();
            if(!seen.add(mapping.itemNo()))throw bad();
            String issue=mappingIssue(mapping,owner,location);
            results.add(new MappingResult(mapping.itemNo(),issue==null,issue));
        }
        return List.copyOf(results);
    }
    private String mappingIssue(Mapping mapping,String owner,String location) {
        boolean hasTarget=mapping.targetOwnerRef()!=null||mapping.targetLocationRef()!=null||mapping.targetItemRef()!=null;
        if(!hasTarget)return null; // Source-only mirrors do not claim a native identity.
        if(!Objects.equals(owner,mapping.targetOwnerRef()))return "TARGET_OWNER_MISMATCH";
        if(!Objects.equals(location,mapping.targetLocationRef()))return "TARGET_LOCATION_MISMATCH";
        if(mapping.targetItemRef()==null||mapping.targetItemRef().isBlank()||mapping.targetItemRef().length()>64)return "TARGET_ITEM_INVALID";
        var material=items.selectOne(Wrappers.<InvItem>lambdaQuery().eq(InvItem::getOwnerId,owner)
                .eq(InvItem::getItemId,mapping.targetItemRef()).eq(InvItem::getStatus,"ACTIVE"));
        if(material==null)return "TARGET_ITEM_NOT_FOUND";
        return mapping.baseUnit().equals(material.getBaseUom())?null:"TARGET_UNIT_MISMATCH";
    }
    @Override public Receipt activate(Caller caller,Scope s,long generation,String key){lockOwner(caller,s);if(generation<=0)throw bad();
        String hash=sha256("ACTIVATE:"+generation);var old=replay(s,key,hash);if(old!=null)return old;
        rows.ensureSession(s);var state=rows.session(s);if(generation<=state.generation())throw conflict();
        rows.setSession(s,generation,"ACTIVE");return save(s,generation,key,hash,"ACTIVE",null);}
    @Override public Receipt deactivate(Caller caller,Scope s,long generation,String key){lockOwner(caller,s);
        String hash=sha256("DEACTIVATE:"+generation);var old=replay(s,key,hash);if(old!=null)return old;
        var state=rows.session(s);if(state==null||state.generation()!=generation)throw conflict();
        rows.setSession(s,generation,"OFF");return save(s,generation,key,hash,"OFF",null);}
    @Override public Receipt apply(Caller caller,Scope s,long generation,String event,String hash,Snapshot p){String targetLocation=lockOwner(caller,s);validate(p);
        String actual=sha256(json.writeValueAsString(new Object[]{s,generation,p}));if(!actual.equals(hash))throw bad();
        var old=replay(s,event,hash);if(old!=null)return old;
        var state=rows.session(s);if(state==null||state.generation()!=generation||!"ACTIVE".equals(state.status()))throw conflict();
        // Explicit target identities require verified mapping; source-only mirrors cannot forge native identities.
        if(mappingIssue(new Mapping(p.itemNo(),p.baseUnit(),p.targetOwnerRef(),p.targetLocationRef(),p.targetItemRef()),
                caller.ownerId(),targetLocation)!=null)throw bad();
        var prior=rows.projection(s,p.itemNo());String result="APPLIED";
        if(prior!=null&&prior.generation()==generation&&prior.version()>p.sourceVersion())result="STALE";
        else if(prior!=null&&prior.generation()==generation&&prior.version()==p.sourceVersion()){
            if(!prior.hash().equals(hash))throw conflict();result="UNCHANGED";
        }else if(prior==null)rows.insert(s,generation,hash,p);else rows.update(s,generation,hash,p);
        return save(s,generation,event,hash,result,"STALE".equals(result)?prior.version():p.sourceVersion());}
    @Override public Receipt queryReceipt(Caller caller,Scope s,String event,String hash){lockOwner(caller,s);return replay(s,event,hash);}
    @Override public List<Snapshot> queryProjection(Caller caller,Scope s,List<String> items){lockOwner(caller,s);if(items==null||items.size()>200)throw bad();var session=rows.session(s);var result=new ArrayList<Snapshot>();for(var item:items){var p=rows.projection(s,item);if(p!=null&&session!=null&&p.generation()==session.generation())result.add(p.snapshot());}return List.copyOf(result);}
    private String lockOwner(Caller caller,Scope s) {
        var owner=owners.selectOne(Wrappers.<InvOwner>lambdaQuery()
                .eq(InvOwner::getOwnerId,caller.ownerId()).last("FOR UPDATE"));
        if(owner==null||!"ACTIVE".equals(owner.getStatus()))throw conflict();
        if(!sourceSystem.equals(caller.systemCode())||!caller.systemCode().equals(owner.getSystemCode())
                ||!s.entityNo().equals(owner.getExternalRef()))
            throw InventoryException.of(InventoryError.FORBIDDEN);
        if(!"SVC".equals(owner.getWriteAuthority()))throw InventoryException.of(InventoryError.NOT_WRITER);
        return location(caller.ownerId(),s.storeNo());
    }
    private String location(String owner,String store) {
        var row=locations.selectOne(Wrappers.<InvLocation>lambdaQuery()
                .eq(InvLocation::getOwnerId,owner).eq(InvLocation::getExternalRef,store));
        if(row==null)throw InventoryException.of(InventoryError.NOT_FOUND,"location");
        return stockLocations.resolveStockLocation(owner,row.getLocationId());
    }
    @Override public ProjectionPage list(Caller caller,Scope s,int page,int size) {
        lockOwner(caller,s);
        var session=rows.session(s);
        var data=rows.page(s,(long)(page-1)*size,size).stream()
                .map(item->view(s,session,rows.projection(s,item))).toList();
        return new ProjectionPage(data,rows.count(s),page,size);
    }
    @Override public ProjectionView get(Caller caller,Scope s,String item) {
        lockOwner(caller,s);var p=rows.projection(s,item);
        if(p==null)throw InventoryException.of(InventoryError.NOT_FOUND,"projection");
        return view(s,rows.session(s),p);
    }
    private ProjectionView view(Scope s,StockProjectionMapper.Session session,StockProjectionMapper.Projection p) {
        String status=session==null?"UNCONFIGURED":session.generation()!=p.generation()?"STALE":session.status();
        return new ProjectionView("FOOD_LOCAL",s.entityNo(),s.storeNo(),p.generation(),status,p.receivedAt(),p.snapshot());
    }
    private Receipt replay(Scope s,String key,String hash){var r=rows.receipt(s,key);if(r==null)return null;if(!r.hash().equals(hash))throw conflict();return json.readValue(r.json(),Receipt.class);}
    private Receipt save(Scope s,long generation,String key,String hash,String status,Long version){var r=new Receipt(InvKeys.next("IFR"),status,generation,version);rows.receipt(s,generation,key,hash,r.receiptNo(),version,json.writeValueAsString(r));return r;}
    private static void validate(Snapshot p){if(p==null)throw bad();if(p.sourceVersion()<0||p.sourceUpdatedAt()==null||!Set.of("ACTIVE","DISABLED").contains(p.status()==null?"":p.status()))throw bad();
        if("UNTRACKED".equals(p.trackingMode())){if(p.onHand()!=null||p.reservedQty()!=null)throw bad();}
        else if("TRACKED".equals(p.trackingMode())){quantity(p.onHand());quantity(p.reservedQty());if(p.reservedQty().compareTo(p.onHand())>0)throw bad();}else throw bad();}
    private static void quantity(BigDecimal q){if(q==null||q.signum()<0||q.stripTrailingZeros().scale()>6||q.compareTo(new BigDecimal("999999999999999999.999999"))>0)throw bad();}
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static InventoryException bad(){return InventoryException.of(InventoryError.BAD_REQUEST);}
    private static InventoryException conflict(){return InventoryException.of(InventoryError.CONFLICT);}
}
