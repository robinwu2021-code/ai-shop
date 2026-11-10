package ai.neargo.shop.merchant.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.entity.MchBookingHold;
import ai.neargo.shop.merchant.entity.MchBookingHoldUnit;
import ai.neargo.shop.merchant.entity.MchBookingOwner;
import ai.neargo.shop.merchant.entity.MchResourceBucket;
import ai.neargo.shop.merchant.entity.MchResourceCommand;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingHoldMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingHoldUnitMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.BookingOwnerMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceBucketMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.ResourceCommandMapper;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort.Lookup;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.trade.ExecutionReference;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;

import jakarta.validation.ConstraintViolationException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

class ResourceBookingExecutionEvidencePortImplTest {
    private final BookingOwnerMapper owners = mock(BookingOwnerMapper.class);
    private final BookingHoldMapper holds = mock(BookingHoldMapper.class);
    private final BookingHoldUnitMapper units = mock(BookingHoldUnitMapper.class);
    private final ResourceBucketMapper buckets = mock(ResourceBucketMapper.class);
    private final ResourceCommandMapper commands = mock(ResourceCommandMapper.class);
    private final MchBookingHold hold = new MchBookingHold();
    private final MchBookingHoldUnit unit = new MchBookingHoldUnit();
    private ResourceBookingExecutionEvidencePortImpl port;
    private Lookup lookup;

    @BeforeEach
    void setup() {
        String h = "a".repeat(64);
        var at = Instant.parse("2026-11-10T00:00:00Z");
        String plan =
                ResourcePlanContract.encode(
                        List.of(
                                new ResourcePlanContract.Demand(
                                        "R",
                                        "ITEM:REQ:0",
                                        1,
                                        List.of(
                                                new ResourcePlanContract.Interval(
                                                        at, at.plusSeconds(300))))),
                        List.of(
                                new ResourcePlanContract.Qualification(
                                        "R", "ITEM:REQ:0", null, null, false)));
        String hash = ResourcePlanContract.hash(plan);
        var ref =
                new ExecutionReference(
                                "SERVICE_EXECUTION_REFERENCE_V1",
                                new ExecutionScope("MAIN", "E", "S"),
                                ExecutionReference.SourceKind.STORE_ORDER,
                                "MANUAL_EXECUTION_V1",
                                "EXEC",
                                "GRANT",
                                "UNIT",
                                "TASK",
                                "WORK",
                                1,
                                1,
                                h,
                                h,
                                h,
                                h,
                                h,
                                "FENCE",
                                1,
                                h,
                                "BTRC",
                                h,
                                "ACTION",
                                h,
                                h,
                                null)
                        .signed();
        var binding =
                new ResourceSourceAuthorityPort.Binding(
                        "MAIN",
                        "E",
                        "S",
                        "CONFIRM",
                        "BEAUTY_APPOINTMENT",
                        "APPOINTMENT",
                        42,
                        "CONFIRM",
                        h,
                        hash);
        lookup = new Lookup(ref, binding, "HOLD", 6, "ITEM", 42, h, plan, hash);
        var scope = new ResourceCommandScopeRow("MAIN", "E", "S");
        when(owners.selectScope("MAIN", "E", "S")).thenReturn(scope);
        var command = new MchResourceCommand();
        command.setTenantNo("MAIN");
        command.setEntityNo("E");
        command.setStoreNo("S");
        command.setCommandNo("CONFIRM");
        command.setOwnerNo("OWNER");
        command.setAction("CONFIRM");
        command.setSourceRevision(42L);
        command.setPayloadHash(h);
        command.setFrozenPayload(plan);
        command.setCommandKind("BOOKING_V1");
        command.setStatus("SUCCEEDED");
        when(commands.selectCommand(scope, "CONFIRM")).thenReturn(command);
        var owner = new MchBookingOwner();
        owner.setSourceType("BEAUTY_APPOINTMENT");
        owner.setSourceNo("APPOINTMENT");
        owner.setOwnerNo("OWNER");
        owner.setCurrentHoldNo("HOLD");
        owner.setSourceRevision(42L);
        when(owners.selectOwner(scope, "OWNER")).thenReturn(owner);
        hold.setStatus("CONFIRMED");
        hold.setHoldNo("HOLD");
        hold.setLeaseRevision(6L);
        hold.setPlanRevision(8L);
        hold.setCurrentPlanHash(hash);
        hold.setSourceRevision(42L);
        when(holds.selectOwnedHold(scope, "OWNER", "HOLD")).thenReturn(hold);
        unit.setUnitNo("NATIVE_UNIT");
        unit.setBucketNo("BUCKET");
        unit.setAllocationRevision(8L);
        unit.setQty(1);
        unit.setSourceLineRef("ITEM:REQ:0");
        unit.setDeleted(0);
        when(units.selectWholeOccupationOriginal(scope, "HOLD")).thenReturn(List.of(unit));
        var bucket = new MchResourceBucket();
        bucket.setResourceNo("R");
        bucket.setStartAt(at.atOffset(ZoneOffset.UTC).toLocalDateTime());
        bucket.setEndAt(at.plusSeconds(300).atOffset(ZoneOffset.UTC).toLocalDateTime());
        when(buckets.selectOccupationBucket(scope, "BUCKET")).thenReturn(bucket);
        var manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        port =
                new ResourceBookingExecutionEvidencePortImpl(
                        owners, holds, units, buckets, commands, manager);
    }

    @Test
    void actualOwnerAllocationIsReadRatherThanGuessedFromArrangementOrLease() {
        var result = port.queryOriginal(lookup);
        result.validate();
        assertThat(result.bookingOwnerNo()).isEqualTo("OWNER");
        assertThat(result.allocationRevision()).isEqualTo(8);
        assertThat(result.lookup().arrangementRevision()).isEqualTo(42);
        assertThat(result.lookup().leaseRevision()).isEqualTo(6);
        assertThat(result.allocations().getFirst().unitNo()).isEqualTo("NATIVE_UNIT");
    }

    @Test
    void mixedGenerationAndWrongHoldOrMissingWholePlanUnitAreRejected() {
        unit.setAllocationRevision(9L);
        assertThatThrownBy(() -> port.queryOriginal(lookup)).isInstanceOf(RuntimeException.class);
        unit.setAllocationRevision(8L);
        hold.setLeaseRevision(7L);
        assertThatThrownBy(() -> port.queryOriginal(lookup)).isInstanceOf(RuntimeException.class);
        hold.setLeaseRevision(6L);
        unit.setQty(2);
        assertThatThrownBy(() -> port.queryOriginal(lookup)).isInstanceOf(RuntimeException.class);
        unit.setQty(1);
        var wrong =
                new Lookup(
                        lookup.reference(),
                        lookup.binding(),
                        "OTHER_HOLD",
                        6,
                        "ITEM",
                        42,
                        lookup.arrangementHash(),
                        lookup.wholePlanJson(),
                        lookup.wholePlanHash());
        assertThatThrownBy(() -> port.queryOriginal(wrong)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void realCglibMethodValidationWiringStartsAndRejectsMalformedLookupBeforeReadingOwner() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    "methodValidationPostProcessor",
                    MethodValidationPostProcessor.class,
                    () -> {
                        var validator = new MethodValidationPostProcessor();
                        validator.setProxyTargetClass(true);
                        return validator;
                    });
            context.registerBean(BookingOwnerMapper.class, () -> owners);
            context.registerBean(BookingHoldMapper.class, () -> holds);
            context.registerBean(BookingHoldUnitMapper.class, () -> units);
            context.registerBean(ResourceBucketMapper.class, () -> buckets);
            context.registerBean(ResourceCommandMapper.class, () -> commands);
            context.registerBean(
                    "transactionManager",
                    PlatformTransactionManager.class,
                    () -> mock(PlatformTransactionManager.class));
            context.registerBean(ResourceBookingExecutionEvidencePortImpl.class);
            context.refresh();
            var actual = context.getBean(ResourceBookingExecutionEvidencePort.class);
            assertThat(AopUtils.isCglibProxy(actual)).isTrue();
            var invalid =
                    new Lookup(
                            null,
                            null,
                            "HOLD",
                            6,
                            "ITEM",
                            42,
                            lookup.arrangementHash(),
                            lookup.wholePlanJson(),
                            lookup.wholePlanHash());
            assertThatThrownBy(() -> actual.queryOriginal(invalid))
                    .isInstanceOf(ConstraintViolationException.class);
            verifyNoInteractions(owners, holds, units, buckets, commands);
        }
    }
}
