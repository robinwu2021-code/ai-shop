package ai.neargo.shop.portal.biz.pay;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.payclient.BizPointsStatementService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import ai.neargo.shop.pay.points.admin.PointsAdministrationService;
import ai.neargo.shop.pay.points.admin.PointsInspectionService;
import ai.neargo.shop.pay.points.admin.PointsInspectionService.AccountView;
import ai.neargo.shop.pay.points.model.HolderType;
import ai.neargo.shop.pay.points.model.PointType;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Store ledger and statement decisions scoped to the authenticated current store. */
@Profile("api")
@RestController
public class BizStorePointsController {
  private final PointsAdministrationService administration;
  private final PointsInspectionService inspection;
  private final BizPointsStatementService statements;

  public BizStorePointsController(
      PointsAdministrationService administration, PointsInspectionService inspection,
      BizPointsStatementService statements) {
    this.administration = administration;
    this.inspection = inspection;
    this.statements = statements;
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
  public StoreEntryPage entries(
      @RequestParam String typeCode,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "20") int limit) {
    if (typeCode.isBlank() || after < 0 || limit < 1 || limit > 50)
      throw new IllegalArgumentException("POINTS_ENTRIES_QUERY_INVALID");
    var page = inspection.entries(typeCode, HolderType.STORE, BizContext.requireStoreNo(), null, after, limit);
    var metadata = new ai.neargo.shop.payclient.PointsEntryMetadata(inspection, typeCode);
    return new StoreEntryPage(page.rows().stream().map(row -> new StoreEntry(
        row.id(), row.entryNo(), row.voucherNo(), row.voucherDocumentNo(), row.holderType(),
        row.holderNo(), row.bucket(), row.deltaPoints(), row.afterPoints(), row.at(),
        metadata.forVoucher(row.voucherNo()).direction(), metadata.forVoucher(row.voucherNo()).businessRef()
    )).toList(), page.next(), page.hasMore());
  }
  public record StoreEntry(long id, String entryNo, String voucherNo, String voucherDocumentNo,
      HolderType holderType, String holderNo, ai.neargo.shop.pay.points.model.Bucket bucket,
      long deltaPoints, long afterPoints, long at, String direction, String businessRef) {}
  public record StoreEntryPage(List<StoreEntry> rows, long next, boolean hasMore) {}

  @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
  @GetMapping("/biz/points/store-statements")
  public BizPointsStatementService.Page statements(@RequestParam String typeCode,
      @RequestParam(defaultValue = "0") long after,
      @RequestParam(defaultValue = "20") int limit) {
    return statements.statements(typeCode, after, limit);
  }

  @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
  @PostMapping("/biz/points/store-statement-confirm")
  public ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult confirm(
      @RequestBody BizPointsStatementService.Change request) {
    return statements.confirm(request, SecurityUtils.currentUserNo());
  }

  @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
  @PostMapping("/biz/points/store-statement-dispute")
  public ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult dispute(
      @RequestBody BizPointsStatementService.Change request) {
    return statements.dispute(request, SecurityUtils.currentUserNo());
  }

  @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
  @GetMapping("/biz/points/store-statement")
  public BizPointsStatementService.View statement(@RequestParam String typeCode,
      @RequestParam String statementNo) {
    return statements.statement(typeCode, statementNo);
  }

  @PreAuthorize("@perm.canBiz('" + BizPerms.FINANCE + "')")
  @GetMapping("/biz/points/store-statement-lines")
  public ai.neargo.shop.pay.points.admin.PointsWorkbenchService.StatementLinePage lines(
      @RequestParam String typeCode, @RequestParam String statementNo, @RequestParam long revision,
      @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "20") int limit) {
    return statements.lines(typeCode, statementNo, revision, after, limit);
  }

}
