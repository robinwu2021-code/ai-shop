package ai.neargo.shop.portal.ops.pay;
import ai.neargo.shop.auth.Perms;
import ai.neargo.shop.spi.user.UserQueryPort;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.pay.points.admin.PointsAdjustmentService;
import ai.neargo.shop.pay.points.admin.PointsAdjustmentService.*;
import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import ai.neargo.shop.payclient.PointsOpsAccess;
import ai.neargo.shop.payclient.PointsOpsErrors;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@Profile("ops") @RestController
public class OpsPointsAdjustmentController {
 private final PointsAdjustmentService service;
 private final UserQueryPort users;
 public OpsPointsAdjustmentController(PointsAdjustmentService service,UserQueryPort users){this.service=service;this.users=users;}
 @PostMapping("/ops/points/adjustments")
 @PreAuthorize("@perm.can('"+Perms.FINANCE_SETTLE_EXECUTE+"')")
 public OperatorResult submit(@RequestBody Submission request){PointsOpsAccess.platform();return PointsOpsErrors.call(()->{if(request==null || request.userNo()==null || users.find(request.userNo()).isEmpty())throw new IllegalArgumentException("POINTS_ADJUSTMENT_USER_UNKNOWN");return service.submit(request,SecurityUtils.currentUserNo());});}
 @PostMapping("/ops/points/adjustment-review")
 @PreAuthorize("@perm.can('"+Perms.FINANCE_PAYOUT_EXECUTE+"')")
 public OperatorResult review(@RequestBody Review request){PointsOpsAccess.platform();return PointsOpsErrors.call(()->service.review(request,SecurityUtils.currentUserNo()));}
 @GetMapping("/ops/points/adjustments")
 @PreAuthorize("@perm.can('"+Perms.FINANCE_SETTLE_READ+"')")
 public Page list(@RequestParam String typeCode,@RequestParam(defaultValue="0") long after,@RequestParam(defaultValue="50") int limit){PointsOpsAccess.platform();return PointsOpsErrors.call(()->service.list(typeCode,after,limit));}
}
