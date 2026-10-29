package ai.neargo.shop.scenario;

import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.inventory.domain.service.ReservationService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 单一写入方的内嵌一侧（M8 T8.5、AC-27）：业主翻给独立服务（或正在翻）之后，商城内嵌不再碰这本账，
 * 内嵌进程的到期回收也不再回收它的预留 —— 否则过渡期两边同时写同一本账。
 */
@SpringBootTest
@ActiveProfiles("test")
class InventoryEmbeddedWriterGuardTest {

    @Autowired InventoryAclService acl;
    @Autowired ReservationService reservations;
    @Autowired ai.neargo.inventory.domain.service.StockCountService counts;
    @Autowired @Qualifier("invDataSource") DataSource invDataSource;

    @Test
    void ownerFlippedToTheServiceIsNoLongerResolvedByTheEmbeddedPath() {
        String entity = "E-FLIP-" + System.nanoTime();
        String owner = acl.ownerIdOf(entity);
        assertThat(acl.findOwnerId(entity)).isEqualTo(owner);
        JdbcTemplate sql = new JdbcTemplate(invDataSource);

        for (String elsewhere : List.of("SVC", "FLIPPING")) {
            sql.update("update inv_owner set write_authority = ? where owner_id = ?", elsewhere, owner);
            assertThatThrownBy(() -> acl.ownerIdOf(entity))
                    .isInstanceOfSatisfying(BizException.class,
                            e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
            assertThatThrownBy(() -> acl.findOwnerId(entity)).isInstanceOf(BizException.class);
        }
        sql.update("update inv_owner set write_authority = 'EMBEDDED' where owner_id = ?", owner);
        assertThat(acl.findOwnerId(entity)).isEqualTo(owner);
    }

    @Test
    void writesThatReachTheOwnerBySkuAreRefusedToo() {
        // 下单锁货、商品镜像、商品页改库存都是按 SKU 反查业主，不经商家号解析 —— 闸在领域写方法上
        String owner = acl.ownerIdOf("E-FLIP-SKU-" + System.nanoTime());
        JdbcTemplate sql = new JdbcTemplate(invDataSource);
        sql.update("update inv_owner set write_authority = 'SVC' where owner_id = ?", owner);
        assertThatThrownBy(() -> reservations.reserve(owner, "R-SKU-" + System.nanoTime(),
                List.of(new ReservationService.Line("ITEM-X", "LOC-X", 1)), 900))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThatThrownBy(() -> counts.adjustOne(owner, "LOC-X", "ITEM-X", 3, "OTHER", "GOODS_PAGE"))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        sql.update("update inv_owner set write_authority = 'EMBEDDED' where owner_id = ?", owner);
    }

    @Test
    void embeddedReleaseByOrderNoLeavesServiceWrittenMerchantsAlone() {
        String owner = acl.ownerIdOf("E-FLIP-REL-" + System.nanoTime());
        JdbcTemplate sql = new JdbcTemplate(invDataSource);
        String ref = "R-REL-" + System.nanoTime();
        sql.update("insert into inv_reservation (reservation_id, owner_id, external_ref, status, lifecycle_version,"
                        + " expires_at, created_at, updated_at) values (?, ?, ?, 'HELD', 1, ?, ?, ?)",
                "RS" + System.nanoTime(), owner, ref, LocalDateTime.now().plusHours(1), LocalDateTime.now(),
                LocalDateTime.now());
        sql.update("update inv_owner set write_authority = 'SVC' where owner_id = ?", owner);
        reservations.releaseByRef(ref);
        assertThat(sql.queryForObject("select status from inv_reservation where owner_id = ? and external_ref = ?",
                String.class, owner, ref)).isEqualTo("HELD");
        sql.update("update inv_owner set write_authority = 'EMBEDDED' where owner_id = ?", owner);
    }

    @Test
    void embeddedExpirySweepSkipsOwnersWrittenByTheService() {
        String owner = acl.ownerIdOf("E-FLIP-EXP-" + System.nanoTime());
        JdbcTemplate sql = new JdbcTemplate(invDataSource);
        String ref = "R-FLIP-" + System.nanoTime();
        // 造一笔已过期的预留（不经余额：只验回收挑不挑它）
        sql.update("insert into inv_reservation (reservation_id, owner_id, external_ref, status, lifecycle_version,"
                        + " expires_at, created_at, updated_at) values (?, ?, ?, 'HELD', 1, ?, ?, ?)",
                "RS" + System.nanoTime(), owner, ref, LocalDateTime.now().minusMinutes(5), LocalDateTime.now(),
                LocalDateTime.now());
        sql.update("update inv_owner set write_authority = 'SVC' where owner_id = ?", owner);
        reservations.expireOverdue(1000);
        assertThat(sql.queryForObject("select status from inv_reservation where owner_id = ? and external_ref = ?",
                String.class, owner, ref)).isEqualTo("HELD");
        sql.update("update inv_owner set write_authority = 'EMBEDDED' where owner_id = ?", owner);
        reservations.expireOverdue(1000);
        assertThat(sql.queryForObject("select status from inv_reservation where owner_id = ? and external_ref = ?",
                String.class, owner, ref)).isEqualTo("EXPIRED");
    }
}
