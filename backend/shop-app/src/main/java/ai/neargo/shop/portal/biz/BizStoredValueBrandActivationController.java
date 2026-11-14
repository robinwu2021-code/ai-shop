package ai.neargo.shop.portal.biz;
import ai.neargo.shop.common.*;
import ai.neargo.shop.merchant.brand.*;
import ai.neargo.shop.merchantbridge.ManagementAccess;
import ai.neargo.shop.payclient.*;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.storedvalue.StoredValueBrandActivation.Capability;
import ai.neargo.shop.spi.storedvalue.StoredValueIssuers.Config;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@Profile("api") @RestController @PreAuthorize("@managementAccess.authenticated()")
public class BizStoredValueBrandActivationController {
    private final ManagementAccess access;private final IssuerAuthorityService authority;private final BrandFinancialActivationService journal;private final StoredValueIssuerAppService issuers;private final StoredValueBrandAppService pay;private final boolean enabled;private final boolean governance;
    public BizStoredValueBrandActivationController(ManagementAccess access,IssuerAuthorityService authority,BrandFinancialActivationService journal,StoredValueIssuerAppService issuers,StoredValueBrandAppService pay,@Value("${shop.pay.stored-value.brand-execution-enabled:false}")boolean enabled,@Value("${shop.pay.stored-value.governance-enabled:false}")boolean governance){this.access=access;this.authority=authority;this.journal=journal;this.issuers=issuers;this.pay=pay;this.enabled=enabled;this.governance=governance;}
    public record Activate(String managementNo,long expectedRevision){public Activate{ContractChecks.id(managementNo);ContractChecks.require(expectedRevision>=0);}}
    @GetMapping("/biz/stored-value/brand-capability")
    public Capability capability(){return enabled&&governance?pay.capability():new Capability(1,false,List.of("CORE_BRAND_EXECUTION_DISABLED"));}
    @PostMapping("/biz/management/brands/{brandNo}/stored-value/issuer/activation")
    public Map<String,Object> activate(@PathVariable String brandNo,@RequestBody Activate request,@RequestHeader("Idempotency-Key")String key){
        var actor=access.actor();var original=journal.original(actor,request.managementNo(),brandNo,key,request.expectedRevision());
        if(original!=null){if("ACTIVE".equals(original.state()))return Map.of("activationNo",original.activationNo(),"state","ACTIVE");return journal.acknowledge(actor,request.managementNo(),brandNo,pay.activate(original.command()));}
        if(!enabled||!governance)throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);var lookup=authority.configurationLookup(actor,request.managementNo(),"BRAND",brandNo);var current=issuers.find(lookup).issuer();if(current==null)throw BizException.of(ErrorCode.NOT_FOUND);if(current.revision()!=request.expectedRevision())throw BizException.of(ErrorCode.CONFLICT);
        if(!pay.capability().ready())throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        var config=new Config(current.name(),current.settlementEntityNo(),current.collectionMode(),current.clearingMode(),current.debitAllocationMode(),current.currency(),current.marketZone(),current.routes(),current.revision());var trusted=authority.configureScope(actor,request.managementNo(),"BRAND",brandNo,config);
        var pending=journal.prepare(actor,trusted,current,key);if("ACTIVE".equals(pending.state()))return Map.of("activationNo",pending.activationNo(),"state","ACTIVE");var ack=pay.activate(pending.command());return journal.acknowledge(actor,request.managementNo(),brandNo,ack);
    }
}
