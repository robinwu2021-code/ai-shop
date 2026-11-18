package ai.neargo.shop.portal.ops;

import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.portal.service.CustomerReadAccess;
import ai.neargo.shop.portal.service.CustomerReportingService;
import ai.neargo.shop.portal.service.CustomerReportingService.OperatingEntity;
import jakarta.validation.constraints.Size;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** STAFF-scoped source-authority read used by the independently deployed payment service. */
@Profile("ops")
@RestController
@Validated
@RequestMapping("/ops/customers")
public class OpsOperatingEntityController {
  private final CustomerReadAccess access;
  private final CustomerReportingService reporting;
  public OpsOperatingEntityController(CustomerReadAccess access, CustomerReportingService reporting) {this.access=access;this.reporting=reporting;}
  @GetMapping("/operating-entities/{id}")
  @PreAuthorize("@perm.can('" + Perms.MERCHANT_READ + "')")
  public OperatingEntity operatingEntity(@PathVariable @Size(min=1,max=64) String id) {
    return reporting.operatingEntity(access.require(Perms.MERCHANT_READ),id);
  }
}
