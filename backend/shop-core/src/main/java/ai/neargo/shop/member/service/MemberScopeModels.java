package ai.neargo.shop.member.service;
import java.util.List;
public final class MemberScopeModels {
 private MemberScopeModels(){}
 public record ScopeMember(String scopeMemberNo,String customerNo,String scopeType,String scopeNo,String firstStoreNo,String status){}
 public record Summary(String scopeMemberNo,String displayName,String phoneMasked,String status,String sourceStoreNo,String sourceStoreName,String remark){}
 public record Page(List<Summary> items,String nextCursor,long identityConflictCount){public Page(List<Summary> items,String nextCursor){this(items,nextCursor,0);}}
}
