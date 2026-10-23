package ai.neargo.shop.paybridge;
import ai.neargo.shop.member.service.*;import ai.neargo.shop.member.dto.MemberCodeScope;import ai.neargo.shop.payclient.*;import ai.neargo.shop.paybridge.dto.StoredValueScopeViews.*;import ai.neargo.shop.paybridge.dto.StoredValueCustomerViews;import ai.neargo.shop.pay.entitlement.dto.CatalogScope;import ai.neargo.shop.spi.entitlement.EntitlementContext;import ai.neargo.shop.common.*;import ai.neargo.shop.spi.user.StoredValueCustomerProfilePort;import org.springframework.stereotype.Service;import java.util.*;
/** Brand membership is operational scope; every Pay read retains the executing store/entity. No wallet creation. */
@Service
public class ScopedStoredValueMemberService {
 private final MemberScopeService members;private final MemberScopeQueryService query;private final MemberCodeResolveService codes;private final StoredValueMerchantQueryAppService accounts;private final StoredValuePlanRechargeAppService direct;private final StoredValueMemberSelectionService legacy;
 private StoredValueCustomerProfilePort profiles;
 @org.springframework.beans.factory.annotation.Autowired public void setProfiles(StoredValueCustomerProfilePort profiles){this.profiles=profiles;}
 public ScopedStoredValueMemberService(MemberScopeService members,MemberScopeQueryService query,MemberCodeResolveService codes,StoredValueMerchantQueryAppService accounts,StoredValuePlanRechargeAppService direct,StoredValueMemberSelectionService legacy){this.members=members;this.query=query;this.codes=codes;this.accounts=accounts;this.direct=direct;this.legacy=legacy;}
 public Page candidates(EntitlementContext ctx,String entity,String store,String keyword,String cursor,int size,boolean readAccount){
  if(size<1||size>100)throw BizException.of(ErrorCode.BAD_REQUEST);
  var financial=new CatalogScope(ctx.tenantNo(),entity,ctx.actorNo(),false);
  if(keyword==null||keyword.isBlank()){
   members.scope(ctx.tenantNo(),entity,store);if(!readAccount)return new Page(List.of(),null);
   var page=accounts.directory(financial,store,null,"scoped-funded",cursor,size);var ids=page.records().stream().map(a->a.identityNo()).toList();var names=profiles==null?Map.<String,StoredValueCustomerProfilePort.Profile>of():profiles.profiles(ctx.tenantNo(),entity,store,ids);
   return new Page(page.records().stream().map(a->{var p=names.get(a.identityNo());return new Candidate(null,p==null?"顾客":p.maskedName(),p==null?"":p.maskedPhone(),"ACTIVE",true,null,new StoredValueCustomerViews.Account(a.accountRef(),a.currency(),a.availableMinor()),null,null,a.lastRechargeAt());}).toList(),page.nextCursor());
  }
  var page=query.search(ctx.tenantNo(),entity,store,ctx.actorNo(),keyword,cursor,size);
  var ids=new ArrayList<String>();if(readAccount)for(var m:page.items()){var member=members.require(ctx.tenantNo(),entity,store,m.scopeMemberNo());var person=members.identity(ctx.tenantNo(),member.customerNo());ids.add(person.customerNo());if(person.userNo()!=null)ids.add(person.userNo());}
  var funded=ids.isEmpty()?List.<ai.neargo.shop.spi.storedvalue.StoredValueMerchantViews.Account>of():accounts.directory(financial,store,ids.stream().distinct().toList(),"scoped-member-search",null,100).records();
  return new Page(page.items().stream().map(m->{StoredValueCustomerViews.Account account=null;if(readAccount){var person=members.identity(ctx.tenantNo(),members.require(ctx.tenantNo(),entity,store,m.scopeMemberNo()).customerNo());account=funded.stream().filter(a->person.customerNo().equals(a.customerNo())||person.userNo()!=null&&person.userNo().equals(a.userNo())).findFirst().map(a->new StoredValueCustomerViews.Account(a.accountRef(),a.currency(),a.availableMinor())).orElse(null);}boolean active=Set.of("ACTIVE","LEAD").contains(m.status());return new Candidate(m.scopeMemberNo(),m.displayName(),m.phoneMasked(),m.status(),active,active?null:"MEMBER_BLOCKED",account,m.sourceStoreNo(),m.sourceStoreName(),null);}).toList(),page.nextCursor(),page.identityConflictCount());
 }
 public Selection select(EntitlementContext ctx,String entity,String store,Source source,boolean readAccount){
  if("ACCOUNT".equals(source.type())){if(!readAccount)throw BizException.of(ErrorCode.FORBIDDEN);return new Selection(legacy.select(ctx,entity,store,new StoredValueCustomerViews.Source("ACCOUNT",null,source.accountRef())),null,null);}
  String memberNo=source.memberNo();var codeScope=new MemberCodeScope(ctx.tenantNo(),entity,store,ctx.actorNo());
  if("MEMBER_CODE".equals(source.type())){var reference=codes.resolve(codeScope,source.code());var person=codes.requireReference(codeScope,reference.customerRef());members.importLegacy(ctx.tenantNo(),entity,store,ctx.actorNo());memberNo=members.byCustomer(ctx.tenantNo(),entity,store,person.customerNo()).scopeMemberNo();}
  var member=members.require(ctx.tenantNo(),entity,store,memberNo);if(!Set.of("ACTIVE","LEAD").contains(member.status()))throw BizException.of(ErrorCode.CONFLICT);
  var person=members.identity(ctx.tenantNo(),member.customerNo());var summary=query.detail(ctx.tenantNo(),entity,store,memberNo);
  if(person.userNo()!=null)direct.bindCustomer(ctx,person.customerNo(),person.userNo());
  var reference=codes.selectScopedCustomer(codeScope,memberNo);StoredValueCustomerViews.Account account=null;
  if(readAccount){var ids=person.userNo()==null?List.of(person.customerNo()):List.of(person.customerNo(),person.userNo());var page=accounts.directory(new CatalogScope(ctx.tenantNo(),entity,ctx.actorNo(),false),store,ids,"scoped-member-selection",null,1);if(!page.records().isEmpty()){var a=page.records().getFirst();account=new StoredValueCustomerViews.Account(a.accountRef(),a.currency(),a.availableMinor());}}
  var selected=new StoredValueCustomerViews.Selection(reference.customerRef(),reference.expiresAt(),memberNo,summary.displayName(),summary.phoneMasked(),account,ai.neargo.shop.promotion.service.ActivityCommandDeduplicator.hash(dbKey(ctx,entity,store,member.customerNo())));
  return new Selection(selected,summary.sourceStoreNo(),summary.sourceStoreName());
 }
 private String dbKey(EntitlementContext ctx,String entity,String store,String customer){return java.util.stream.Stream.of(ctx.tenantNo(),entity,store,ctx.actorNo(),customer).map(s->s.length()+":"+s).collect(java.util.stream.Collectors.joining());}
}
