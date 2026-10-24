package ai.neargo.shop.spi.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Command;
import ai.neargo.shop.spi.inventory.InventoryProductionPort.Line;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class InventoryProductionFactsTest {
    private static final String HASH="a".repeat(64);
    private Command command(List<Line> lines) {
        return new Command("M1","S1","EV1","PRODUCTION",Instant.parse("2026-10-24T01:00:00Z"),lines);
    }
    private Line line(String item,String quantity) { return new Line(item,new BigDecimal(quantity),"g",HASH); }
    @Test void reorderedAndEquivalentDecimalsKeepOriginalPostingIdentity() {
        var a=command(List.of(line("b","30.00"),line("a","150.0")));
        var b=command(List.of(line("a","150"),line("b","30")));
        assertThat(InventoryProductionFacts.hash(a)).isEqualTo(InventoryProductionFacts.hash(b));
        assertThat(InventoryProductionFacts.integerTotal(a)).isEqualTo(180);
    }
    @Test void changingARealUnitQuantityOrStoreChangesOriginalReceiptHash() {
        var a=command(List.of(line("a","150")));
        assertThat(InventoryProductionFacts.hash(a)).isNotEqualTo(InventoryProductionFacts.hash(
                command(List.of(line("a","151")))));
        assertThat(InventoryProductionFacts.hash(a)).isNotEqualTo(InventoryProductionFacts.hash(
                new Command("M1","S2","EV1","PRODUCTION",a.occurredAt(),a.lines())));
        assertThat(InventoryProductionFacts.hash(a)).isNotEqualTo(InventoryProductionFacts.hash(
                command(List.of(new Line("a",new BigDecimal("150"),"ml",HASH)))));
    }
    @Test void duplicateMaterialRequiresExplicitDemandAggregationRatherThanDoublePosting() {
        assertThatThrownBy(() -> InventoryProductionFacts.normalize(command(List.of(line("a","1"),line("a","2")))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void fractionalDemandDoesNotRoundIntoAWholeInventoryPosting() {
        var fractional=command(List.of(line("a","0.125")));
        assertThat(InventoryProductionFacts.normalize(fractional).lines().getFirst().quantity())
                .isEqualByComparingTo("0.125");
        assertThatThrownBy(() -> InventoryProductionFacts.integerTotal(fractional))
                .isInstanceOf(ArithmeticException.class);
    }
    @Test void aggregateQuantityCannotOverflowTheOriginalInventoryDocument() {
        assertThatThrownBy(() -> InventoryProductionFacts.integerTotal(command(
                List.of(line("a","2147483647"),line("b","1"))))).isInstanceOf(ArithmeticException.class);
    }
    @Test void noNegativeOrZeroOrUnknownMaterialDefinitionCanBecomeAProductionFact() {
        for(String qty:List.of("0","-1"))
            assertThatThrownBy(() -> InventoryProductionFacts.normalize(command(List.of(line("a",qty)))))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> InventoryProductionFacts.normalize(command(
                List.of(new Line("a",BigDecimal.ONE,"g",null))))).isInstanceOf(IllegalArgumentException.class);
    }
}
