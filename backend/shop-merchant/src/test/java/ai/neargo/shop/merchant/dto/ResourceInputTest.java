package ai.neargo.shop.merchant.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import org.junit.jupiter.api.Test;

/** Caller-supplied identity and capacity limits must fail before any database mutation. */
class ResourceInputTest {
    @Test
    void rejectsStaffIdentityForeignPersonnelCapacityAndInvalidNewRevision() {
        assertThatThrownBy(() -> new SaveResourceReq(null, "STAFF", "P", null, "staff", 1, "ACTIVE", 0L))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new SaveResourceReq(null, "ROOM", "P", null, "room", 1, "ACTIVE", 0L))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new SaveResourceReq(null, "ROOM", null, null, "room", 0, "ACTIVE", 0L))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new SaveResourceReq(null, "ROOM", null, null, "room", 1001, "ACTIVE", 0L))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new SaveResourceReq(null, "ROOM", null, null, "room", 1, "ACTIVE", 1L))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new SaveResourceReq(null, "ROOM", null, null, "room", 1, "SUSPENDED", 0L))
                .isInstanceOf(BizException.class);
    }
    @Test
    void enforcesCanonicalNamesRevisionsAndBoundedSqlQueries() {
        assertThatThrownBy(() -> new SaveResourceReq("R ", "ROOM", null, null, "room", 1, "ACTIVE", 1L))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new SaveResourceReq("R", "ROOM", null, null, "x".repeat(65), 1, "ACTIVE", 1L))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new SaveResourceReq("R", "ROOM", null, null, "room", 1, "ACTIVE", Long.MAX_VALUE))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new ResourceQuery(1, 101, null, null, null, null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new ResourceQuery(0, 20, null, null, null, null))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> new ResourceQuery(null, null, "S ", null, null, null))
                .isInstanceOf(BizException.class);
        var query = new ResourceQuery(null, null, null, "STAFF", "SUSPENDED", null);
        assertThat(query.page()).isOne();
        assertThat(query.size()).isEqualTo(20);
    }
}
