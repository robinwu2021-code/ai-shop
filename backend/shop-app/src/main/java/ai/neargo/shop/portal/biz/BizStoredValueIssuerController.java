package ai.neargo.shop.portal.biz;
import ai.neargo.shop.spi.entitlement.ContractChecks;


import ai.neargo.shop.merchant.brand.IssuerAuthorityService;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import ai.neargo.shop.payclient.StoredValueIssuerAppService;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Profile("api")
@RestController
@PreAuthorize("@managementAccess.authenticated()")
public class BizStoredValueIssuerController {
    private final ManagementAccess access;
    private final IssuerAuthorityService authority;
    private final StoredValueIssuerAppService issuers;
    public BizStoredValueIssuerController(ManagementAccess access,IssuerAuthorityService authority,StoredValueIssuerAppService issuers) {
        this.access=access;this.authority=authority;this.issuers=issuers;
    }
    public record ConfigureRequest(String managementNo,Config configuration) { public ConfigureRequest{ContractChecks.id(managementNo);ContractChecks.present(configuration);} }
    @GetMapping("/biz/management/brands/{brandNo}/stored-value/issuer")
    public Lookup brand(@PathVariable String brandNo,@RequestParam String managementNo) {
        return issuers.find(authority.readScope(access.actor(),managementNo,"BRAND",brandNo));
    }
    @PostMapping("/biz/management/brands/{brandNo}/stored-value/issuer")
    public View create(@PathVariable String brandNo,@RequestBody ConfigureRequest request,@RequestHeader("Idempotency-Key") String key) {
        return issuers.configure(authority.configureScope(access.actor(),request.managementNo(),"BRAND",brandNo,request.configuration()),request.configuration(),key);
    }

    @GetMapping("/biz/management/stores/{storeNo}/stored-value/issuer")
    public Lookup store(@PathVariable String storeNo,@RequestParam String managementNo){return issuers.find(authority.readScope(access.actor(),managementNo,"STORE",storeNo));}
    @PostMapping("/biz/management/stores/{storeNo}/stored-value/issuer")
    public View configureStore(@PathVariable String storeNo,@RequestBody ConfigureRequest request,@RequestHeader("Idempotency-Key")String key){
        var actor=access.actor();var scope=authority.configureScope(actor,request.managementNo(),"STORE",storeNo,request.configuration());
        if(issuers.find(scope).issuer()==null)issuers.prepareStore(authority.storePreparationScope(actor,storeNo));
        return issuers.configure(scope,request.configuration(),key);
    }
    public record ManagementRequest(String managementNo){public ManagementRequest{ContractChecks.id(managementNo);}}
    @PostMapping("/biz/management/stores/{storeNo}/stored-value/issuer/prepare")
    public View prepare(@PathVariable String storeNo,@RequestBody ManagementRequest request){
        String managementNo=request.managementNo();
        return issuers.prepareStore(authority.prepareSettings(access.actor(),managementNo,storeNo));
    }
    @PostMapping("/biz/management/stores/{storeNo}/stored-value/issuer/access-refresh")
    public AccessAck refresh(@PathVariable String storeNo,@RequestBody ManagementRequest request){
        String managementNo=request.managementNo();
        authority.readScope(access.actor(),managementNo,"STORE",storeNo);
        return issuers.refresh(authority.storePreparationScope(access.actor(),storeNo));
    }
    @GetMapping("/biz/management/stored-value/issuers/{issuerNo}")
    public Lookup issuer(@PathVariable String issuerNo,@RequestParam String managementNo){
        var actor=access.actor();var scope=issuers.locate(actor.tenantNo(),issuerNo);
        return issuers.find(authority.readScope(actor,managementNo,scope.scopeType(),scope.scopeNo()));
    }
    @PostMapping("/biz/management/stored-value/issuers/{issuerNo}/configuration-checks")
    public ConfigurationImpact check(@PathVariable String issuerNo,@RequestBody ConfigureRequest request){
        var actor=access.actor();var scope=issuers.locate(actor.tenantNo(),issuerNo);
        return issuers.check(authority.configureScope(actor,request.managementNo(),scope.scopeType(),scope.scopeNo(),request.configuration()),issuerNo,request.configuration());
    }
    @PostMapping("/biz/management/stored-value/issuers/{issuerNo}/configuration-versions")
    public View version(@PathVariable String issuerNo,@RequestBody ConfigureRequest request,@RequestHeader("Idempotency-Key")String key){
        var actor=access.actor();var scope=issuers.locate(actor.tenantNo(),issuerNo);
        return issuers.configure(authority.configureScope(actor,request.managementNo(),scope.scopeType(),scope.scopeNo(),request.configuration()),request.configuration(),key);
    }
    @GetMapping("/biz/management/stored-value/issuers/{issuerNo}/collection-routes")
    public java.util.List<Route> routes(@PathVariable String issuerNo,@RequestParam String managementNo,@RequestParam(required=false)String payChannel,@RequestParam(required=false)String payScene){
        var actor=access.actor();var scope=issuers.locate(actor.tenantNo(),issuerNo);
        var view=issuers.find(authority.readScope(actor,managementNo,scope.scopeType(),scope.scopeNo())).issuer();
        if(view==null)throw ai.neargo.shop.common.BizException.of(ai.neargo.shop.common.ErrorCode.NOT_FOUND);
        var check=new Config(view.name(),view.settlementEntityNo(),view.collectionMode(),view.clearingMode(),view.debitAllocationMode(),view.currency(),view.marketZone(),view.routes(),view.revision());
        authority.configureScope(actor,managementNo,scope.scopeType(),scope.scopeNo(),check);
        return view.routes().stream().filter(r->payChannel==null||payChannel.equals(r.channel())).filter(r->payScene==null||payScene.equals(r.scene())).toList();
    }

    @GetMapping("/biz/management/stored-value/settlement-entity-candidates")
    public java.util.List<java.util.Map<String,Object>> entities(@RequestParam String managementNo,@RequestParam String scopeType,@RequestParam String scopeNo){return authority.settlementCandidates(access.actor(),managementNo,scopeType,scopeNo);}
}
