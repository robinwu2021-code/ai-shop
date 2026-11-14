package ai.neargo.shop.commercebridge;

import static org.junit.jupiter.api.Assertions.*;
import ai.neargo.auth.store.TokenHash;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.commerce.api.common.StoreRef;
import ai.neargo.shop.auth.*;
import java.time.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class MallOperatorFinancialScopeTest {
    static final String TOKEN="otk_scope", PERMISSION="commerce:mall:order:read";
    LoginUser live; ShopMallAuthority authority; JdbcTemplate db;
    @BeforeEach void setup() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:mall_scope_"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        db=new JdbcTemplate(ds);
        db.execute("CREATE TABLE mch_store(tenant_no VARCHAR,store_no VARCHAR,entity_no VARCHAR,deleted INT)");
        db.update("INSERT INTO mch_store VALUES('MAIN','S1','E1',0),('MAIN','HISTORY','E1',1),('MAIN','S2','E2',0),('OTHER','FOREIGN','E1',0)");
        var cached=user(DataScopeSpec.ALL);
        var authentication=new UsernamePasswordAuthenticationToken(cached,null,List.of());authentication.setDetails(TOKEN);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        live=user(DataScopeSpec.of(ScopeDim.MERCHANT,Set.of("E1")));
        AccountSessionVerifier verifier=new AccountSessionVerifier() {
            public <T> Optional<T> withFresh(String bearer,Instant now,Function<AccountSessionVerification,T> read) {
                return Optional.of(read.apply(new AccountSessionVerification(live,TokenHash.of(bearer),now.plusSeconds(60),now)));
            }
        };
        authority=new ShopMallAuthority(verifier,verifier,BizIdentityResolver.NONE,
                new ShopCommerceStoreAdapter(ds,new DataSourceTransactionManager(ds)),Clock.systemUTC());
    }
    @AfterEach void close() { SecurityContextHolder.clearContext();db.execute("DROP ALL OBJECTS"); }
    LoginUser user(DataScopeSpec scope) { return new LoginUser(Realm.OPERATOR,ai.neargo.auth.store.SubjectKind.OPS,"OP","operator",List.of(),List.of(PERMISSION),"MAIN",scope); }
    boolean permits(String tenant,String store) {
        return authority.authorize(authority.operator(PERMISSION),new StoreRef(tenant,store),PERMISSION).allowed();
    }
    @Test void freshRestrictedScopeOverridesCachedAllAndRetainsTombstoneOwnership() {
        assertTrue(permits("MAIN","S1"));assertTrue(permits("MAIN","HISTORY"));
        assertFalse(permits("MAIN","S2"));assertFalse(permits("MAIN","FOREIGN"));
        assertFalse(permits("OTHER","FOREIGN"));assertFalse(permits("MAIN","MISSING"));
        live=user(DataScopeSpec.of(ScopeDim.MERCHANT,Set.of("E2")));
        assertFalse(permits("MAIN","S1"));assertTrue(permits("MAIN","S2"));
    }
    @Test void communityPickupEmptyAndNullScopesFailClosed() {
        for(var scope:List.of(DataScopeSpec.of(ScopeDim.COMMUNITY,Set.of("E1")),
                DataScopeSpec.of(ScopeDim.PICKUP,Set.of("E1")),DataScopeSpec.of(ScopeDim.MERCHANT,Set.of()),
                new DataScopeSpec(false,null))) {
            live=user(scope);assertFalse(permits("MAIN","S1"));assertFalse(permits("MAIN","_TENANT"));
        }
        live=user(null);assertFalse(permits("MAIN","S1"));assertFalse(permits("MAIN","_TENANT"));
    }
    @Test void tenantWideQueueRequiresExplicitAllAndPermissionsStaySeparate() {
        assertFalse(permits("MAIN","_TENANT"));live=user(DataScopeSpec.ALL);
        assertTrue(permits("MAIN","_TENANT"));assertTrue(permits("MAIN","HISTORY"));
        var actor=authority.operator(PERMISSION);
        assertFalse(authority.authorize(actor,new StoreRef("MAIN","S1"),"commerce:mall:offline:confirm").allowed());
    }
}
