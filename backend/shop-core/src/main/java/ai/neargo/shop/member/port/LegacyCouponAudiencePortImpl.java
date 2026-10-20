package ai.neargo.shop.member.port;
import ai.neargo.shop.common.*;
import ai.neargo.shop.spi.entitlement.*;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.Recipient;
import ai.neargo.shop.spi.member.MemberQueryPort;
import ai.neargo.shop.spi.user.*;
import org.springframework.stereotype.Component;
import java.util.List;
/** Source decision and member/account facts are owned by core. No pay call or recipient supplied by the public client. */
@Component
public class LegacyCouponAudiencePortImpl implements LegacyCouponAudiencePort {
    private final LegacyCouponSourceCommitPort decisions;
    private final LegacyCouponIdentityPort identities;
    private final MemberQueryPort members;
    private final MerchantScopeFactsPort merchants;
    private final UserScopeFactsPort users;
    public LegacyCouponAudiencePortImpl(LegacyCouponSourceCommitPort decisions,LegacyCouponIdentityPort identities,MemberQueryPort members,MerchantScopeFactsPort merchants,UserScopeFactsPort users) {
        this.decisions=decisions;this.identities=identities;this.members=members;this.merchants=merchants;this.users=users;
    }
    public Fact resolve(EntitlementContext ctx,Query q) {
        if(!"MAIN".equals(ctx.tenantNo()) || !merchants.entityActive(ctx.tenantNo(),q.entityNo())) throw BizException.of(ErrorCode.NOT_FOUND);
        var decision=decisions.decided(ctx,q.source());
        if(decision==null || decision.isEmpty() || !q.batchNo().equals(decision.get().migrationBatchNo())
                || !q.planHash().equals(decision.get().planHash()) || !q.entityNo().equals(identities.templateEntity(ctx,q.source().sourceSystem(),q.source().sourceNo()).orElse(null)))
            throw BizException.of(ErrorCode.CONFLICT);
        if(q.userKey()!=null) {
            if(!q.platformActor()) throw BizException.of(ErrorCode.FORBIDDEN);
            var user=users.findActive(ctx.tenantNo(),q.userKey()).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));
            return new Fact(ctx.tenantNo(),q,ctx.actorNo(),1,0,List.of(new Recipient(user.userNo(),true,null)));
        }
        if("CENTER".equals(q.mode())) {
            if(users.findActive(ctx.tenantNo(),ctx.actorNo()).isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
            return new Fact(ctx.tenantNo(),q,ctx.actorNo(),1,0,List.of(new Recipient(ctx.actorNo(),true,null)));
        }
        var selected=q.audiences().isEmpty()?members.resolveSegment(q.entityNo(),q.segmentNo()):null;
        var union=q.audiences().isEmpty()?null:members.resolve(q.entityNo(),q.audiences(),null);
        if(union!=null && !union.countable()) throw BizException.of(ErrorCode.BAD_REQUEST);
        int matched=selected!=null?selected.matched():union.matched();
        var reachable=selected!=null?selected.reachable():union.reachable();
        if(reachable.size()>1000 || reachable.stream().map(MemberQueryPort.Audience::userNo).distinct().count()!=reachable.size()) throw BizException.of(ErrorCode.BAD_REQUEST);
        var recipients=reachable.stream().map(a->users.findActive(ctx.tenantNo(),a.userNo()).isPresent()
                ?new Recipient(a.userNo(),true,null):new Recipient(a.userNo(),false,"UNREACHABLE")).toList();
        return new Fact(ctx.tenantNo(),q,ctx.actorNo(),matched,matched-recipients.size(),recipients);
    }
}
