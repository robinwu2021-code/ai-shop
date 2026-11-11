package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.invbridge.port.InventoryStockPort;
import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.LocationService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.inventory.domain.entity.InvOutboundLine;
import ai.neargo.inventory.domain.entity.InvOutboundOrder;
import ai.neargo.inventory.domain.entity.InvRefundRestore;
import ai.neargo.inventory.domain.entity.InvLedger;
import ai.neargo.inventory.domain.entity.InvReservation;
import ai.neargo.inventory.domain.entity.InvReservationLine;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ReservationMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.ReservationLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundOrderMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.OutboundLineMapper;
import ai.neargo.inventory.domain.mapper.InventoryMappers.LedgerMapper;
import ai.neargo.inventory.domain.mapper.RefundRestoreMapper;
import ai.neargo.inventory.domain.mapper.ReturnReceiptMapper;
import ai.neargo.inventory.domain.policy.ExecutionStockRetentionGuard;
import ai.neargo.inventory.domain.service.InboundService;
import ai.neargo.inventory.domain.service.impl.OriginalReservationRestoreServiceImpl;
import ai.neargo.shop.trade.entity.OrdAfterSale;
import ai.neargo.shop.trade.mapper.TradeMappers.AfterSaleMapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.mockito.ArgumentCaptor;
import java.util.ArrayList;
import java.util.UUID;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一张父单跨两个商家时，镜像补记要按 SKU 的业主分组记。
 *
 * <p>锁号是父订单号，一次锁货覆盖这张单里所有商家的行。此前镜像拿<b>第一行</b>的业主
 * 记全部行：B 商家的货被记进 A 商家的账（A 的库位里根本没有这个条目，或者有同名的另一件货），
 * B 自己的账上那笔预留不存在 —— 双写对差时 A 多扣、B 少扣，两边各自看起来都像真的。
 * 直连那条路（{@code InventoryStockPort.lock}）早就按业主分组了，两条路必须一致。
 */
class MirrorMultiOwnerTest {

    private final ReservationService reservations = mock(ReservationService.class);
    private final InventoryAclService acl = mock(InventoryAclService.class);
    private final LocationService locations = mock(LocationService.class);
    private InventoryMirrorConsumer consumer;

    @BeforeEach
    void setUp() {
        when(acl.ownerOfSku("SA")).thenReturn("OWN_A");
        when(acl.ownerOfSku("SB")).thenReturn("OWN_B");
        when(acl.itemIdOfSku("SA")).thenReturn("IT_A");
        when(acl.itemIdOfSku("SB")).thenReturn("IT_B");
        when(acl.locationOfStore(anyString(), org.mockito.ArgumentMatchers.any())).thenAnswer(i -> "LOC_" + i.getArgument(0));
        when(locations.resolveStockLocation(anyString(), anyString())).thenAnswer(i -> i.getArgument(1));
        consumer = new InventoryMirrorConsumer(null, reservations, acl, locations, new ObjectMapper());
    }

    private void consume(String type, String payload) {
        SysOutbox event = new SysOutbox();
        event.setEventType(type);
        event.setPayload(payload);
        consumer.consume(event);
    }

    @Test
    @DisplayName("★★★ 两个商家的父单：预留按业主各记各的，B 的货不进 A 的账")
    void reserveGroupsByOwner() {
        consume(InvMirrorEvent.RESERVE, "{\"ref\":\"P1\",\"items\":["
                + "{\"skuNo\":\"SA\",\"qty\":2},{\"skuNo\":\"SB\",\"qty\":3}]}");

        verify(reservations).reserve(eq("OWN_A"), eq("P1"),
                eq(List.of(new ReservationService.Line("IT_A", "LOC_OWN_A", 2, null))), anyLong(), anyInt());
        verify(reservations).reserve(eq("OWN_B"), eq("P1"),
                eq(List.of(new ReservationService.Line("IT_B", "LOC_OWN_B", 3, null))), anyLong(), anyInt());
        verify(reservations, never()).reserve(eq("OWN_A"), eq("P1"),
                eq(List.of(new ReservationService.Line("IT_A", "LOC_OWN_A", 2, null),
                        new ReservationService.Line("IT_B", "LOC_OWN_A", 3, null))), anyLong(), anyInt());
    }

    @Test
    @DisplayName("★★ 镜像与直连恢复都使用跨业主冻结原单，不按当前SKU归属记账")
    void restoreGroupsByOwner() {
        var originalA = original("A", "SA", 2, 50L, 1L);
        var originalB = original("B", "SB", 8, 80L, 2L);
        var originals = List.of(originalA, originalB);
        var heads = mock(ReservationMapper.class);
        var lines = mock(ReservationLineMapper.class);
        var outbound = mock(OutboundOrderMapper.class);
        var posted = mock(OutboundLineMapper.class);
        var ledger = mock(LedgerMapper.class);
        var claims = mock(RefundRestoreMapper.class);
        var returns = mock(ReturnReceiptMapper.class);
        var retention = mock(ExecutionStockRetentionGuard.class);
        var inbound = mock(InboundService.class);
        var afterSales = mock(AfterSaleMapper.class);
        var savedClaims = new ArrayList<InvRefundRestore>();
        var manager = new DataSourceTransactionManager(new DriverManagerDataSource(
                "jdbc:h2:mem:mirror-original-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", ""));
        var tx = new TransactionTemplate(manager);
        for (var type : List.of(InvOutboundOrder.class, InvOutboundLine.class, InvRefundRestore.class,
                InvLedger.class, OrdAfterSale.class)) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), "mirror-original"), type);
        }
        when(heads.lockOriginal("P1")).thenReturn(originals.stream().map(FrozenOriginal::head).toList());
        originals.forEach(row -> when(lines.lockOriginal(row.head().getReservationId())).thenReturn(List.of(row.line())));
        when(outbound.selectOne(any())).thenAnswer(call -> originals.stream()
                .filter(row -> matches(call.getArgument(0), row.order().getOutboundNo()))
                .map(FrozenOriginal::order).findFirst().orElse(null));
        when(posted.selectList(any())).thenAnswer(call -> originals.stream()
                .filter(row -> matches(call.getArgument(0), row.order().getOutboundNo()))
                .map(FrozenOriginal::posted).toList());
        when(ledger.selectList(any())).thenReturn(List.of());
        when(claims.selectList(any())).thenAnswer(call -> savedClaims.stream()
                .filter(row -> matches(call.getArgument(0), row.getPurposeNo())).toList());
        when(claims.lockByOriginalLines(anyList())).thenAnswer(call -> List.copyOf(savedClaims));
        when(returns.lockByOriginalLines(anyList())).thenReturn(List.of());
        doAnswer(call -> { savedClaims.add(call.getArgument(0)); return 1; })
                .when(claims).insert(any(InvRefundRestore.class));
        doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return null;
        }).when(retention).requireUnretained(anyList());
        var sales = List.of("AS1", "AS2").stream().map(no -> {
            var row = new OrdAfterSale(); row.setAfterSaleNo(no); row.setOrderNo("P1"); return row;
        }).toList();
        when(afterSales.selectList(any())).thenAnswer(call -> sales.stream()
                .filter(row -> matches(call.getArgument(0), row.getAfterSaleNo())).toList());

        // Construct the real package-private original-source boundary through Spring, using mapper
        // doubles only for the saved reservation, posted outbound and immutable restore claims.
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("heads", heads); beans.registerSingleton("lines", lines);
        beans.registerSingleton("outbound", outbound); beans.registerSingleton("posted", posted);
        beans.registerSingleton("ledger", ledger); beans.registerSingleton("retention", retention);
        beans.registerSingleton("claims", claims); beans.registerSingleton("returns", returns);
        beans.registerSingleton("inbound", inbound);
        var source = new RootBeanDefinition();
        source.setBeanClassName("ai.neargo.inventory.domain.service.impl.OriginalReservationSource");
        source.setAutowireMode(RootBeanDefinition.AUTOWIRE_CONSTRUCTOR);
        beans.registerBeanDefinition("originalSource", source);
        var restoreBean = new RootBeanDefinition(OriginalReservationRestoreServiceImpl.class);
        restoreBean.setAutowireMode(RootBeanDefinition.AUTOWIRE_CONSTRUCTOR);
        beans.registerBeanDefinition("originalRestores", restoreBean);
        var restores = beans.getBean(OriginalReservationRestoreServiceImpl.class);
        var adapter = new OriginalInventoryRestoreAdapter(acl, restores);
        adapter.setOriginalBoundary(heads, manager);
        adapter.setLegacyOriginalLookup(afterSales);
        consumer.setOriginalRestores(adapter);
        var port = new InventoryStockPort(null, reservations, acl, locations);
        port.setOriginalRestores(adapter);
        when(acl.ownerOfSku(anyString())).thenReturn("CURRENT_WRONG_OWNER");
        when(acl.itemIdOfSku(anyString())).thenReturn("CURRENT_WRONG_ITEM");
        when(locations.resolveStockLocation(anyString(), anyString())).thenReturn("CURRENT_WRONG_LOCATION");

        tx.executeWithoutResult(status -> consume(InvMirrorEvent.RESTORE, "{\"ref\":\"AS1\",\"items\":["
                + "{\"skuNo\":\"SA\",\"qty\":1,\"storeNo\":null,\"fulfillmentRef\":null,\"lifecycleVersion\":1},"
                + "{\"skuNo\":\"SB\",\"qty\":4,\"storeNo\":null,\"fulfillmentRef\":null,\"lifecycleVersion\":1}]}"));
        tx.executeWithoutResult(status -> port.restore("AS2", List.of(new SkuQty("SA", 1), new SkuQty("SB", 4))));
        var postings = ArgumentCaptor.forClass(InboundService.Draft.class);
        verify(inbound, times(4)).postDirectly(postings.capture(), eq("REFUND"));
        assertThat(postings.getAllValues()).allSatisfy(draft -> assertThat(draft.sourceType()).isEqualTo("RETURN"));
        assertThat(postings.getAllValues()).extracting(draft -> List.of(
                draft.sourceRef(), draft.ownerId(), draft.locationId(), draft.lines())).containsExactlyInAnyOrder(
                List.of("AS1", "OWN_A", "FROZEN_LOC_A", List.of(new InboundService.Line("IT_A", 1, null, 50L))),
                List.of("AS1", "OWN_B", "FROZEN_LOC_B", List.of(new InboundService.Line("IT_B", 4, null, 80L))),
                List.of("AS2", "OWN_A", "FROZEN_LOC_A", List.of(new InboundService.Line("IT_A", 1, null, 50L))),
                List.of("AS2", "OWN_B", "FROZEN_LOC_B", List.of(new InboundService.Line("IT_B", 4, null, 80L))));
        verify(acl, never()).ownerOfSku(anyString());
        verify(acl, never()).itemIdOfSku(anyString());
        verifyNoInteractions(reservations);
    }

    private static boolean matches(Wrapper<?> wrapper, String value) {
        var query = (AbstractWrapper<?, ?, ?>) wrapper;
        query.getSqlSegment();
        return query.getParamNameValuePairs().containsValue(value);
    }

    private record FrozenOriginal(InvReservation head, InvReservationLine line,
            InvOutboundOrder order, InvOutboundLine posted) {}

    private static FrozenOriginal original(String suffix, String sku, int quantity, long cost, long id) {
        // These are immutable read-side facts returned by mapper doubles; the real inventory
        // original-source validator and restore service below still prove and consume them.
        var head = mock(InvReservation.class);
        when(head.getReservationId()).thenReturn("R_" + suffix);
        when(head.getOwnerId()).thenReturn("OWN_" + suffix);
        when(head.getExternalRef()).thenReturn("P1");
        when(head.getStatus()).thenReturn("COMMITTED");
        when(head.getLifecycleVersion()).thenReturn(1);
        when(head.getOutboundNo()).thenReturn("OUT_" + suffix);
        when(head.getSourceManifestHash()).thenReturn("a".repeat(64));
        var line = mock(InvReservationLine.class);
        when(line.getId()).thenReturn(id);
        when(line.getReservationId()).thenReturn("R_" + suffix);
        when(line.getOwnerId()).thenReturn("OWN_" + suffix);
        when(line.getItemId()).thenReturn("IT_" + suffix);
        when(line.getLocationId()).thenReturn("FROZEN_LOC_" + suffix);
        when(line.getSourceSkuNo()).thenReturn(sku);
        when(line.getQty()).thenReturn(quantity);
        var order = mock(InvOutboundOrder.class);
        when(order.getOutboundNo()).thenReturn("OUT_" + suffix);
        when(order.getOwnerId()).thenReturn("OWN_" + suffix);
        when(order.getLocationId()).thenReturn("FROZEN_LOC_" + suffix);
        when(order.getPurpose()).thenReturn("SALE");
        when(order.getStatus()).thenReturn("POSTED");
        when(order.getSourceRef()).thenReturn("P1");
        when(order.getReservationId()).thenReturn("R_" + suffix);
        var posted = mock(InvOutboundLine.class);
        when(posted.getOutboundNo()).thenReturn("OUT_" + suffix);
        when(posted.getOwnerId()).thenReturn("OWN_" + suffix);
        when(posted.getItemId()).thenReturn("IT_" + suffix);
        when(posted.getQty()).thenReturn(quantity);
        when(posted.getUnitCostMinor()).thenReturn(cost);
        return new FrozenOriginal(head, line, order, posted);
    }
}
