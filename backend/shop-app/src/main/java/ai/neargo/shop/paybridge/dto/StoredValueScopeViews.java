package ai.neargo.shop.paybridge.dto;
import ai.neargo.shop.spi.entitlement.ContractChecks;import java.util.List;import java.time.Instant;
public final class StoredValueScopeViews {
 private StoredValueScopeViews(){}
 public record Page(List<Candidate> items,String nextCursor,long identityConflictCount){public Page(List<Candidate> items,String nextCursor){this(items,nextCursor,0);}}
 public record Candidate(String memberNo,String displayName,String phoneMasked,String memberStatus,boolean canRecharge,String unavailableReason,StoredValueCustomerViews.Account account,String sourceStoreNo,String sourceStoreName,Instant lastRechargeAt){}
 public record Selection(StoredValueCustomerViews.Selection selection,String sourceStoreNo,String sourceStoreName){}
 public record Source(String type,String memberNo,String accountRef,String code){
  public Source{int count=(memberNo!=null?1:0)+(accountRef!=null?1:0)+(code!=null?1:0);ContractChecks.require(count==1&&("MEMBER".equals(type)&&memberNo!=null||"ACCOUNT".equals(type)&&accountRef!=null||"MEMBER_CODE".equals(type)&&code!=null));if(memberNo!=null)ContractChecks.id(memberNo);if(accountRef!=null)ContractChecks.require(!accountRef.isBlank()&&accountRef.length()<=512);if(code!=null)ContractChecks.require(code.matches("mc1_[A-Za-z0-9_-]{43}"));}
  @Override public String toString(){return "ScopeSource[type="+type+", value=REDACTED]";}
 }
 public record SelectRequest(Source source){public SelectRequest{ContractChecks.present(source);}}
 public record MemberCreate(String phone,String remark,List<String> tagNos){}
}
