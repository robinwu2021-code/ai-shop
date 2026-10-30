package ai.neargo.shop.merchant.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class PersonnelInputTest {
    private AssignPersonnelReq request(List<String> positions) {
        return new AssignPersonnelReq(0L, null, "S", OffsetDateTime.parse("2026-01-01T08:00:00+08:00"),
                null, true, null, 0, positions, List.of());
    }
    @Test
    void malformedPositionIsBusinessErrorRatherThanNullPointer() {
        assertThatThrownBy(() -> request(Arrays.asList((String) null))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> request(List.of("TECHNICIAN", "TECHNICIAN"))).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> request(List.of("OWNER"))).isInstanceOf(BizException.class);
    }
    @Test
    void copiesAndCanonicalizesPositionsAndUtcBeforeHashing() {
        var positions = new ArrayList<>(List.of("WAITER", "TECHNICIAN"));
        var request = request(positions);
        positions.clear();
        assertThat(request.positions()).containsExactly("TECHNICIAN", "WAITER");
        assertThat(request.validFrom()).isEqualTo(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        assertThatThrownBy(() -> request.positions().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test
    void boundsVersionsAndDoesNotAcceptAnInitialAssignmentAsAnUpdate() {
        assertThatThrownBy(() -> new LinkPersonnelAccountReq(Long.MAX_VALUE, null)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new ChangePersonnelStatusReq(1L, "ACTIVE", " ")).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new PersonnelQuery(1, 101, null, null, null, null)).isInstanceOf(BizException.class);
    }
}
