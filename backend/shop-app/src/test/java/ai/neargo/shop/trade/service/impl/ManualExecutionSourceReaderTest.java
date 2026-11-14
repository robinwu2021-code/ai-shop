package ai.neargo.shop.trade.service.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.trade.service.ServiceExecutionService.ExecutionCoordinates;
import ai.neargo.shop.trade.entity.OrdCheckoutLine;
import ai.neargo.shop.trade.entity.OrdCheckoutOperation;
import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdManualServiceAdmission;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;

import ai.neargo.shop.trade.mapper.CheckoutMappers;
import ai.neargo.shop.trade.mapper.ManualAdmissionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.OrderItemMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Legacy JSON bytes are pinned independently of the extracted owner reader. Not yet executed. */
class ManualExecutionSourceReaderTest {
    private final ManualExecutionSourceReader reader = new ManualExecutionSourceReader(JsonMapper.builder().build(),
            mock(CheckoutMappers.QuoteMapper.class), mock(CheckoutMappers.ConfirmationMapper.class),
            mock(CheckoutMappers.LineMapper.class), mock(OrderItemMapper.class), mock(ManualAdmissionMapper.class));

    @Test
    void retainsLegacySourceFieldAndRecordPropertyOrder() {
        var op = operation();
        String expected = "[\"MANUAL_SOURCE_V1\",\"operation\",\"buyer\",\"order\",\"sub\",7,9,11,"
                + "[{\"sourceLineNo\":\"line-a\",\"id\":2,\"revision\":3}],"
                + "[{\"id\":4,\"revision\":5}],\"ADMITTED\",[]]";
        assertEquals(CheckoutDigests.hash(expected), hash(op, List.of(line("line-a", 2, 3))));
    }

    @Test
    void canonicalizesSourceRowsWithoutLosingIdentityOrVersion() {
        var op = operation();
        var first = line("line-a", 2, 3);
        var second = line("line-b", 6, 8);
        String original = hash(op, List.of(first, second));
        assertEquals(original, hash(op, List.of(second, first)));
        second.setVersion(9L);
        assertNotEquals(original, hash(op, List.of(first, second)));
    }

    @Test
    void guestCounterWrapsLegacyHashWithFrozenPartyAndPurchaseEvidence() {
        var op = operation();
        op.setBuyerNo(null);
        op.setCustomerKind("GUEST");
        op.setGuestNo("guest");
        op.setIdentityRevision(2L);
        op.setPurchaseAuthority("MERCHANT_RECORDED_IN_PERSON");
        op.setConfirmationNo("confirmation");
        op.setAuthorityNamespaceHash("a".repeat(64));
        String base = "[\"MANUAL_SOURCE_V1\",\"operation\",null,\"order\",\"sub\",7,9,11,"
                + "[{\"sourceLineNo\":\"line-a\",\"id\":2,\"revision\":3}],"
                + "[{\"id\":4,\"revision\":5}],\"ADMITTED\",[]]";
        String envelope = "[\"MANUAL_COUNTER_SOURCE_V1\",{\"kind\":\"GUEST\",\"userNo\":null,"
                + "\"guestNo\":\"guest\",\"identityRevision\":2},\"confirmation\",\"" + "a".repeat(64)
                + "\",\"" + CheckoutDigests.hash(base) + "\"]";
        assertEquals(CheckoutDigests.hash(envelope), hash(op, List.of(line("line-a", 2, 3))));
        String original = hash(op, List.of(line("line-a", 2, 3)));
        op.setConfirmationNo("other-confirmation");
        assertNotEquals(original, hash(op, List.of(line("line-a", 2, 3))));
    }

    @Test
    void rejectsMissingOwnerRootsRatherThanInferringSourceAvailability() {
        assertThrows(BizException.class, () -> reader.load(operation(), null, null));
    }

    @Test
    void lockedSnapshotCannotReadOrInferAUnitOutsideTheOwnersTransaction() {
        var scope = new ExecutionScope("MAIN", "entity", "store");
        var hash = "a".repeat(64);
        var binding = new ExecutionBinding(scope, "BEAUTY", "operation", "order", "sub", "line", 1,
                "work", 1, "task", 0, "binding", 1, "execution", 1, "submission", "fence", 1,
                1, 1, hash, "definition", hash, hash, hash);
        assertThrows(IllegalStateException.class, () -> reader.loadLocked(new ExecutionCoordinates(scope,
                "BEAUTY", "operation", "order", "sub"), binding));
    }

    private String hash(OrdCheckoutOperation op, List<OrdCheckoutLine> lines) {
        var parent = new OrdOrder();
        parent.setVersion(7L);
        var child = new OrdSubOrder();
        child.setVersion(9L);
        var declaration = new OrdManualServiceAdmission();
        declaration.setAdmissionRevision(11L);
        var item = new OrdItem();
        item.setId(4L);
        item.setVersion(5L);
        return reader.sourceHash(op, parent, child, declaration, lines, List.of(item), "ADMITTED", List.of());
    }

    private static OrdCheckoutOperation operation() {
        var op = new OrdCheckoutOperation();
        op.setOperationNo("operation");
        op.setBuyerNo("buyer");
        op.setCustomerKind("USER");
        op.setOrderNo("order");
        op.setSubOrderNo("sub");
        return op;
    }

    private static OrdCheckoutLine line(String source, long id, long version) {
        var line = new OrdCheckoutLine();
        line.setSourceLineNo(source);
        line.setId(id);
        line.setVersion(version);
        return line;
    }
}
