package ai.neargo.shop.food.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.food.dto.BusinessShareContracts.AllocationSource;
import ai.neargo.shop.food.dto.BusinessShareContracts.CreateReq;
import ai.neargo.shop.food.dto.BusinessShareContracts.LineWeight;
import ai.neargo.shop.food.dto.BusinessShareContracts.Person;
import ai.neargo.shop.food.policy.BusinessSharePolicy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exact minor-unit allocation is independent of payment, current balances and input ordering. */
class BusinessSharePolicyTest {
    @Test void optionalAmountRoundTripsForPersonSplitsButNeverCoercesMoney() {
        var json = JsonMapper.builder().build();
        var person = new Person("A", null);
        assertThat(json.readValue(json.writeValueAsString(person), Person.class)).isEqualTo(person);
        for (String value : new String[]{"1.5", "\"1\"", "true", "-1", "9223372036854775808"}) {
            assertThatThrownBy(() -> json.readValue("{\"personNo\":\"A\",\"amountMinor\":" + value + "}",
                    Person.class)).isInstanceOf(RuntimeException.class);
        }
        assertThat(json.readValue("{\"personNo\":\"A\",\"amountMinor\":0}", Person.class))
                .isEqualTo(new Person("A", 0L));
    }
    @Test void tiesUsePersonIdentityAndNeverLoseAMinorUnit() {
        assertThat(BusinessSharePolicy.largestRemainder(5,Map.of("B",1L,"A",1L,"C",1L)))
                .containsEntry("A",2L).containsEntry("B",2L).containsEntry("C",1L);
    }
    @Test void bigIntegerIntermediatesSupportTheMaximumOriginalAmount() {
        var split=BusinessSharePolicy.largestRemainder(Long.MAX_VALUE,Map.of("A",Long.MAX_VALUE,"B",Long.MAX_VALUE));
        assertThat(split.get("A")).isEqualTo(4611686018427387904L);
        assertThat(split.get("B")).isEqualTo(4611686018427387903L);
        assertThat(Math.addExact(split.get("A"),split.get("B"))).isEqualTo(Long.MAX_VALUE);
    }
    @Test void zeroOriginalAmountHasNoInventedCharge() {
        var division=BusinessSharePolicy.divide(List.of(source("X","L",0)),request("BY_PERSON",
                List.of(new Person("A",null),new Person("B",null)),null));
        assertThat(division.people()).allSatisfy(p -> assertThat(p.amountMinor()).isZero());
        assertThat(division.cells()).allSatisfy(c -> assertThat(c.amountMinor()).isZero());
    }
    @Test void byPersonConservesEveryOriginalRowAndEveryPersonAcrossOrderRoots() {
        var division=BusinessSharePolicy.divide(List.of(source("Y","L2",2),source("X","L1",3)),
                request("BY_PERSON",List.of(new Person("B",null),new Person("A",null)),null));
        assertThat(division.people()).extracting(p -> p.amountMinor()).containsExactly(3L,2L);
        assertThat(division.cells().stream().filter(c -> c.allocationNo().equals("X"))
                .mapToLong(c -> c.amountMinor()).sum()).isEqualTo(3);
        assertThat(division.cells().stream().filter(c -> c.allocationNo().equals("Y"))
                .mapToLong(c -> c.amountMinor()).sum()).isEqualTo(2);
        assertThat(division.cells().stream().filter(c -> c.personNo().equals("A"))
                .mapToLong(c -> c.amountMinor()).sum()).isEqualTo(3);
    }
    @Test void reorderingSourcesAndPeopleCannotChangeThePlanMatrix() {
        var first=BusinessSharePolicy.divide(List.of(source("X","L1",7),source("Y","L2",4)),
                request("BY_PERSON",List.of(new Person("B",null),new Person("A",null)),null));
        var second=BusinessSharePolicy.divide(List.of(source("Y","L2",4),source("X","L1",7)),
                request("BY_PERSON",List.of(new Person("A",null),new Person("B",null)),null));
        assertThat(first).isEqualTo(second);
    }
    @Test void byAmountMustExactlyEqualOriginalAuthority() {
        var source=List.of(source("X","L",10));
        var divided=BusinessSharePolicy.divide(source,request("BY_AMOUNT",
                List.of(new Person("A",3L),new Person("B",7L)),null));
        assertThat(divided.people()).extracting(p -> p.amountMinor()).containsExactly(3L,7L);
        assertThatThrownBy(() -> BusinessSharePolicy.divide(source,request("BY_AMOUNT",
                List.of(new Person("A",3L),new Person("B",8L)),null))).isInstanceOf(BizException.class);
    }
    @Test void byLineIncludesAllOriginalAddonAllocationsUnderItsFoodLine() {
        var division=BusinessSharePolicy.divide(List.of(source("X","L",5),source("Y","L",2)),
                request("BY_LINE",List.of(new Person("A",null),new Person("B",null)),
                        List.of(new LineWeight("L","A",1L),new LineWeight("L","B",1L))));
        assertThat(division.people()).extracting(p -> p.amountMinor()).containsExactly(4L,3L);
        assertThat(division.cells().stream().mapToLong(c -> c.amountMinor()).sum()).isEqualTo(7);
    }
    @Test void splittingOneFoodLineIntoTinyAddonAmountsCannotBiasItsWholeResponsibility() {
        var division=BusinessSharePolicy.divide(List.of(source("X","L",1),source("Y","L",1)),
                request("BY_LINE",List.of(new Person("A",null),new Person("B",null)),
                        List.of(new LineWeight("L","A",1L),new LineWeight("L","B",1L))));
        assertThat(division.people()).extracting(p -> p.amountMinor()).containsExactly(1L,1L);
    }
    @Test void byLineRejectsMissingUnknownOrDuplicateResponsibilities() {
        var people=List.of(new Person("A",null)); var source=List.of(source("X","L",5));
        assertThatThrownBy(() -> BusinessSharePolicy.divide(source,request("BY_LINE",people,List.of())))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> BusinessSharePolicy.divide(source,request("BY_LINE",people,
                List.of(new LineWeight("OTHER","A",1L))))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> BusinessSharePolicy.divide(source,request("BY_LINE",people,
                List.of(new LineWeight("L","A",1L),new LineWeight("L","A",2L)))))
                .isInstanceOf(BizException.class);
    }
    @Test void rejectsDuplicatePeopleNegativeWeightsAndOriginalOverflow() {
        assertThatThrownBy(() -> BusinessSharePolicy.divide(List.of(source("X","L",5)),
                request("BY_PERSON",List.of(new Person("A",null),new Person("A",null)),null)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> BusinessSharePolicy.largestRemainder(5,Map.of("A",-1L)))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> BusinessSharePolicy.divide(
                List.of(source("X","L",Long.MAX_VALUE),source("Y","L",1)),
                request("BY_PERSON",List.of(new Person("A",null)),null))).isInstanceOf(BizException.class);
    }
    private static CreateReq request(String mode,List<Person> people,List<LineWeight> lines) {
        return new CreateReq(0L,mode,people,lines,"business responsibility");
    }
    private static AllocationSource source(String allocation,String foodLine,long amount) {
        return new AllocationSource(allocation,"B",foodLine,"original-line",allocation,1L,BigDecimal.ONE,
                "EA","original-hash",0L,0L,1L,"frozen-line","CNY",amount);
    }
}
