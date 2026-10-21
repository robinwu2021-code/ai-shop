package ai.neargo.shop.merchant.brand;

import ai.neargo.shop.common.*;
import java.time.*;
import java.util.*;

/** Wire commands carry identifiers, never a client supplied authenticated context. */
public final class ManagementModels {
 private ManagementModels() {}
 public record Actor(String tenantNo,String actorKey) {
  public Actor { if(tenantNo==null||tenantNo.isBlank()||actorKey==null||!actorKey.matches("(USER|MCH):[A-Za-z0-9_-]{1,55}")) throw BizException.of(ErrorCode.UNAUTHORIZED); }
 }
 public record GrantCommand(String actorNo,String resourceType,String resourceNo,String permissionCode,
       Instant validFrom,Instant validTo,Boolean delegatable,Long expectedRevision,Boolean reactivate) {
  public GrantCommand {if(delegatable==null)delegatable=false;if(reactivate==null)reactivate=false;}
 }
 public record BrandCreate(String managementNo,String name,String logo,String status) {}
 public record BrandEdit(String name,String logo,long expectedRevision) {}
 public record StatusChange(String targetStatus,String reason,long expectedRevision) {}
 public record ChangeCheck(String managementNo,String toBrandNo) {}
 public record ChangeSubmit(String managementNo,String toBrandNo,String checkToken,long expectedStoreRevision,
       long expectedScopeRevision,Long expectedFromBrandRevision,Long expectedToBrandRevision,String reason) {}
 public record ChangeDecision(String decision,long expectedRevision,String reason) {}
 public record SpaceCreate(String name,String sourceAccountNo) {}
 public record Page(List<Map<String,Object>> items,String nextCursor) {}
 public static String text(String value,int max) {
  if(value==null||value.isBlank()||value.trim().length()>max)throw BizException.of(ErrorCode.BAD_REQUEST);
  return value.trim();
 }
}
