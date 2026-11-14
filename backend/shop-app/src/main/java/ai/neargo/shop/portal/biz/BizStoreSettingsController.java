package ai.neargo.shop.portal.biz;
import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.PermChecker;
import ai.neargo.shop.merchant.service.StoreSettingsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.ArrayList;
import java.util.List;

@RestController @Profile("api")
public class BizStoreSettingsController {
    private final StoreSettingsService service;
    private final PermChecker permissions;
    public BizStoreSettingsController(StoreSettingsService service,PermChecker permissions) { this.service=service;this.permissions=permissions; }
    public record View(ai.neargo.shop.merchant.dto.StoreProfileVO profile,java.util.Map<String,String> sectionVersions,List<String> allowedActions) {}
    private View view(StoreSettingsService.View v) {
        List<String> allowed=new ArrayList<>();
        if(permissions.canBiz(BizPerms.STORE_ADMIN)) allowed.add("EDIT_BASIC");
        if(permissions.canBiz(BizPerms.STORE)) allowed.add("EDIT_HOURS");
        return new View(v.profile(),v.sectionVersions(),List.copyOf(allowed));
    }
    @GetMapping("/biz/store/settings") @PreAuthorize("@perm.merchantAccount()")
    public View load() { return view(service.load(BizContext.requireMerchantNo(),BizContext.current().currentStoreNo())); }
    public record Basic(@NotBlank @Size(max=128) String expectedVersion,@NotBlank @Size(max=64) String name,
                        @NotBlank @Size(max=255) String address,@jakarta.validation.constraints.NotNull @Size(max=64) String addressDetail) {}
    public record Hours(@NotBlank @Size(max=128) String expectedVersion,@jakarta.validation.constraints.NotNull @Size(max=512) String openHours) {}
    @PostMapping("/biz/store/settings/basic") @PreAuthorize("@perm.canBiz('"+BizPerms.STORE_ADMIN+"')")
    public View basic(@Valid @RequestBody Basic req) {
        return view(service.basic(BizContext.requireMerchantNo(),BizContext.current().currentStoreNo(),req.expectedVersion(),req.name(),req.address(),req.addressDetail()));
    }
    @PostMapping("/biz/store/settings/hours") @PreAuthorize("@perm.canBiz('"+BizPerms.STORE+"')")
    public View hours(@Valid @RequestBody Hours req) {
        return view(service.hours(BizContext.requireMerchantNo(),BizContext.current().currentStoreNo(),req.expectedVersion(),req.openHours()));
    }
}
