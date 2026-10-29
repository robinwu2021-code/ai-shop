package ai.neargo.shop.trade.entitlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionPort;
import ai.neargo.shop.spi.entitlement.ExternalCertificateEvidencePort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.entitlement.entity.OrdExternalCertificateUse;
import ai.neargo.shop.trade.entitlement.port.ExternalCertificateConsumptionPortImpl;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.mapper.ExternalCertificateUseMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.ChannelImportMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.SubOrderMapper;
import ai.neargo.shop.trade.order.entity.OrdAfterSaleItem;
import ai.neargo.shop.trade.order.mapper.AfterSaleItemMapper;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class ExternalCertificateConsumptionPortTest {
    final ExternalCertificateUseMapper uses = mock(ExternalCertificateUseMapper.class);
    final SubOrderMapper subs = mock(SubOrderMapper.class);
    final OrderItemMapper items = mock(OrderItemMapper.class);
    final ChannelImportMapper channels = mock(ChannelImportMapper.class);
    final AfterSaleMapper afterSales = mock(AfterSaleMapper.class);
    final AfterSaleItemMapper returnLines = mock(AfterSaleItemMapper.class);
    final StoreDirectoryPort stores = mock(StoreDirectoryPort.class);
    final ExternalCertificateEvidencePort proof = mock(ExternalCertificateEvidencePort.class);
    final ObjectProvider<ExternalCertificateEvidencePort> providers = mock(ObjectProvider.class);
    final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    final List<OrdExternalCertificateUse> rows = new ArrayList<>();
    final ExternalCertificateConsumptionPort.Scope scope =
            new ExternalCertificateConsumptionPort.Scope("MAIN", "E", "S");
    final ExternalCertificateConsumptionPort.Business business =
            new ExternalCertificateConsumptionPort.Business("SUB", 11);
    final OrdSubOrder sub = new OrdSubOrder();
    final OrdItem item = new OrdItem();
    final OrdAfterSale physicalReturn = new OrdAfterSale();
    final OrdAfterSaleItem returnedLine = new OrdAfterSaleItem();
    ExternalCertificateConsumptionPortImpl service;

    void setup() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(providers.orderedStream()).thenAnswer(call -> Stream.of(proof));
        var card = mock(StoreDirectoryPort.StoreCard.class);
        when(card.active()).thenReturn(true);
        when(card.entityNo()).thenReturn("E");
        when(stores.cards(List.of("S"))).thenReturn(Map.of("S", card));
        sub.setSubOrderNo("SUB");
        sub.setOrderNo("ORDER");
        sub.setStatus(OrdSubOrder.COMPLETED);
        sub.setVersion(1L);
        sub.setCompletedAt(Instant.now().minusSeconds(2).toEpochMilli());
        when(subs.selectOne(any())).thenReturn(sub);
        item.setId(11L);
        item.setSkuNo("ACTUAL_SKU");
        item.setSaleQuantity(new BigDecimal("1.25"));
        item.setQuantityUnit("KG");
        item.setVersion(1L);
        item.setRefundedQty(0);
        item.setQty(2);
        when(items.selectOne(any())).thenReturn(item);
        when(proof.verified(any(), anyString(), anyString()))
                .thenAnswer(
                        call ->
                                new ExternalCertificateEvidencePort.Verified(
                                        scope,
                                        call.getArgument(1),
                                        call.getArgument(2),
                                        "a".repeat(64),
                                        "ACTUAL_SKU",
                                        null,
                                        new BigDecimal("10"),
                                        "KG",
                                        Instant.now().plusSeconds(29),
                                        "ORIGINAL_VERIFY"));
        doAnswer(
                        call -> {
                            OrdExternalCertificateUse row = call.getArgument(0);
                            row.setId((long) rows.size() + 1);
                            rows.add(row);
                            return 1;
                        })
                .when(uses)
                .insert(any(OrdExternalCertificateUse.class));
        when(uses.selectOne(any())).thenAnswer(call -> find(call.getArgument(0)));
        when(uses.selectList(any()))
                .thenAnswer(
                        call ->
                                rows.stream()
                                        .filter(
                                                row ->
                                                        "USE".equals(row.getPurpose())
                                                                && List.of(
                                                                                "RESERVED",
                                                                                "PENDING",
                                                                                "CONFIRMED")
                                                                        .contains(row.getStatus()))
                                        .toList());
        when(uses.selectCount(any()))
                .thenAnswer(
                        call ->
                                rows.stream()
                                        .filter(row -> "REVERSE".equals(row.getPurpose()))
                                        .count());
        when(uses.update(any(Wrapper.class)))
                .thenAnswer(
                        call -> {
                            Wrapper<?> wrapper = call.getArgument(0);
                            if (wrapper.getSqlSet() != null
                                    && wrapper.getSqlSet().contains("status")) {
                                var params =
                                        ((AbstractWrapper<?, ?, ?>) wrapper)
                                                .getParamNameValuePairs();
                                if (params.containsValue("REVERSED"))
                                    rows.stream()
                                            .filter(row -> "USE".equals(row.getPurpose()))
                                            .forEach(row -> row.setStatus("REVERSED"));
                            }
                            return 1;
                        });
        when(proof.decision(any(), anyString()))
                .thenAnswer(
                        call -> {
                            String reference = call.getArgument(1);
                            var row =
                                    rows.stream()
                                            .filter(
                                                    value ->
                                                            reference.equals(
                                                                    value.getAuthorizationNo()))
                                            .findFirst()
                                            .orElseThrow();
                            return new ExternalCertificateEvidencePort.Decision(
                                    scope,
                                    reference,
                                    row.getCertificateIdentityHash(),
                                    row.getSourceHash(),
                                    row.getQuantity(),
                                    row.getUnit(),
                                    "CONFIRMED",
                                    "ORIGINAL_ISSUER_RECEIPT");
                        });
        physicalReturn.setAfterSaleNo("RETURN");
        physicalReturn.setType(OrdAfterSale.RETURN_REFUND);
        physicalReturn.setStatus(OrdAfterSale.REFUNDED);
        physicalReturn.setReturnReceivedAt(Instant.now().minusSeconds(1).toEpochMilli());
        physicalReturn.setVersion(3L);
        when(afterSales.selectOne(any())).thenReturn(physicalReturn);
        returnedLine.setItemId(11L);
        returnedLine.setQty(2);
        returnedLine.setVersion(1L);
        when(returnLines.selectOne(any())).thenReturn(returnedLine);
        service =
                new ExternalCertificateConsumptionPortImpl(
                        uses,
                        afterSales,
                        returnLines,
                        subs,
                        items,
                        channels,
                        stores,
                        providers,
                        JsonMapper.builder().findAndAddModules().build(),
                        manager);
    }

    OrdExternalCertificateUse find(QueryWrapper<OrdExternalCertificateUse> query) {
        String sql = query.getSqlSegment();
        var params = query.getParamNameValuePairs().values();
        if (sql.contains("original_authorization_no"))
            return rows.stream()
                    .filter(
                            row ->
                                    row.getOriginalAuthorizationNo() != null
                                            && params.contains(row.getOriginalAuthorizationNo())
                                            && params.contains(row.getReversalRef()))
                    .findFirst()
                    .orElse(null);
        if (sql.contains("preview_ref"))
            return rows.stream()
                    .filter(row -> params.contains(row.getPreviewRef()))
                    .findFirst()
                    .orElse(null);
        return rows.stream()
                .filter(row -> params.contains(row.getAuthorizationNo()))
                .findFirst()
                .orElse(null);
    }

    @Test
    void
            actualCompletedOriginalQuantityIsReservedOnceWithoutCreatingAnyLocalEntitlementAssetOrUser() {
        setup();
        var auth = service.authorize(scope, business, "P1", "b".repeat(64));
        assertThat(auth.quantity()).isEqualByComparingTo("1.25");
        assertThat(auth.unit()).isEqualTo("KG");
        assertThat(auth.purpose()).isEqualTo("USE");
        assertThatThrownBy(() -> service.authorize(scope, business, "P2", "c".repeat(64)))
                .isInstanceOf(RuntimeException.class);
        assertThat(rows).hasSize(1);
        assertThat(service.freeze(scope, auth.authorizationRef()).status()).isEqualTo("PENDING");
        assertThat(service.reconcile(scope, auth.authorizationRef()).status())
                .isEqualTo("CONFIRMED");
    }

    @Test
    void uncompletedOrUnknownPhysicalBusinessFactsCannotBecomeExternalCertificateConsumption() {
        setup();
        sub.setStatus(OrdSubOrder.WAIT_FULFILL);
        assertThatThrownBy(() -> service.authorize(scope, business, "P1", "b".repeat(64)))
                .isInstanceOf(RuntimeException.class);
        sub.setStatus(OrdSubOrder.COMPLETED);
        item.setSaleQuantity(null);
        assertThatThrownBy(() -> service.authorize(scope, business, "P1", "b".repeat(64)))
                .isInstanceOf(RuntimeException.class);
        assertThat(rows).isEmpty();
    }

    @Test
    void aRefundWithoutActualMerchantReceiptOrAnIncompleteReturnCannotAuthorizeReversal() {
        setup();
        var auth = service.authorize(scope, business, "P1", "b".repeat(64));
        service.freeze(scope, auth.authorizationRef());
        service.reconcile(scope, auth.authorizationRef());
        physicalReturn.setReturnReceivedAt(null);
        var unavailable = service.reversalReadiness(scope, auth.authorizationRef(), "RETURN");
        assertThat(unavailable.status()).isEqualTo("UNSUPPORTED");
        assertThat(unavailable.authorization()).isNull();
        assertThatThrownBy(
                        () -> service.authorizeReversal(scope, auth.authorizationRef(), "RETURN"))
                .isInstanceOf(UnsupportedOperationException.class);
        physicalReturn.setReturnReceivedAt(Instant.now().minusSeconds(1).toEpochMilli());
        returnedLine.setQty(1);
        assertThatThrownBy(
                        () -> service.authorizeReversal(scope, auth.authorizationRef(), "RETURN"))
                .isInstanceOf(UnsupportedOperationException.class);
        returnedLine.setQty(2);
        var reverse = service.authorizeReversal(scope, auth.authorizationRef(), "RETURN");
        assertThat(reverse.purpose()).isEqualTo("REVERSE");
        assertThat(reverse.originalAuthorizationRef()).isEqualTo(auth.authorizationRef());
        assertThat(reverse.quantity()).isEqualByComparingTo(auth.quantity());
        service.freeze(scope, reverse.authorizationRef());
        assertThat(service.reconcile(scope, reverse.authorizationRef()).status())
                .isEqualTo("CONFIRMED");
        assertThat(rows.getFirst().getStatus()).isEqualTo("REVERSED");
    }
}
