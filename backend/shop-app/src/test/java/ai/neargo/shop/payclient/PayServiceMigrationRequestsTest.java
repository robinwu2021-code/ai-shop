package ai.neargo.shop.payclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.paysync.NearPayStoreProfile;
import ai.neargo.shop.merchant.paysync.StoreSnapshotReader;
import java.util.List;
import org.junit.jupiter.api.Test;

/** TDD-UME迁移建档 AC1–AC4：迁移建档的请求组装。 */
class PayServiceMigrationRequestsTest {
    static final StoreSnapshotReader.Entity UME =
            new StoreSnapshotReader.Entity("MUME02380159443150569472", "創科系統有限公司", "TW", 1_794_542_005_001L);

    static StoreSnapshotReader.Snapshot store(long id, String no, boolean deleted) {
        return new StoreSnapshotReader.Snapshot(id, no, "MUME02380159443150569472", "創科系統收款帳號", null, "TW", "創科系統收款帳號",
                null, null, null, null, null, "ACTIVE", deleted, 1_793_952_472_000L);
    }

    @Test
    void umeEntityIsAppliedWithItsStoresAndNoProfile() {
        var s = store(1090, "STUME12380164319477760000", false);
        var body = PayServiceApplyRequests.migration(UME, List.of(s, store(1091, "STUME-GONE", true)), "ST-UME-CONFIG",
                PayServiceApplyRequestsTest.PROPS);
        var sync = NearPayStoreProfile.request(s, PayServiceApplyRequestsTest.PROPS);
        assertThat(body.stores()).as("AC1 未删除的导入门店随申请关联").hasSize(1);
        assertThat(body.stores().getFirst().externalId()).isEqualTo(sync.get("storeRef"));
        assertThat(body.stores().getFirst().profileHash()).isEqualTo(sync.get("profileHash"));
        assertThat(body.profile()).as("AC4 不带资料、不提交证件号").isNull();
        assertThat(body.profileRevision()).isNull();
        assertThat(body.entityName()).isEqualTo("創科系統有限公司");
        assertThat(body.sourceEntityRef()).isEqualTo("MUME02380159443150569472");
        assertThat(body.actorRef().realm()).isEqualTo("OPERATOR");
        assertThat(body.actorRef().subject()).isEqualTo("ST-UME-CONFIG");
        assertThat(body.capabilityType()).isEqualTo("FIAT");
    }

    @Test
    void theEventIdIsFixedPerEntityRevision() {
        var a = PayServiceApplyRequests.migration(UME, List.of(), "ST-A", PayServiceApplyRequestsTest.PROPS).sourceEventId();
        var b = PayServiceApplyRequests.migration(UME, List.of(), "ST-B", PayServiceApplyRequestsTest.PROPS).sourceEventId();
        assertThat(a).as("AC2 同主体同版本同一事件号，操作人不同也一样").isEqualTo(b)
                .isEqualTo("ais-migrate-MUME02380159443150569472-1794542005001").hasSizeLessThanOrEqualTo(64);
    }

    @Test
    void nonUmeEntitiesAreRefused() {
        var native_ = new StoreSnapshotReader.Entity("M261106BF7GK8052W", "台北早餐", "TW", 1L);
        assertThatThrownBy(() -> PayServiceApplyRequests.migration(native_, List.of(), "ST-A", PayServiceApplyRequestsTest.PROPS))
                .as("AC3 非 UME 导入主体只能走带资料的申请支付服务").isInstanceOf(BizException.class);
        assertThatThrownBy(() -> PayServiceApplyRequests.migration(UME, List.of(), " ", PayServiceApplyRequestsTest.PROPS))
                .isInstanceOf(BizException.class);
    }
}
