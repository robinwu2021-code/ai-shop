package ai.neargo.shop.logistics.compensation;

import ai.neargo.shop.logistics.domain.WaybillStatus;
import ai.neargo.shop.logistics.entity.LgsWaybill;
import ai.neargo.shop.logistics.mapper.LogisticsMappers.WaybillMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 补偿作业选哪些单（TDD-物流模块 M8 / AC8）—— 对真库跑，因为缺陷只会藏在 SQL 条件里。
 *
 * <p>判据：<b>正常推送中的单一单都不碰</b>。作业每小时一轮，选错一个条件就是每小时把全部在途单问一遍。
 */
@SpringBootTest
@ActiveProfiles("test")
class CompensationSelectionTest {

    private static final String PREFIX = "SH-CMP-";
    private static final long H = 3600_000L;

    @Autowired
    private CompensationSweeper sweeper;
    @Autowired
    private WaybillMapper waybills;

    @AfterEach
    void cleanup() {
        waybills.delete(Wrappers.<LgsWaybill>query().likeRight("shipment_no", PREFIX));
    }

    @Test
    @DisplayName("★★★ 只选「订阅成功、未终态、24 小时没进展、6 小时内没问过」的；正常推送中的单 0 次")
    void selectsOnlySilentOnes() {
        long now = System.currentTimeMillis();
        insert("PUSHING", LgsWaybill.SUB_DONE, WaybillStatus.IN_TRANSIT, now - H, null, false);
        insert("SILENT", LgsWaybill.SUB_DONE, WaybillStatus.IN_TRANSIT, now - 25 * H, null, false);
        insert("ENDED-SILENT", LgsWaybill.SUB_ENDED, WaybillStatus.DELIVERING, now - 30 * H, null, false);
        insert("NEVER-OLD", LgsWaybill.SUB_DONE, WaybillStatus.CREATED, null, null, true);
        insert("NEVER-NEW", LgsWaybill.SUB_DONE, WaybillStatus.CREATED, null, null, false);
        insert("THROTTLED", LgsWaybill.SUB_DONE, WaybillStatus.IN_TRANSIT, now - 25 * H, now - H, false);
        insert("THROTTLE-OVER", LgsWaybill.SUB_DONE, WaybillStatus.IN_TRANSIT, now - 25 * H, now - 7 * H, false);
        insert("SIGNED", LgsWaybill.SUB_DONE, WaybillStatus.DELIVERED, now - 50 * H, null, false);
        insert("CANCELLED", LgsWaybill.SUB_DONE, WaybillStatus.CANCELLED, now - 50 * H, null, false);
        insert("PENDING", LgsWaybill.SUB_PENDING, WaybillStatus.IN_TRANSIT, now - 50 * H, null, true);
        insert("FATAL", LgsWaybill.SUB_FATAL, WaybillStatus.IN_TRANSIT, now - 50 * H, null, true);

        Set<String> picked = sweeper.silent(now, 1000).stream().map(LgsWaybill::getShipmentNo)
                .filter(n -> n.startsWith(PREFIX)).map(n -> n.substring(PREFIX.length()))
                .collect(Collectors.toSet());

        assertThat(picked).containsExactlyInAnyOrder("SILENT", "ENDED-SILENT", "NEVER-OLD", "THROTTLE-OVER");
    }

    private void insert(String tag, String subState, String status, Long lastEventAt, Long checkedAt,
                        boolean registeredLongAgo) {
        LgsWaybill w = new LgsWaybill();
        w.setShipmentNo(PREFIX + tag);
        w.setBizType(LgsWaybill.BIZ_SUB_ORDER);
        w.setBizRef("SUB-CMP-" + tag);
        w.setCarrier("SF");
        w.setWaybillNo("CMP" + tag);
        w.setProfile(LgsWaybill.PROFILE_WX);
        w.setSubState(subState);
        w.setStatus(status);
        w.setLastEventAt(lastEventAt);
        w.setWxStatusCheckedAt(checkedAt);
        waybills.insert(w);
        if (registeredLongAgo) {
            waybills.update(null, Wrappers.<LgsWaybill>update()
                    .set("created_at", LocalDateTime.now().minusHours(30)).eq("id", w.getId()));
        }
    }
}
