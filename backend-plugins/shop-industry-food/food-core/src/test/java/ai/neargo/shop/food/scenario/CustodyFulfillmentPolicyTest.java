package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.KitchenContracts.CoreLineScope;
import ai.neargo.shop.food.dto.KitchenContracts.CustodyReceiptVO;
import ai.neargo.shop.food.dto.KitchenContracts.CustodySourceLine;
import ai.neargo.shop.food.dto.KitchenContracts.HandoverLine;
import ai.neargo.shop.food.policy.CustodyFulfillmentPolicy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

class CustodyFulfillmentPolicyTest {
    CustodyReceiptVO receipt(String op, String qty, String store) {
        var source =
                new CustodySourceLine(
                        "LINE",
                        "ROOT",
                        List.of(
                                new CoreLineScope(
                                        "A",
                                        11L,
                                        "CHILD",
                                        new BigDecimal("0.500"),
                                        "KG",
                                        "hash",
                                        0L)),
                        List.of());
        return new CustodyReceiptVO(
                op,
                "E",
                store,
                "B",
                "ORDER",
                "SUB",
                "BIND",
                0L,
                0L,
                "frozen",
                "CUSTODY_HANDOVER",
                "PICKUP",
                List.of(new HandoverLine("LINE", new BigDecimal(qty))),
                List.of(source),
                null,
                null,
                "A",
                "2026-10-24T00:00:00Z",
                false,
                true);
    }

    @Test
    void cumulativePhysicalHandoverBecomesExactOriginalComponentQuantity() {
        var a = receipt("1", "0.5", "S");
        var b = receipt("2", "0.5", "S");
        var targets =
                CustodyFulfillmentPolicy.targets(
                        List.of(a, b), b, Map.of("LINE", new BigDecimal("2")));
        assertThat(targets).hasSize(1);
        assertThat(targets.getFirst().sourceComponentRef()).isEqualTo("CHILD");
        assertThat(targets.getFirst().cumulativeQty()).isEqualByComparingTo("0.250");
        assertThat(targets.getFirst().unit()).isEqualTo("KG");
    }

    @Test
    void anotherStoreCannotSupplyCustodyEvidence() {
        var a = receipt("1", "0.5", "OTHER");
        var b = receipt("2", "0.5", "S");
        assertThatThrownBy(
                        () ->
                                CustodyFulfillmentPolicy.targets(
                                        List.of(a, b), b, Map.of("LINE", BigDecimal.ONE)))
                .isInstanceOf(BizException.class);
    }
}
