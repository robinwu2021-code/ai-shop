package ai.neargo.shop.promotion.service;

import ai.neargo.shop.numbering.CoreMarketingDocumentNumbers;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.numbering.DocumentType;
import ai.neargo.shop.promotion.entity.PmtCoupon;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponIssueMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.CouponScopeMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.UserCouponMapper;
import ai.neargo.shop.promotion.migration.LegacyCouponSourceFenceService;
import ai.neargo.shop.promotion.service.impl.PmtCouponServiceImpl;
import ai.neargo.shop.spi.entitlement.EntitlementOwnerRegistryPort.SourceSystem;
import ai.neargo.shop.spi.member.MemberQueryPort;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class CouponIssueTransactionBoundaryTest {
    @Test
    void audienceResolutionRunsWithoutLocksAndConcurrentFreezeRejectsBeforeAnyIssueWrite() {
        var manager = new DataSourceTransactionManager(new DriverManagerDataSource(
                "jdbc:h2:mem:coupon-boundary-" + UUID.randomUUID(), "sa", ""));
        var transactions = new TransactionTemplate(manager);
        var numbers = mock(CoreMarketingDocumentNumbers.class);
        when(numbers.transaction(anyMap(), any())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Number reservation must precede the business transaction");
            return transactions.execute(status -> ((Supplier<?>) call.getArgument(1)).get());
        });
        var coupons = mock(CouponMapper.class);
        var scopes = mock(CouponScopeMapper.class);
        var issued = mock(UserCouponMapper.class);
        var batches = mock(CouponIssueMapper.class);
        var members = mock(MemberQueryPort.class);
        var fence = mock(LegacyCouponSourceFenceService.class);
        var coupon = new PmtCoupon();
        coupon.setTenantNo("MAIN");
        coupon.setEntityNo("ENTITY");
        coupon.setCouponNo("COUPON");
        coupon.setStatus(PmtCoupon.ACTIVE);
        when(coupons.selectOne(any())).thenReturn(coupon);
        when(numbers.loadOne(eq(DocumentType.MARKETING_COUPON_TEMPLATE), eq("PMT"),
                eq("pmt_coupon"), eq(coupon), any(), any())).thenReturn(coupon);
        var frozen = new AtomicBoolean();
        doAnswer(call -> {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Every source fence must join a real business transaction");
            if (frozen.get()) throw BizException.of(ErrorCode.CONFLICT);
            return null;
        }).when(fence).requireWritable("MAIN", SourceSystem.PMT, "COUPON", false);
        when(members.resolveSegment(anyString(), anyString())).thenAnswer(call -> {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive(),
                    "Remote audience resolution must not run under a source lock");
            frozen.set(true);
            return new MemberQueryPort.SegmentAudience(1,
                    List.of(new MemberQueryPort.Audience("MEMBER", "USER")));
        });
        var service = new PmtCouponServiceImpl(coupons, scopes, issued, batches, members, fence);
        service.setDocumentNumbers(numbers);

        assertThrows(BizException.class, () -> service.issue("ENTITY", "COUPON", "ALL", "ACTOR"));

        verify(fence, times(2)).requireWritable("MAIN", SourceSystem.PMT, "COUPON", false);
        verifyNoInteractions(issued, batches);
    }
}
