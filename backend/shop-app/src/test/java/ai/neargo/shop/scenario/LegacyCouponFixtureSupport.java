package ai.neargo.shop.scenario;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.trade.service.OrderService;
import java.util.List;
import java.util.UUID;

/** Explicit already-migrated ownership fixture; it does not certify target import/cutover. */
final class LegacyCouponFixtureSupport {
    private LegacyCouponFixtureSupport() { }
    static String asset(org.springframework.jdbc.core.JdbcTemplate jdbc,tools.jackson.databind.ObjectMapper json,
            ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort identities,
            ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort owners,String user,OrderService.CreateOrderCommand cmd) {

        String id=UUID.randomUUID().toString(),asset="EI"+id;
        var origin=new ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceIdentity(
                ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem.PMT,
                ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceKind.INSTANCE,cmd.couponNo());
        when(identities.ownedOrigins(any(),eq(user),eq(cmd.couponNo()))).thenReturn(List.of(
                new ai.neargo.shop.spi.entitlement.LegacyCouponIdentityPort.Origin(origin,"T1","M0001","MERCHANT",
                        "COMMIT_DECIDED","B1","b".repeat(64),true,false,true)));
        when(owners.resolve(any(),eq(origin))).thenAnswer(invocation->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerRef(origin,asset,
                    ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.OwnerSystem.PAY,
                    ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.MigrationState.CUTOVER,"B1","b".repeat(64),0);
        });
        var from=java.time.Instant.now().minusSeconds(100);var until=java.time.Instant.now().plusSeconds(3600);
        var rule=new ai.neargo.shop.spi.entitlement.EntitlementDefinition("Old coupon","ORDER_COUPON","MERCHANT","CNY","CASH",100L,null,null,0L,null,
                "COUNT",2,null,new ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity("ABSOLUTE",from,until,null,"GRANTED","ROLLING_DAYS","Asia/Shanghai"),
                new ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope("ALL",List.of()),List.of("TARGETED"));
        jdbc.update("INSERT INTO ent_instance(instance_no,template_version_no,grant_no,source_unit_key,user_no,entity_no,kind,status,rule_snapshot,unit_index,total_units,remaining_units,reserved_units,asset_seq,effective_from,effective_until,tenant_no,created_at,updated_at,created_by,updated_by) "
                +"VALUES(?,?,?,?,?,'M0001','ORDER_COUPON','ACTIVE',?,1,2,2,0,1,?,?,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'T','T')",
                asset,"EV"+id,"G"+id,"K"+id,user,json.writeValueAsString(rule),java.time.LocalDateTime.ofInstant(from,java.time.ZoneOffset.UTC),java.time.LocalDateTime.ofInstant(until,java.time.ZoneOffset.UTC));
        jdbc.update("INSERT INTO ent_ledger(entry_no,instance_no,asset_seq,biz_type,biz_key,units_delta,reserved_delta,after_units,after_reserved,occurred_at,tenant_no,created_at,updated_at,created_by,updated_by) "
                +"VALUES(?,?,1,'GRANT',?,2,0,2,0,CURRENT_TIMESTAMP,'MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'T','T')","EL"+id,asset,"G"+id);
        return asset;
    }
}
