package ai.neargo.shop.member.service.impl;

import ai.neargo.shop.member.service.MemberCodeResolveService;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.config.MemberCodeProperties;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.dto.MemberCodeViews.CustomerReference;
import ai.neargo.shop.member.dto.MemberCodeViews.MemberCodeView;
import ai.neargo.shop.member.entity.MbrIdentityTicket;
import ai.neargo.shop.member.mapper.MemberIdentityTicketMapper;
import ai.neargo.shop.spi.member.CustomerIdentityPort.ResolvedCustomer;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort;
import ai.neargo.shop.spi.user.UserScopeFactsPort.ActiveUser;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** No process-local ticket map and no login-token fallback; the member domain owns credential facts. */
@Service
public class MemberCodeResolveServiceImpl implements MemberCodeResolveService {
    private static final String CODE = "MEMBER_CODE";
    private static final String REFERENCE = "CUSTOMER_REFERENCE";
    private static final String SELECTION = "CUSTOMER_SELECTION";
    private static final SecureRandom RANDOM = new SecureRandom();
    private ai.neargo.shop.spi.user.PersonPort people;
    @org.springframework.beans.factory.annotation.Autowired
    public void setPeople(ai.neargo.shop.spi.user.PersonPort people){this.people=people;}
    private ai.neargo.shop.member.service.MemberScopeService scopeMembers;
    @org.springframework.beans.factory.annotation.Autowired
    public void setScopeMembers(ai.neargo.shop.member.service.MemberScopeService members){this.scopeMembers=members;}
    private final MemberIdentityTicketMapper tickets;
    private final UserScopeFactsPort users;
    private final MerchantScopeFactsPort merchants;
    private final MemberCodeProperties properties;

    public MemberCodeResolveServiceImpl(MemberIdentityTicketMapper tickets, UserScopeFactsPort users,
            MerchantScopeFactsPort merchants, MemberCodeProperties properties) {
        this.tickets = tickets;
        this.users = users;
        this.merchants = merchants;
        this.properties = properties;
    }

    @Override
    @Transactional
    public MemberCodeView issue(String tenantNo, String userNo) {
        requireEnabled();
        requireId(tenantNo, 32);
        requireId(userNo, 64);
        activeUser(tenantNo, userNo);
        String raw = token("mc1_");
        var row = ticket(tenantNo, userNo, CODE, raw, 60, userNo);
        persist(row);
        return new MemberCodeView(raw, row.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    @Override
    @Transactional
    public CustomerReference resolve(MemberCodeScope scope, String memberCode) {
        requireEnabled();
        requireMerchant(scope);
        var code = lookup(scope.tenantNo(), CODE, "mc1_", memberCode, ErrorCode.MEMBER_CODE_INVALID);
        var user = activeUser(scope.tenantNo(), code.getUserNo());
        String raw = token("cr1_");
        var ref = ticket(scope.tenantNo(), user.userNo(), REFERENCE, raw, 120, scope.actorNo());
        ref.setEntityNo(scope.entityNo());
        ref.setStoreNo(scope.storeNo());
        ref.setActorNo(scope.actorNo());
        ref.setSourceTicketHash(code.getTicketHash());
        persist(ref);
        return new CustomerReference(raw, mask(user.nickname()), ref.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    @Override @Transactional
    public CustomerReference selectCustomer(MemberCodeScope scope,String customer,String user){
        requireEnabled();requireMerchant(scope);requireId(customer,64);
        String name="顾客";
        if(user!=null)name=activeUser(scope.tenantNo(),user).nickname();
        else if(people==null||people.find(customer).isEmpty())throw BizException.of(ErrorCode.NOT_FOUND);
        String raw=token("cr1_");var ref=ticket(scope.tenantNo(),user,SELECTION,raw,120,scope.actorNo());
        ref.setCustomerNo(customer);ref.setEntityNo(scope.entityNo());ref.setStoreNo(scope.storeNo());ref.setActorNo(scope.actorNo());persist(ref);
        return new CustomerReference(raw,mask(name),ref.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    @Override @Transactional
    public CustomerReference selectScopedCustomer(MemberCodeScope scope,String memberNo){
        requireEnabled();requireMerchant(scope);var member=scopeMembers.require(scope.tenantNo(),scope.entityNo(),scope.storeNo(),memberNo);
        if(!Set.of("ACTIVE","LEAD").contains(member.status()))throw BizException.of(ErrorCode.CONFLICT);
        var person=scopeMembers.identity(scope.tenantNo(),member.customerNo());if(person.userNo()!=null)activeUser(scope.tenantNo(),person.userNo());var organization=scopeMembers.scope(scope.tenantNo(),scope.entityNo(),scope.storeNo());
        String raw=token("cr1_");var ref=ticket(scope.tenantNo(),person.userNo(),SELECTION,raw,120,scope.actorNo());
        ref.setCustomerNo(member.customerNo());ref.setEntityNo(scope.entityNo());ref.setStoreNo(scope.storeNo());ref.setActorNo(scope.actorNo());ref.setOperatorScopeType(organization.scopeType());ref.setBrandScopeRevision(organization.revision());ref.setScopeMemberNo(member.scopeMemberNo());persist(ref);
        return new CustomerReference(raw,"顾客",ref.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvedCustomer requireReference(MemberCodeScope scope, String customerRef) {
        requireMerchant(scope);
        var ref = lookup(scope.tenantNo(), REFERENCE, "cr1_", customerRef, ErrorCode.NOT_FOUND);
        if (!scope.entityNo().equals(ref.getEntityNo()) || !scope.storeNo().equals(ref.getStoreNo())
                || !scope.actorNo().equals(ref.getActorNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if(ref.getScopeMemberNo()!=null){
            var member=scopeMembers.require(scope.tenantNo(),scope.entityNo(),scope.storeNo(),ref.getScopeMemberNo());var organization=scopeMembers.scope(scope.tenantNo(),scope.entityNo(),scope.storeNo());
            if(!Set.of("ACTIVE","LEAD").contains(member.status())||!member.customerNo().equals(ref.getCustomerNo())||!organization.scopeType().equals(ref.getOperatorScopeType())||ref.getBrandScopeRevision()==null||organization.revision()!=ref.getBrandScopeRevision())throw BizException.of(ErrorCode.NOT_FOUND);
        }
        if(ref.getUserNo()!=null)activeUser(scope.tenantNo(),ref.getUserNo());
        String customer=ref.getCustomerNo()==null?ref.getUserNo():ref.getCustomerNo();
        String user=ref.getUserNo();
        if(user==null){
            var person=people==null?java.util.Optional.<ai.neargo.shop.spi.user.PersonPort.PersonView>empty():people.find(customer);
            if(person.isEmpty())throw BizException.of(ErrorCode.NOT_FOUND);
            user=person.get().userNo();if(user!=null)activeUser(scope.tenantNo(),user);
        }else if(people!=null){
            customer=people.findByUser(user).map(ai.neargo.shop.spi.user.PersonPort.PersonView::personNo).orElse(customer);
        }
        return new ResolvedCustomer(user,customer);
    }

    private MbrIdentityTicket lookup(String tenant, String purpose, String prefix, String raw, ErrorCode failure) {
        if (raw == null || !raw.matches(prefix + "[A-Za-z0-9_-]{43}")) {
            throw BizException.of(failure);
        }
        var row = DataScopeContext.executeWithoutScope(() -> tickets.selectOne(
                Wrappers.<MbrIdentityTicket>lambdaQuery().eq(MbrIdentityTicket::getTenantNo, tenant)
                        .in(MbrIdentityTicket::getPurpose, REFERENCE.equals(purpose)?java.util.List.of(REFERENCE,SELECTION):java.util.List.of(purpose)).eq(MbrIdentityTicket::getTicketHash, hash(raw))));
        if (row == null || !LocalDateTime.now(ZoneOffset.UTC).isBefore(row.getExpiresAt())) {
            throw BizException.of(failure);
        }
        return row;
    }

    private void requireMerchant(MemberCodeScope scope) {
        if (scope == null || !merchants.entityActive(scope.tenantNo(), scope.entityNo())
                || !merchants.storesOwned(scope.tenantNo(), scope.entityNo(), Set.of(scope.storeNo()))) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
    }

    private ActiveUser activeUser(String tenant, String user) {
        return users.findActive(tenant, user).orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
        }
    }

    private void persist(MbrIdentityTicket row) {
        DataScopeContext.executeWithoutScope(() -> tickets.insert(row));
    }

    private static MbrIdentityTicket ticket(String tenant, String user, String purpose, String raw,
            int ttl, String actor) {
        var now = LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        var row = new MbrIdentityTicket();
        row.setTenantNo(tenant);
        row.setUserNo(user);
        row.setPurpose(purpose);
        row.setTicketHash(hash(raw));
        row.setIssuedAt(now);
        row.setExpiresAt(now.plusSeconds(ttl));
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        row.setCreatedBy(actor);
        row.setUpdatedBy(actor);
        row.setVersion(0L);
        row.setDeleted(0);
        return row;
    }

    private static String token(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String mask(String name) {
        return name == null || name.isBlank() ? "顾客" : name.substring(0, name.offsetByCodePoints(0, 1)) + "**";
    }

    private static void requireId(String value, int max) {
        if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
    }
}
