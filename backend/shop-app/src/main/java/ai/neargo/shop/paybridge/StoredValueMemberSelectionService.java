package ai.neargo.shop.paybridge;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.member.service.MemberService;
import ai.neargo.shop.paybridge.dto.StoredValueCustomerViews.*;
import ai.neargo.shop.payclient.StoredValueMerchantQueryAppService;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.user.PersonPort;
import java.util.List;
import org.springframework.stereotype.Service;

/** Candidates come from Core membership, never from the Pay directory of already funded accounts. */
@Service
public class StoredValueMemberSelectionService {
    private final ai.neargo.shop.payclient.StoredValuePlanRechargeAppService direct;
    private final MemberService members;
    private final PersonPort people;
    private final MemberCodeResolveService codes;
    private final StoredValueMerchantQueryAppService accounts;
    public StoredValueMemberSelectionService(MemberService members,PersonPort people,MemberCodeResolveService codes,
                                             StoredValueMerchantQueryAppService accounts,ai.neargo.shop.payclient.StoredValuePlanRechargeAppService direct){
        this.direct=direct;this.members=members;this.people=people;this.codes=codes;this.accounts=accounts;
    }
    public PageData<Candidate> candidates(EntitlementContext ctx,String entity,String store,String phone){
        ContractChecks.require(phone!=null);phone=phone.strip();
        ContractChecks.require(phone.length()<=100&&(!phone.matches("[0-9]+")||phone.length()<=11)&&phone.length()>=(phone.matches("[0-9]+")?3:2));
        if(!phone.matches("1[3-9][0-9]{9}")){
            String query=phone;var suggestions=DataScopeContext.executeWithoutScope(()->members.suggest(entity,query));
            if(suggestions.isEmpty())return PageData.empty(1,20);
            var ids=suggestions.stream().flatMap(s->java.util.stream.Stream.of(s.person().personNo(),s.person().userNo())).filter(java.util.Objects::nonNull).distinct().toList();
            var funded=accounts.directory(new CatalogScope(ctx.tenantNo(),entity,ctx.actorNo(),false),store,ids,"member-suggest",null,100).records();
            var list=suggestions.stream().map(s->{
                boolean active=MbrMember.ACTIVE.equals(s.status())||MbrMember.LEAD.equals(s.status());
                var a=funded.stream().filter(x->s.person().personNo().equals(x.customerNo())||(s.person().userNo()!=null&&s.person().userNo().equals(x.userNo()))).findFirst();
                return new Candidate(s.memberNo(),name(s.remark()),s.person().phoneMasked(),s.status(),active,active?null:"MEMBER_BLOCKED",a.map(x->new Account(x.accountRef(),x.currency(),x.availableMinor())).orElse(null));
            }).toList();
            return PageData.of(list,list.size(),1,20);
        }
        var person=people.findByPhone(phone);
        if(person.isEmpty())return PageData.empty(1,20);
        var member=DataScopeContext.executeWithoutScope(()->members.find(entity,person.get().personNo()));
        if(member.isEmpty())return PageData.empty(1,20);
        var m=member.get();boolean active=MbrMember.ACTIVE.equals(m.getStatus())||MbrMember.LEAD.equals(m.getStatus());
        if(active&&person.get().userNo()!=null)direct.bindCustomer(ctx,person.get().personNo(),person.get().userNo());
        var account=active?account(ctx,entity,store,person.get().personNo()):null;
        String reason=MbrMember.BLOCKED.equals(m.getStatus())?"MEMBER_BLOCKED":active?null:"CUSTOMER_ACCOUNT_REQUIRED";
        return PageData.of(List.of(new Candidate(m.getMemberNo(),name(m.getRemark()),person.get().phoneMasked(),m.getStatus(),active,reason,account)),1,1,20);
    }
    public Selection select(EntitlementContext ctx,String entity,String store,Source source){
        boolean verifiedPerson=false;String user;String customer;String memberNo=null,display="顾客",phone="";
        if("MEMBER".equals(source.type())){
            var m=DataScopeContext.executeWithoutScope(()->members.detail(entity,source.memberNo()))
                    .orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND)).member();
            if(!MbrMember.ACTIVE.equals(m.status())&&!MbrMember.LEAD.equals(m.status()))throw BizException.of(ErrorCode.CONFLICT);
            var person=people.find(m.personNo()).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));
            verifiedPerson=true;user=person.userNo();customer=person.personNo();memberNo=m.memberNo();display=name(m.remark());phone=person.phoneMasked();
        }else{
            var a=accounts.account(new CatalogScope(ctx.tenantNo(),entity,ctx.actorNo(),false),store,source.accountRef());
            user=a.userNo();customer=a.customerNo()==null?user:a.customerNo();var person=user==null?people.find(customer):people.findByUser(user);
            if(person.isPresent()){verifiedPerson=true;phone=person.get().phoneMasked();customer=person.get().personNo();}
        }
        if(user!=null&&verifiedPerson)direct.bindCustomer(ctx,customer,user);
        var ref=codes.selectCustomer(new MemberCodeScope(ctx.tenantNo(),entity,store,ctx.actorNo()),customer,user);
        return new Selection(ref.customerRef(),ref.expiresAt(),memberNo,display,phone,account(ctx,entity,store,customer),ai.neargo.shop.promotion.service.ActivityCommandDeduplicator.hash(java.util.stream.Stream.of(ctx.tenantNo(),entity,store,ctx.actorNo(),customer).map(x->x.length()+":"+x).collect(java.util.stream.Collectors.joining())));
    }
    private Account account(EntitlementContext ctx,String entity,String store,String user){
        var page=accounts.directory(new CatalogScope(ctx.tenantNo(),entity,ctx.actorNo(),false),store,List.of(user),"member-selection",null,1);
        if(page.records().isEmpty())return null;
        var a=page.records().getFirst();return new Account(a.accountRef(),a.currency(),a.availableMinor());
    }
    private static String name(String remark){return remark==null||remark.isBlank()?"顾客":remark.strip();}
}
