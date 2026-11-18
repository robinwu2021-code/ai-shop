package ai.neargo.shop.portal.ops;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.auth.Perms;
import org.springframework.security.access.prepost.PreAuthorize;
import ai.neargo.shop.portal.service.CustomerReadAccess;
import ai.neargo.shop.portal.service.CustomerReportingService;
import ai.neargo.shop.portal.service.CustomerReportingService.*;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Profile("ops")
@RestController
@Validated
@RequestMapping("/ops/customers")
public class OpsCustomerController {
  private final CustomerReadAccess access;
  private final CustomerReportingService reporting;
  public OpsCustomerController(CustomerReadAccess access, CustomerReportingService reporting) {this.access=access;this.reporting=reporting;}
  @GetMapping("/users")
  @PreAuthorize("@perm.can('" + Perms.CUSTOMER_USER_READ + "')")
  public Registrations users(@RequestParam(required=false) @Size(max=64) String keyword,
      @RequestParam(defaultValue="1") @Min(1) long page,@RequestParam(defaultValue="20") @Min(1) @Max(100) long size) {
    return reporting.registrations(access.require(Perms.CUSTOMER_USER_READ),new Filter(keyword,null,null,page,size));
  }
  @GetMapping("/users/{userNo}/stores")
  @PreAuthorize("@perm.can('" + Perms.CUSTOMER_USER_READ + "')")
  public List<StoreLink> userStores(@PathVariable @Size(min=1,max=64) String userNo) {
    return reporting.registrationStores(access.require(Perms.CUSTOMER_USER_READ),userNo);
  }
  @GetMapping("/chains")
  @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
  public PageData<Brand> chains(@RequestParam(required=false) @Size(max=64) String keyword,
      @RequestParam(defaultValue="1") @Min(1) long page,@RequestParam(defaultValue="20") @Min(1) @Max(100) long size) {
    return reporting.brands(access.require(Perms.MERCHANT_READ),new Filter(keyword,null,null,page,size));
  }
  @GetMapping("/members")
  @PreAuthorize("@perm.can('" + Perms.MEMBER_MEMBER_READ + "')")
  public PageData<Person> members(@RequestParam(required=false) @Pattern(regexp="(?:[0-9]{4}|[A-Za-z][A-Za-z0-9_-]{2,63})") String keyword,
      @RequestParam(required=false) @Size(min=1,max=64) String storeNo,@RequestParam(required=false) @Size(min=1,max=64) String brandNo,
      @RequestParam(defaultValue="1") @Min(1) long page,@RequestParam(defaultValue="20") @Min(1) @Max(100) long size) {
    return reporting.members(access.require(Perms.MEMBER_MEMBER_READ),new Filter(keyword,storeNo,brandNo,page,size));
  }
  @GetMapping("/members/{personNo}")
  @PreAuthorize("@perm.can('" + Perms.MEMBER_MEMBER_READ + "')")
  public PersonDetail member(@PathVariable @Size(min=1,max=64) String personNo) {
    return reporting.member(access.require(Perms.MEMBER_MEMBER_READ),personNo);
  }
}
