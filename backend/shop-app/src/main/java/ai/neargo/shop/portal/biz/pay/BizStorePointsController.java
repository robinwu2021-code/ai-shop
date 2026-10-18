package ai.neargo.shop.portal.biz.pay;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.AccountView;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.EntryPage;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Read-only store ledger; the authenticated B context fixes the holder identity. */
@Profile("api")
@RestController
public class BizStorePointsController {
  private final PointsAdministrationService administration;
  private final PointsInspectionService inspection;

  public BizStorePointsController(
      PointsAdministrationService administration, PointsInspectionService inspection) {
    this.administration = administration;
    this.inspection = inspection;
  }

  @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
  @GetMapping("/biz/points/store-types")
  public List<PointType> types() {
    BizContext.requireStoreNo();
    return administration.types().stream()
        .filter(type -> type.status() != PointType.Status.DRAFT)
        .toList();
  }

  @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
  @GetMapping("/biz/points/store-account")
  public AccountView account(@RequestParam String typeCode) {
    return inspection.account(typeCode, HolderType.STORE, BizContext.requireStoreNo());
  }

  @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
  @GetMapping("/biz/points/store-entries")
  public EntryPage entries(
      @RequestParam String typeCode,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "20") int limit) {
    return inspection.entries(
        typeCode, HolderType.STORE, BizContext.requireStoreNo(), null, after, limit);
  }
}
