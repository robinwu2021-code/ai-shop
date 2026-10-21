package ai.neargo.shop.merchant.mapper;

import ai.neargo.shop.merchant.dto.OperatorAccessRow;
import ai.neargo.shop.merchant.dto.ResourceCommandFactRow;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.merchant.dto.PersonnelAssignmentFactRow;
import ai.neargo.shop.merchant.dto.PersonnelPositionFactRow;
import ai.neargo.shop.merchant.dto.PersonnelQualificationFactRow;
import ai.neargo.shop.merchant.dto.ResourceFactRow;
import ai.neargo.shop.merchant.dto.ResourceCommandScopeRow;
import ai.neargo.shop.merchant.dto.ResourceBookingAllocationRow;
import ai.neargo.shop.merchant.dto.ResourceBookingCounterRow;
import ai.neargo.shop.merchant.dto.ScheduleRuleFactRow;
import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.entity.MchBookingOwner;
import ai.neargo.shop.merchant.entity.MchResourceCommand;
import ai.neargo.shop.merchant.entity.MchResourceBucket;
import ai.neargo.shop.merchant.entity.MchBookingHold;
import ai.neargo.shop.merchant.entity.MchBookingHoldUnit;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.entity.MchPayoutAccount;
import ai.neargo.shop.merchant.entity.MchPersonnel;
import ai.neargo.shop.merchant.entity.MchPersonnelAccount;
import ai.neargo.shop.merchant.entity.MchPersonnelPosition;
import ai.neargo.shop.merchant.entity.MchPersonnelSkill;
import ai.neargo.shop.merchant.entity.MchPersonnelStore;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.entity.MchResource;
import ai.neargo.shop.merchant.entity.MchScheduleChangeLog;
import ai.neargo.shop.merchant.entity.MchScheduleRule;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.entity.MchStoreRole;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * merchant 域的 Mapper 集合（嵌套接口，沿用 powerbank 的写法）。
 * Mapper负责CRUD与范围投影；关系投影可JOIN确认父链和规范身份。
 * 事务编排、权限和资源/资格许可仍由所有者业务层负责，不在Mapper中替代。
 *
 * <p>原先这六个接口与 user 的四个同住 {@code UserMappers}。合在一起的直接后果是：
 * 任何拿到 {@code UserMappers} 的类都顺手能读写商家表，边界靠自觉——
 * 事实上 {@code CommunityServiceImpl} 与 {@code StoreFavoriteServiceImpl} 都这么做了。
 */
public final class MerchantMappers {

    private MerchantMappers() {
    }

    /** Exact live access projection; source owners must not reuse session-role snapshots or personnel jobs. */
    @Mapper
    public interface OperatorAccessMapper {
        @Select("""
                SELECT a.mch_account_no AS account_no,
                  (a.is_owner=1 OR EXISTS (
                    SELECT 1 FROM mch_store_role g JOIN mch_role d ON d.tenant_no=g.tenant_no
                      AND CAST(d.role_code AS BINARY)=CAST(g.role AS BINARY) AND d.deleted=0
                    WHERE g.tenant_no=a.tenant_no AND g.mch_account_no=a.mch_account_no AND g.deleted=0
                      AND CAST(g.store_no AS BINARY)=CAST(s.store_no AS BINARY)
                      AND (CAST(d.entity_no AS BINARY)=CAST(e.entity_no AS BINARY)
                        OR (d.entity_no='*' AND NOT EXISTS (SELECT 1 FROM mch_role custom
                          WHERE custom.tenant_no=d.tenant_no AND custom.deleted=0
                            AND CAST(custom.entity_no AS BINARY)=CAST(e.entity_no AS BINARY)
                            AND CAST(custom.role_code AS BINARY)=CAST(d.role_code AS BINARY))))
                      AND JSON_VALID(d.perms)
                      AND (JSON_CONTAINS(d.perms,JSON_QUOTE(#{q.permission}))
                        OR JSON_CONTAINS(d.perms,JSON_QUOTE('*'))))) AS allowed
                FROM mch_entity e JOIN mch_store s ON s.tenant_no=e.tenant_no AND s.entity_no=e.entity_no
                JOIN mch_account a ON a.tenant_no=e.tenant_no AND a.entity_no=e.entity_no
                WHERE CAST(e.tenant_no AS BINARY)=CAST(#{q.tenantNo} AS BINARY)
                  AND CAST(e.entity_no AS BINARY)=CAST(#{q.entityNo} AS BINARY)
                  AND CAST(s.store_no AS BINARY)=CAST(#{q.storeNo} AS BINARY)
                  AND e.status='ACTIVE' AND e.archived_at IS NULL AND e.deleted=0
                  AND s.status='ACTIVE' AND s.deleted=0 AND a.status='ACTIVE' AND a.deleted=0
                  AND ((#{q.subjectKind}='MCH' AND CAST(a.mch_account_no AS BINARY)=CAST(#{q.operatorNo} AS BINARY))
                    OR (#{q.subjectKind}='USR' AND CAST(a.user_no AS BINARY)=CAST(#{q.operatorNo} AS BINARY)))
                LIMIT 2
                """)
        List<OperatorAccessRow> selectAccess(@Param("q") MerchantOperatorAccessPort.Query query);
    }

    /** 稳定资源仲裁归属数据访问；完整仲裁由后续服务编排。 */
    public interface BookingOwnerMapper extends BaseMapper<MchBookingOwner> {
        /**
         * Shared parent guards use explicit trusted scope. JSQLParser cannot parse LOCK IN SHARE MODE, so only
         * this fully scoped statement skips data-permission rewriting; generic mapper interception stays enabled.
         */
        @InterceptorIgnore(dataPermission = "true")
        @Select("""
                SELECT e.tenant_no,e.entity_no,s.store_no FROM mch_entity e
                JOIN mch_store s ON s.tenant_no=e.tenant_no AND s.entity_no=e.entity_no
                WHERE e.tenant_no=#{tenantNo} AND e.entity_no=#{entityNo} AND s.store_no=#{storeNo}
                  AND e.status='ACTIVE' AND e.archived_at IS NULL AND e.deleted=0
                  AND s.status='ACTIVE' AND s.deleted=0 LOCK IN SHARE MODE
                """)
        ResourceCommandScopeRow lockScope(@Param("tenantNo") String tenantNo, @Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo);

        /** Current root read; a missing row is established by scoped unique insert, not an unlocked absence check. */
        @Select("""
                SELECT * FROM mch_booking_owner WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND store_no=#{scope.storeNo} AND source_type=#{sourceType} AND source_no=#{sourceNo}
                  AND deleted=0 FOR UPDATE
                """)
        MchBookingOwner lockBySource(@Param("scope") ResourceCommandScopeRow scope,
                @Param("sourceType") String sourceType, @Param("sourceNo") String sourceNo);

        /** Nonlocking replay read occurs only after the duplicate-insert transaction has rolled back. */
        @Select("""
                SELECT * FROM mch_booking_owner WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND store_no=#{scope.storeNo} AND source_type=#{sourceType} AND source_no=#{sourceNo} AND deleted=0
                """)
        MchBookingOwner selectBySource(@Param("scope") ResourceCommandScopeRow scope,
                @Param("sourceType") String sourceType, @Param("sourceNo") String sourceNo);

        /** Stable source binding for recovery receipts; no caller-supplied source identity is trusted on query. */
        @Select("""
                SELECT * FROM mch_booking_owner WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND store_no=#{scope.storeNo} AND owner_no=#{ownerNo} AND deleted=0
                """)
        MchBookingOwner selectOwner(@Param("scope") ResourceCommandScopeRow scope, @Param("ownerNo") String ownerNo);

        /** Must precede command/resource locks; every business command serializes on this stable root. */
        @Select("""
                SELECT * FROM mch_booking_owner WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND store_no=#{scope.storeNo} AND owner_no=#{ownerNo} AND deleted=0 FOR UPDATE
                """)
        MchBookingOwner lockOwner(@Param("scope") ResourceCommandScopeRow scope, @Param("ownerNo") String ownerNo);

        /** Root CAS is part of the same local transaction as capacity evidence and the terminal command result. */
        @Update("""
                UPDATE mch_booking_owner SET current_hold_no=#{holdNo},source_revision=#{sourceRevision},
                  owner_version=owner_version+1,version=version+1,updated_at=#{now},updated_by=#{actor}
                WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo}
                  AND owner_no=#{ownerNo} AND owner_version=#{expectedVersion} AND deleted=0
                  AND owner_version<9223372036854775807 AND version<9223372036854775807
                  AND (current_hold_no=#{previousHoldNo} OR (current_hold_no IS NULL AND #{previousHoldNo} IS NULL))
                """)
        int advanceHead(@Param("scope") ResourceCommandScopeRow scope, @Param("ownerNo") String ownerNo,
                @Param("expectedVersion") long expectedVersion, @Param("previousHoldNo") String previousHoldNo,
                @Param("holdNo") String holdNo, @Param("sourceRevision") Long sourceRevision,
                @Param("actor") String actor, @Param("now") LocalDateTime now);

    }

    /** 资源持久命令与结果数据访问；完整仲裁由后续服务编排。 */
    public interface ResourceCommandMapper extends BaseMapper<MchResourceCommand> {
        /** One original parent-chain snapshot; never substitutes the owner's current hold or latest command. */
        @Select("""
                SELECT c.tenant_no,c.entity_no,c.store_no,c.command_no,o.source_type,o.source_no,
                  c.source_revision,c.action,c.payload_hash AS command_hash,c.frozen_payload,c.status,
                  c.version AS revision,c.result_payload,c.rejection_code
                FROM mch_resource_command c JOIN mch_booking_owner o ON o.tenant_no=c.tenant_no
                  AND o.entity_no=c.entity_no AND o.store_no=c.store_no AND o.owner_no=c.owner_no AND o.deleted=0
                WHERE c.tenant_no=#{scope.tenantNo} AND c.entity_no=#{scope.entityNo} AND c.store_no=#{scope.storeNo}
                  AND c.command_no=#{commandNo} AND c.deleted=0
                """)
        ResourceCommandFactRow selectOriginalFact(@Param("scope") ResourceCommandScopeRow scope,
                @Param("commandNo") String commandNo);

        /** Explicit parent chain prevents recovery by command number from crossing scope or a foreign owner. */
        @Select("""
                SELECT c.* FROM mch_resource_command c JOIN mch_booking_owner o
                  ON o.tenant_no=c.tenant_no AND o.entity_no=c.entity_no AND o.store_no=c.store_no
                  AND o.owner_no=c.owner_no AND o.deleted=0
                WHERE c.tenant_no=#{scope.tenantNo} AND c.entity_no=#{scope.entityNo} AND c.store_no=#{scope.storeNo}
                  AND c.command_no=#{commandNo} AND c.deleted=0
                """)
        MchResourceCommand selectCommand(@Param("scope") ResourceCommandScopeRow scope,
                @Param("commandNo") String commandNo);

        /** Immutable key evidence; no update is performed during a replay. */
        @Select("""
                SELECT c.* FROM mch_resource_command c JOIN mch_booking_owner o
                  ON o.tenant_no=c.tenant_no AND o.entity_no=c.entity_no AND o.store_no=c.store_no
                  AND o.owner_no=c.owner_no AND o.deleted=0
                WHERE c.tenant_no=#{scope.tenantNo} AND c.entity_no=#{scope.entityNo} AND c.store_no=#{scope.storeNo}
                  AND c.action=#{action} AND c.request_key_hash=#{keyHash} AND c.deleted=0
                """)
        MchResourceCommand selectByKey(@Param("scope") ResourceCommandScopeRow scope, @Param("action") String action,
                @Param("keyHash") String keyHash);

        /** Caller has already locked the scoped owner; this current read cannot reuse an old RR snapshot. */
        @Select("""
                SELECT * FROM mch_resource_command WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND store_no=#{scope.storeNo} AND owner_no=#{ownerNo} AND command_no=#{commandNo}
                  AND deleted=0 FOR UPDATE
                """)
        MchResourceCommand lockCommand(@Param("scope") ResourceCommandScopeRow scope,
                @Param("ownerNo") String ownerNo, @Param("commandNo") String commandNo);

        /** Explicit audit/version and ACCEPTED predicate protect terminal records from stale recovery workers. */
        @Update("""
                UPDATE mch_resource_command SET wait_reason=#{reason},next_retry_at=#{retryAt},version=version+1,
                  updated_at=#{now},updated_by=#{actor}
                WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo}
                  AND command_no=#{commandNo} AND status='ACCEPTED' AND version=#{version} AND deleted=0
                  AND version<9223372036854775807
                """)
        int markWaiting(@Param("scope") ResourceCommandScopeRow scope, @Param("commandNo") String commandNo,
                @Param("version") long version, @Param("reason") String reason,
                @Param("retryAt") LocalDateTime retryAt, @Param("actor") String actor,
                @Param("now") LocalDateTime now);

        /** Frozen request columns are deliberately absent; clearing retry fields is part of terminalization. */
        @Update("""
                UPDATE mch_resource_command SET status=#{status},result_payload=#{result},rejection_code=#{code},
                  wait_reason=NULL,next_retry_at=NULL,version=version+1,updated_at=#{now},updated_by=#{actor}
                WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo}
                  AND command_no=#{commandNo} AND status='ACCEPTED' AND version=#{version} AND deleted=0
                  AND version<9223372036854775807
                """)
        int finishAccepted(@Param("scope") ResourceCommandScopeRow scope, @Param("commandNo") String commandNo,
                @Param("version") long version, @Param("status") String status, @Param("result") String result,
                @Param("code") String code, @Param("actor") String actor, @Param("now") LocalDateTime now);

    }

    /** 跨店统一容量仲裁桶数据访问；完整仲裁由后续服务编排。 */
    public interface ResourceBucketMapper extends BaseMapper<MchResourceBucket> {
        /** Never infer availability from a drifted count or silently drop orphaned/deleted active allocations. */
        @Select("SELECT COALESCE(SUM(CASE WHEN h.status='HELD' THEN u.qty ELSE 0 END),0) AS held,"
                + "COALESCE(SUM(CASE WHEN h.status='CONFIRMED' THEN u.qty ELSE 0 END),0) AS confirmed,"
                + "COALESCE(SUM(CASE WHEN h.id IS NULL OR h.deleted<>0 OR u.deleted<>0 "
                + "OR h.status NOT IN ('HELD','CONFIRMED') OR o.id IS NULL OR o.deleted<>0 "
                + "OR o.current_hold_no IS NULL OR o.current_hold_no<>h.hold_no "
                + "OR o.source_revision<>h.source_revision THEN 1 ELSE 0 END),0) AS invalid "
                + "FROM mch_booking_hold_unit u LEFT JOIN mch_booking_hold h ON h.tenant_no=u.tenant_no "
                + "AND h.entity_no=u.entity_no AND h.hold_no=u.hold_no LEFT JOIN mch_booking_owner o "
                + "ON o.tenant_no=h.tenant_no AND o.entity_no=h.entity_no AND o.store_no=h.store_no "
                + "AND o.owner_no=h.owner_no WHERE u.tenant_no=#{scope.tenantNo} AND u.entity_no=#{scope.entityNo} "
                + "AND u.bucket_no=#{bucket} AND u.released_at IS NULL")
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        ResourceBookingCounterRow selectBookingCounters(@Param("scope") ResourceCommandScopeRow scope,
                @Param("bucket") String bucket);

        /** Root locks are already held; bucket identity deliberately excludes store for shared STAFF capacity. */
        @Select("SELECT * FROM mch_resource_bucket WHERE tenant_no=#{scope.tenantNo} "
                + "AND entity_no=#{scope.entityNo} AND resource_no=#{resource} AND start_at=#{start} "
                + "AND deleted=0 FOR UPDATE")
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchResourceBucket lockBookingBucket(@Param("scope") ResourceCommandScopeRow scope,
                @Param("resource") String resource, @Param("start") LocalDateTime start);

        /** Signed net deltas avoid replacement double charging; SQL independently enforces nonnegative capacity. */
        @Update("UPDATE mch_resource_bucket SET held_qty=held_qty+#{held},confirmed_qty=confirmed_qty+#{confirmed},"
                + "version=version+1,updated_by=#{actor},updated_at=#{at} WHERE tenant_no=#{scope.tenantNo} "
                + "AND entity_no=#{scope.entityNo} AND bucket_no=#{bucket} AND version=#{version} AND deleted=0 "
                + "AND version<9223372036854775807 AND held_qty+#{held}>=0 AND confirmed_qty+#{confirmed}>=0 "
                + "AND CAST(held_qty AS DECIMAL(20,0))+confirmed_qty+#{held}+#{confirmed}<=capacity")
        int adjustBookingCapacity(@Param("scope") ResourceCommandScopeRow scope, @Param("bucket") String bucket,
                @Param("version") long version, @Param("held") long held, @Param("confirmed") long confirmed,
                @Param("actor") String actor, @Param("at") LocalDateTime at);
    }

    /** 资源集合持有凭证数据访问；完整仲裁由后续服务编排。 */
    public interface BookingHoldMapper extends BaseMapper<MchBookingHold> {
        /** Acquired after the full personnel/resource union, never before discovering additional roots. */
        @Select("SELECT * FROM mch_booking_hold WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} "
                + "AND store_no=#{scope.storeNo} AND owner_no=#{owner} AND hold_no=#{hold} AND deleted=0 FOR UPDATE")
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchBookingHold lockBookingHold(@Param("scope") ResourceCommandScopeRow scope, @Param("owner") String owner,
                @Param("hold") String hold);

        /** Explicit nullable termination/link writes and revision CAS preserve history and immutable creation hash. */
        @Update("UPDATE mch_booking_hold SET status=#{next.status},source_revision=#{next.sourceRevision},"
                + "lease_revision=#{next.leaseRevision},expires_at=#{next.expiresAt},"
                + "expiration_policy=#{next.expirationPolicy},replaced_by_hold_no=#{next.replacedByHoldNo},"
                + "termination_reason=#{next.terminationReason},terminated_at=#{next.terminatedAt},"
                + "version=version+1,updated_by=#{actor},updated_at=#{at} WHERE tenant_no=#{scope.tenantNo} "
                + "AND entity_no=#{scope.entityNo} AND store_no=#{scope.storeNo} AND owner_no=#{next.ownerNo} "
                + "AND hold_no=#{next.holdNo} AND version=#{next.version} AND deleted=0 "
                + "AND version<9223372036854775807 AND execution_started_at IS NULL")
        int saveBookingHold(@Param("scope") ResourceCommandScopeRow scope, @Param("next") MchBookingHold next,
                @Param("actor") String actor, @Param("at") LocalDateTime at);

        /** Resolves canonical hold identity and owner/store parent chain before a command can reference it. */
        @Select("""
                SELECT * FROM mch_booking_hold WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND store_no=#{scope.storeNo} AND owner_no=#{ownerNo} AND hold_no=#{holdNo} AND deleted=0
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchBookingHold selectOwnedHold(@Param("scope") ResourceCommandScopeRow scope,
                @Param("ownerNo") String ownerNo, @Param("holdNo") String holdNo);

        /** Old-head retirement includes soft-deleted unreleased units; deletion must never manufacture a release. */
        @Select("""
                SELECT COUNT(*) FROM mch_booking_hold_unit
                WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} AND hold_no=#{holdNo}
                  AND released_at IS NULL AND (#{includeDeleted}=TRUE OR deleted=0)
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        long countUnreleased(@Param("scope") ResourceCommandScopeRow scope, @Param("holdNo") String holdNo,
                @Param("includeDeleted") boolean includeDeleted);

    }

    /** 资源持有分配历史明细数据访问；完整仲裁由后续服务编排。 */
    public interface BookingHoldUnitMapper extends BaseMapper<MchBookingHoldUnit> {
        /** Owner root serializes discovery; LEFT JOIN exposes corrupt/deleted parent evidence rather than dropping it. */
        @Select("SELECT u.unit_no,u.version,u.bucket_no,b.resource_no,b.start_at,b.end_at,u.qty,u.source_line_ref,"
                + "u.deleted FROM mch_booking_hold_unit u LEFT JOIN mch_resource_bucket b "
                + "ON b.tenant_no=u.tenant_no AND b.entity_no=u.entity_no AND b.bucket_no=u.bucket_no AND b.deleted=0 "
                + "WHERE u.tenant_no=#{scope.tenantNo} AND u.entity_no=#{scope.entityNo} "
                + "AND u.hold_no=#{hold} AND u.released_at IS NULL ORDER BY u.id LIMIT 10001")
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        List<ResourceBookingAllocationRow> selectBookingAllocations(@Param("scope") ResourceCommandScopeRow scope,
                @Param("hold") String hold);

        /** Unit release is evidence, never deletion; effects are atomic with bucket deltas and the original result. */
        @Update("UPDATE mch_booking_hold_unit SET released_at=#{at},release_command_no=#{command},version=version+1,"
                + "updated_at=#{at},updated_by=#{actor} WHERE tenant_no=#{scope.tenantNo} "
                + "AND entity_no=#{scope.entityNo} AND hold_no=#{hold} AND unit_no=#{unit} AND version=#{version} "
                + "AND released_at IS NULL AND deleted=0 AND version<9223372036854775807")
        int releaseBookingUnit(@Param("scope") ResourceCommandScopeRow scope, @Param("hold") String hold,
                @Param("unit") String unit, @Param("version") long version, @Param("command") String command,
                @Param("actor") String actor, @Param("at") LocalDateTime at);
    }

    public interface MchEntityMapper extends BaseMapper<MchEntity> {
    }

    /**
     * 预约时段。<b>占位与释放只走这里的两条带条件 UPDATE</b>，不要用 Wrapper 或
     * {@code updateById} —— 那两条路都是「先查再改」，并发下必然超约。
     * 与库存锁定（{@code SkuMapper.lockStock}）是同一套手法。
     */
    public interface AppointmentSlotMapper
            extends BaseMapper<ai.neargo.shop.merchant.entity.MchAppointmentSlot> {

        /**
         * 抢一个名额。
         *
         * <p>条件全写在 WHERE 里：<b>时段属于这家店</b>、还没约满、可约、没被逻辑删。
         *
         * <p>{@code store_no} 这一条是**越权闸不是筛选**：时段编号由端上传，
         * 不比对归属的话，买家可以拿别家店的时段号来下单 ——
         * 占的是别人的名额，而那家店的师傅那天根本不知道有这一单。
         * <b>返回 0 不等于「满了」</b> —— 也可能是停约或这一行压根不存在，
         * 所以调用方要区分「行不存在/已停约」（80015）与「已约满」（80014）：
         * 前者要让买家换一个时段，后者要让他换一个时间，两句话不一样。
         *
         * @return 影响行数；1 = 抢到，0 = 没抢到
         */
        @Update("""
                UPDATE mch_appointment_slot SET booked = booked + 1, version = version + 1
                WHERE slot_no = #{slotNo} AND store_no = #{storeNo} AND deleted = 0
                  AND status = 'OPEN' AND booked < capacity
                """)
        int tryBook(@Param("slotNo") String slotNo, @Param("storeNo") String storeNo);

        /**
         * 还一个名额。
         *
         * <p>{@code booked > 0} 是防线不是装饰：少了它，一次重复释放就能把
         * booked 减成负数，此后这个时段能卖出比 capacity 更多的单，
         * <b>而且不会有任何报错</b>。与 {@code SkuMapper.releaseStock} 的
         * {@code locked_stock >= qty} 同一个位置、同一个理由。
         *
         * <p><b>停约的时段照样能还</b>（WHERE 里不判 status）：商家停约之后，
         * 那些已经约进来的单取消时还得把名额退回去，否则数字永远对不上。
         */
        @Update("""
                UPDATE mch_appointment_slot SET booked = booked - 1, version = version + 1
                WHERE slot_no = #{slotNo} AND deleted = 0 AND booked > 0
                """)
        int release(@Param("slotNo") String slotNo);
    }

    /** 商家覆盖的社区：C 端「本社区可见商家」的反查索引所在。 */
    public interface MchEntityCommunityMapper extends BaseMapper<MchEntityCommunity> {

        /**
         * 复活一条被逻辑删掉的覆盖关系。
         *
         * <p>与 {@code MchStoreRoleMapper.revive} 是同一个坑的同一种解法 ——
         * 那边的注释里就写着「这个坑在<b>商家社区表</b>、商品社区池上各踩过一次」，
         * 而商家社区表这一处一直没修。2026-08-11 的 E2E 把它撞出来了：
         * 商家把经营范围从「仅本社区（阳光花园）」改成「全市」再改回来，
         * 保存直接 500 —— {@code uk_entity_community(entity_no, community_no)}
         * <b>不含 deleted</b>，逻辑删掉的那行还占着索引位，insert 撞唯一键。
         *
         * <p>商家看到的是「系统开小差了，请稍后再试」，而他做的只是把范围改回去。
         *
         * <p>必须手写 SQL：MyBatis-Plus 的 {@code @TableLogic} 会给所有查询与更新
         * 自动追加 {@code deleted = 0}，用 Wrapper 根本够不到这一行。
         *
         * @return 影响行数；0 表示压根没有这一行（该走 insert）
         */
        @Update("""
                UPDATE mch_entity_community SET deleted = 0, version = version + 1
                WHERE entity_no = #{entityNo} AND community_no = #{communityNo} AND deleted = 1
                """)
        int revive(@Param("entityNo") String entityNo, @Param("communityNo") String communityNo);
    }

    /**
     * 商家地理覆盖项（ADR-013）。**物理删除**，不走 MyBatis-Plus 的逻辑删除 ——
     * 见 {@link ai.neargo.shop.merchant.entity.MchServiceArea} 的说明。
     */
    public interface ServiceAreaMapper extends BaseMapper<ai.neargo.shop.merchant.entity.MchServiceArea> {

        /**
         * 物理删掉一条覆盖项。
         *
         * <p>必须手写：{@code @TableLogic} 会把 {@code delete()} 变成
         * {@code UPDATE ... SET deleted = 1}，而那正是本仓库踩过四次的坑 ——
         * 墓碑行占着 {@code uk_service_area}，「移除之后又加回同一条」直接撞键。
         */
        @org.apache.ibatis.annotations.Delete("""
                DELETE FROM mch_service_area WHERE id = #{id}
                """)
        int hardDeleteById(@Param("id") Long id);

        /*
         * 原来是 hardDelete(entityNo, level, refCode)。经营范围改门店级（V381）之后
         * 同一条 (level, ref) 在每家门店各有一行 —— 按那三个键删会把**所有门店**的那一条
         * 一起删掉，正是这次要修的「改一家、全变」换个方向重演。按 id 删，只删手上这一行。
         */
    }

    /**
     * 门店送货方式（方案 v4）。物理删除同 {@link ServiceAreaMapper} ——
     * 但注意日常「关一路」是 {@code enabled=0} 不是删行，删行只发生在门店被删时。
     */
    public interface FulfillmentChannelMapper
            extends BaseMapper<ai.neargo.shop.merchant.entity.MchFulfillmentChannel> {
    }

    /** 自提路 × 取货点（P1 启用）。 */
    public interface ChannelPickupMapper
            extends BaseMapper<ai.neargo.shop.merchant.entity.MchChannelPickup> {

        /**
         * **物理删**这一店这一路的取货点引用。不用 {@code delete(wrapper)}：实体继承了带 deleted 的基类，
         * 全局逻辑删会把它改写成 UPDATE deleted=1 —— 留下墓碑行，而唯一键里没有 deleted，
         * 同一个点再存一次就撞键（2026-09-28 生产上商家原样再存一次送货方式即 500）。
         */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM mch_channel_pickup WHERE store_no = #{storeNo} AND channel = #{channel}")
        int purge(@Param("storeNo") String storeNo, @Param("channel") String channel);
    }

    /** SUBSET 收窄（P2 启用）。 */
    /**
     * 多边形范围的 S2 网格派生行（V396，ADR-034）。查询走 {@code cell_id IN (...)}（见 ReachMatchMapper），
     * 这里只管写入与级联清理。
     */
    public interface ServiceAreaCellMapper
            extends BaseMapper<ai.neargo.shop.merchant.entity.MchServiceAreaCell> {

        /**
         * **物理删**这些范围项的全部网格行。多边形项被删或几何改了（指纹变 = 新项）时同事务调用；
         * 不删的话旧网格还会让消费者命中一片已经不存在的范围。
         */
        @org.apache.ibatis.annotations.Delete({"<script>",
                "DELETE FROM mch_service_area_cell WHERE area_no IN",
                "<foreach collection='areaNos' item='n' open='(' separator=',' close=')'>#{n}</foreach>",
                "</script>"})
        int purgeByAreaNos(@Param("areaNos") java.util.Collection<String> areaNos);
    }

    public interface ChannelAreaMapper
            extends BaseMapper<ai.neargo.shop.merchant.entity.MchChannelArea> {

        /** **物理删**这一店这一路的范围子集引用。理由同 {@link ChannelPickupMapper#purge} */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM mch_channel_area WHERE store_no = #{storeNo} AND channel = #{channel}")
        int purge(@Param("storeNo") String storeNo, @Param("channel") String channel);

        /**
         * **物理删**引用了这些范围项的全部子集行（任何门店、任何一路）。
         * 主体删掉一条范围时同事务调用 —— 不删的话子集指向一个不存在的 area_no，
         * 商家界面上还显示「仅 1 项」，而那一项早就不在了。
         */
        @org.apache.ibatis.annotations.Delete({"<script>",
                "DELETE FROM mch_channel_area WHERE area_no IN",
                "<foreach collection='areaNos' item='a' open='(' separator=',' close=')'>#{a}</foreach>",
                "</script>"})
        int purgeAreas(@Param("areaNos") java.util.Collection<String> areaNos);
    }

    /** 商家资质。按 expire_at 扫到期，所以那一列有索引。 */
    public interface AdmissionPolicyMapper
            extends BaseMapper<ai.neargo.shop.merchant.entity.MchAdmissionPolicy> {
    }

    public interface DebtMapper extends BaseMapper<ai.neargo.shop.merchant.entity.MchDebt> {
    }

    public interface DebtTxnMapper extends BaseMapper<ai.neargo.shop.merchant.entity.MchDebtTxn> {
    }

    public interface DepositMapper extends BaseMapper<ai.neargo.shop.merchant.entity.MchDeposit> {
    }

    public interface DepositTxnMapper extends BaseMapper<ai.neargo.shop.merchant.entity.MchDepositTxn> {
    }

    public interface QualificationMapper extends BaseMapper<MchQualification> {
    }

    /** 门店（mch_store）。V44 起一主体可有多行。 */
    /**
     * 门店经营类目。**声明语义不是覆盖语义** —— 见
     * {@link ai.neargo.shop.merchant.entity.MchStoreCategory} 的类注释。
     */
    public interface MchStoreCategoryMapper
            extends BaseMapper<ai.neargo.shop.merchant.entity.MchStoreCategory> {

        /**
         * 把一条**被逻辑删除的**经营类目复活。
         *
         * <p>撤类目走的是逻辑删除（{@code deleted=1}），而唯一键
         * {@code uk_store_category (store_no, category_no)} <b>不含这一列</b> ——
         * 那一行还占着位置。所以「移出再加回来」不能 INSERT，会撞唯一键，
         * 端上只看到一句「系统开小差了」（2026-09-20 线上真实发生）。
         *
         * <p>不写成 {@code selectList} + {@code updateById}：逻辑删除的行选不出来
         * （MyBatis-Plus 自动加 {@code deleted=0}），绕开它要关全局配置。
         *
         * @return 影响行数；0 = 这家店压根没有过这个类目，调用方去 INSERT
         */
        @Update("""
                UPDATE mch_store_category
                   SET deleted = 0, enabled = 1, sort = #{sort}, display_name = #{displayName},
                       entity_no = #{entityNo}, updated_at = NOW(), version = version + 1
                 WHERE store_no = #{storeNo} AND category_no = #{categoryNo} AND deleted = 1
                """)
        int revive(@Param("storeNo") String storeNo, @Param("categoryNo") String categoryNo,
                   @Param("entityNo") String entityNo, @Param("displayName") String displayName,
                   @Param("sort") int sort);
    }

    public interface MchStoreMapper extends BaseMapper<MchStore> {
    }

    /** 商家子账号：账号 ↔ 主体的成员关系。**身份来源**（取代 mch_entity.owner_user_no）。 */
    public interface MchAccountMapper extends BaseMapper<MchAccount> {
        /** Personnel account linking and suspension serialize on the existing canonical membership row. */
        @Select("SELECT * FROM mch_account WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND mch_account_no=#{account} AND deleted=0 FOR UPDATE")
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchAccount lockPersonnelAccount(@Param("entity") String entity, @Param("account") String account);

    }

    /** 子账号在各门店的角色（每店一个角色）。 */
    public interface MchStoreRoleMapper extends BaseMapper<MchStoreRole> {

        /**
         * 复活一条被逻辑删的授权。
         *
         * <p><b>撤销授权是逻辑删，而 {@code uk_store_role} 不含 deleted 列</b> ——
         * 所以「撤销再授予同一个角色」时直接 insert 必然撞唯一键，
         * 表现为授权接口 500，而老板看到的只是「系统开小差」。
         *
         * <p>这个坑在商家社区表、商品社区池上各踩过一次，这是第三次 ——
         * <b>凡是「逻辑删 + 业务唯一键」的组合都有它</b>，
         * 而它只在「删了再加回来」这条路径上出现，日常测试很难走到。
         *
         * @return 影响行数；0 表示压根没有这一行（该走 insert）
         */
        @Update("""
                UPDATE mch_store_role SET deleted = 0, version = version + 1
                WHERE mch_account_no = #{accountNo} AND store_no = #{storeNo}
                  AND role = #{role} AND deleted = 1
                """)
        int revive(@Param("accountNo") String accountNo, @Param("storeNo") String storeNo,
                   @Param("role") String role);
    }

    /**
     * 商家角色（V71）：6 个预置（{@code entity_no='*'}）+ 商家自定义。
     *
     * <p>⚠️ 查询一律 {@code entity_no IN (当前商家, '*')} —— 少了 `'*'` 预置角色全没，
     * 少了当前商家自定义角色全没，两种漏法都表现为「权限突然变少」。
     */
    public interface MchRoleMapper extends BaseMapper<ai.neargo.shop.merchant.entity.MchRole> {
    }

    /**
     * 员工与授权的操作日志（B-11.10.3）。**只写不改** ——
     * 审计记录被更新过就不再是审计记录。
     */
    public interface MchStaffLogMapper
            extends BaseMapper<ai.neargo.shop.merchant.entity.MchStaffLog> {
    }

    /** 商家支付进件：每通道一条。分账回调只带 sub_mchid，靠 idx_mp_sub_mchid 反查商家。 */
    public interface MchPaymentMapper extends BaseMapper<MchPaymentMerchant> {
    }

    /** 类目授权码主数据。 */
    public interface SysAuthCodeMapper extends BaseMapper<ai.neargo.shop.merchant.entity.SysAuthCode> {
    }

    /** 违规与处置记录。 */
    public interface ViolationMapper extends BaseMapper<ai.neargo.shop.merchant.entity.MchViolation> {
    }

    /** 店招与公告的人审队列。 */
    public interface StoreAuditMapper extends BaseMapper<ai.neargo.shop.merchant.entity.MchStoreAudit> {
    }

    /** 增值包档位定义（平台配置，运营可调）。 */
    public interface PlanDefMapper extends BaseMapper<ai.neargo.shop.merchant.entity.SysMerchantPlanDef> {
    }

    /** 主体的增值包订阅（一主体一行）。 */
    public interface EntityPlanMapper extends BaseMapper<ai.neargo.shop.merchant.entity.MchEntityPlan> {
    }

    /**
     * 供应商收款账户（V358，ADR-011）。
     *
     * <p>只做单表 CRUD —— 「同一主体只能有一个 ACTIVE」那条约束在 Service，
     * 不在这里：它要跨行判断并改另一行的状态，放 Mapper 就绕过了状态机。
     */
    public interface PayoutAccountMapper extends BaseMapper<MchPayoutAccount> {
    }


    /** Shared personnel master; login accounts are optional independent associations. */
    public interface PersonnelMapper extends BaseMapper<MchPersonnel> {
        /** Parent CAS serializes every employment, account and status maintenance command. */
        @Update("UPDATE mch_personnel SET status=#{status},version=version+1,updated_at=#{at},updated_by=#{actor} "
                + "WHERE tenant_no='MAIN' AND entity_no=#{entity} AND personnel_no=#{personnel} "
                + "AND version=#{expected} AND version<9223372036854775807 AND deleted=0")
        int advanceMaintenance(@Param("entity") String entity, @Param("personnel") String personnel,
                @Param("expected") long expected, @Param("status") String status, @Param("actor") String actor,
                @Param("at") LocalDateTime at);

        /** Conservatively block maintenance until every unresolved command in the subject has recovered. */
        @Select("SELECT COUNT(*) FROM mch_resource_command WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND status='ACCEPTED' AND deleted=0")
        long countPendingMaintenance(@Param("entity") String entity);

        /** Counter evidence and actual unreleased units both block; broken orphan evidence also fails closed. */
        @Select("""
                SELECT (SELECT COUNT(*) FROM mch_resource_bucket b WHERE b.tenant_no='MAIN'
                    AND b.entity_no=#{entity} AND b.resource_no=#{resource}
                    AND (b.held_qty>0 OR b.confirmed_qty>0 OR EXISTS
                    (SELECT 1 FROM mch_booking_hold_unit u WHERE u.tenant_no=b.tenant_no
                     AND u.entity_no=b.entity_no AND u.bucket_no=b.bucket_no AND u.released_at IS NULL)))
                + (SELECT COUNT(*) FROM mch_booking_hold_unit u LEFT JOIN mch_resource_bucket b
                    ON b.tenant_no=u.tenant_no AND b.entity_no=u.entity_no AND b.bucket_no=u.bucket_no
                    WHERE u.tenant_no='MAIN' AND u.entity_no=#{entity} AND u.released_at IS NULL AND b.id IS NULL)
                """)
        long countResourceWork(@Param("entity") String entity, @Param("resource") String resource);

        /** Resolve database-canonical personnel before sorting roots; do not emulate collation in Java. */
        @Select("""
                SELECT * FROM mch_personnel WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND personnel_no=#{personnelNo} AND deleted=0
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchPersonnel selectScopedPersonnel(@Param("scope") ResourceCommandScopeRow scope,
                @Param("personnelNo") String personnelNo);

        /** Qualification/employment writers must take this root before any resource root. */
        @Select("""
                SELECT * FROM mch_personnel WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND personnel_no=#{personnelNo} AND deleted=0 FOR UPDATE
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchPersonnel lockScopedPersonnel(@Param("scope") ResourceCommandScopeRow scope,
                @Param("personnelNo") String personnelNo);

    }

    /** Current optional account link; mutations require a future owner workflow and audit. */
    public interface PersonnelAccountMapper extends BaseMapper<MchPersonnelAccount> {
    }

    /** Bounded effective employment projection; overlapping rows remain visible for explicit ambiguity rejection. */
    public interface PersonnelStoreMapper extends BaseMapper<MchPersonnelStore> {
        /** Canonical parent identity and all effective employments let the owner reject ambiguity. */
        @Select("""
                <script>
                SELECT a.assignment_no, p.personnel_no, p.name AS display_name, a.level_code,
                a.public_visible, a.sort_order, a.valid_from, a.valid_to
                FROM mch_personnel_store a
                JOIN mch_personnel p ON p.tenant_no=a.tenant_no AND p.entity_no=a.entity_no
                AND p.personnel_no=a.personnel_no
                JOIN mch_store s ON s.tenant_no=a.tenant_no AND s.entity_no=a.entity_no AND s.store_no=a.store_no
                JOIN mch_entity e ON e.tenant_no=a.tenant_no AND e.entity_no=a.entity_no
                WHERE a.tenant_no=#{tenantNo} AND a.entity_no=#{entityNo} AND a.store_no=#{storeNo}
                AND a.deleted=0 AND p.deleted=0 AND s.deleted=0 AND e.deleted=0
                AND a.status='ACTIVE' AND p.status='ACTIVE' AND s.status='ACTIVE' AND e.status='ACTIVE'
                AND e.archived_at IS NULL AND a.valid_from &lt;= #{at} AND (a.valid_to IS NULL OR a.valid_to>#{at})
                AND a.personnel_no IN
                <foreach collection="personnelNos" item="person" open="(" separator="," close=")">#{person}</foreach>
                ORDER BY a.sort_order, a.personnel_no, a.assignment_no LIMIT 201
                </script>
                """)
        List<PersonnelAssignmentFactRow> selectEffectiveAssignments(@Param("tenantNo") String tenantNo,
                @Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("personnelNos") Set<String> personnelNos, @Param("at") LocalDateTime at);
    }

    /** Batch positions only for the already authorized employment rows in the same snapshot. */
    public interface PersonnelPositionMapper extends BaseMapper<MchPersonnelPosition> {
        /** Read only authorized assignment IDs; the parent supplies the stable grouping identity. */
        @Select("""
                <script>
                SELECT a.assignment_no, p.position_code FROM mch_personnel_position p
                JOIN mch_personnel_store a ON a.tenant_no=p.tenant_no AND a.entity_no=p.entity_no
                AND a.assignment_no=p.assignment_no
                WHERE p.tenant_no=#{tenantNo} AND p.entity_no=#{entityNo} AND a.store_no=#{storeNo}
                AND p.deleted=0 AND a.deleted=0 AND p.status='ACTIVE' AND a.status='ACTIVE'
                AND p.assignment_no IN
                <foreach collection="assignmentNos" item="assignment" open="(" separator="," close=")">
                #{assignment}</foreach>
                ORDER BY p.assignment_no, p.position_code LIMIT 2001
                </script>
                """)
        List<PersonnelPositionFactRow> selectByAssignments(@Param("tenantNo") String tenantNo,
                @Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("assignmentNos") List<String> assignmentNos);
    }

    /** Verified effective qualification facts carry their original store scope; no permission inference. */
    public interface PersonnelSkillMapper extends BaseMapper<MchPersonnelSkill> {
        /** Whole rounded bucket eligibility; SKU-specific facts cannot authorize an unspecified SKU. */
        @Select("""
                SELECT COUNT(*) FROM mch_personnel_skill
                WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo} AND personnel_no=#{personnelNo}
                  AND deleted=0 AND status='VERIFIED' AND (store_no IS NULL OR store_no=#{scope.storeNo})
                  AND goods_no=#{goodsNo} AND (sku_no IS NULL OR (#{skuNo} IS NOT NULL AND sku_no=#{skuNo}))
                  AND valid_from<=#{startAt} AND (valid_to IS NULL OR valid_to>=#{endAt})
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        long countCoveringQualification(@Param("scope") ResourceCommandScopeRow scope,
                @Param("personnelNo") String personnelNo, @Param("goodsNo") String goodsNo,
                @Param("skuNo") String skuNo, @Param("startAt") LocalDateTime startAt,
                @Param("endAt") LocalDateTime endAt);

        /** Resolve child references through the master identity under the same owner snapshot. */
        @Select("""
                <script>
                SELECT p.personnel_no, k.skill_no, k.store_no, k.goods_no, k.sku_no, k.level_code,
                k.valid_from, k.valid_to FROM mch_personnel_skill k
                JOIN mch_personnel p ON p.tenant_no=k.tenant_no AND p.entity_no=k.entity_no
                AND p.personnel_no=k.personnel_no
                WHERE k.tenant_no=#{tenantNo} AND k.entity_no=#{entityNo} AND k.deleted=0 AND k.status='VERIFIED'
                AND p.deleted=0 AND p.status='ACTIVE'
                AND (k.store_no IS NULL OR k.store_no=#{storeNo})
                AND k.valid_from &lt;= #{at} AND (k.valid_to IS NULL OR k.valid_to>#{at})
                AND k.personnel_no IN
                <foreach collection="personnelNos" item="person" open="(" separator="," close=")">#{person}</foreach>
                ORDER BY k.personnel_no, k.skill_no LIMIT 2001
                </script>
                """)
        List<PersonnelQualificationFactRow> selectEffectiveByPersonnel(@Param("tenantNo") String tenantNo,
                @Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("personnelNos") Set<String> personnelNos, @Param("at") LocalDateTime at);
    }

    /** Canonical resources; a staff resource is shared by all valid store assignments of one personnel. */
    public interface ResourceMapper extends BaseMapper<MchResource> {
        /** Any active local rule must be explicitly retired before employment can activate clipped occurrences. */
        @Select("SELECT COUNT(*) FROM mch_schedule_rule WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND resource_no=#{resource} AND store_no=#{store} AND status='ACTIVE' AND deleted=0")
        long countStoreScheduleRules(@Param("entity") String entity, @Param("store") String store,
                @Param("resource") String resource);

        /** Schedule and physical maintenance share one root revision, including canonical cross-store STAFF. */
        @Update("UPDATE mch_resource SET version=version+1,updated_by=#{actor},updated_at=#{at} "
                + "WHERE tenant_no='MAIN' AND entity_no=#{entity} AND resource_no=#{resource} "
                + "AND version=#{expected} AND version<9223372036854775807 AND deleted=0")
        int advanceScheduleRevision(@Param("entity") String entity, @Param("resource") String resource,
                @Param("expected") long expected, @Param("actor") String actor, @Param("at") LocalDateTime at);

        /** Historical bucket capacities are immutable until a versioned capacity-change protocol exists. */
        @Select("SELECT COUNT(*) FROM mch_resource_bucket WHERE tenant_no='MAIN' "
                + "AND entity_no=#{entity} AND resource_no=#{resource}")
        long countMaintenanceBuckets(@Param("entity") String entity, @Param("resource") String resource);

        /** Physical resource CAS cannot rewrite canonical identity, store or occupation credentials. */
        @Update("UPDATE mch_resource SET name=#{name},capacity=#{capacity},status=#{status},version=version+1,"
                + "updated_by=#{actor},updated_at=#{at} WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND resource_no=#{resource} AND store_no=#{store} AND resource_type IN ('ROOM','SEAT','DEVICE') "
                + "AND version=#{expected} AND version<9223372036854775807 AND deleted=0")
        int saveMaintenance(@Param("entity") String entity, @Param("store") String store,
                @Param("resource") String resource, @Param("expected") long expected, @Param("name") String name,
                @Param("capacity") int capacity, @Param("status") String status, @Param("actor") String actor,
                @Param("at") LocalDateTime at);

        /** Physical resources are store-scoped while staff identity and capacity remain entity-wide. */
        @Select("""
                SELECT * FROM mch_resource WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND resource_no=#{resourceNo} AND deleted=0
                  AND (resource_type='STAFF' OR store_no=#{scope.storeNo})
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchResource selectScopedResource(@Param("scope") ResourceCommandScopeRow scope,
                @Param("resourceNo") String resourceNo);

        /** Individually acquire canonical sorted roots; ORDER BY on an IN query cannot guarantee lock order. */
        @Select("""
                SELECT * FROM mch_resource WHERE tenant_no=#{scope.tenantNo} AND entity_no=#{scope.entityNo}
                  AND resource_no=#{resourceNo} AND deleted=0
                  AND (resource_type='STAFF' OR store_no=#{scope.storeNo}) FOR UPDATE
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchResource lockScopedResource(@Param("scope") ResourceCommandScopeRow scope,
                @Param("resourceNo") String resourceNo);

        /** Keep joined employment rows to reject overlaps rather than DISTINCT away inconsistent staffing. */
        @Select("""
                <script>
                SELECT r.resource_no,
                CASE WHEN r.resource_type='STAFF' THEN 'STAFF' WHEN r.resource_type='ROOM' THEN 'ROOM'
                WHEN r.resource_type='SEAT' THEN 'SEAT' WHEN r.resource_type='DEVICE' THEN 'DEVICE'
                ELSE r.resource_type END AS resource_type,
                CASE WHEN r.resource_type='STAFF' THEN NULL ELSE s.store_no END AS store_no,
                p.personnel_no, r.name, r.capacity, (r.open_occupation_no IS NOT NULL) AS open_occupied
                FROM mch_resource r
                JOIN mch_entity e ON e.tenant_no=r.tenant_no AND e.entity_no=r.entity_no
                JOIN mch_store s ON s.tenant_no=r.tenant_no AND s.entity_no=r.entity_no AND s.store_no=#{storeNo}
                LEFT JOIN mch_personnel p ON r.resource_type='STAFF' AND p.tenant_no=r.tenant_no
                AND p.entity_no=r.entity_no AND p.personnel_no=r.personnel_no AND p.deleted=0 AND p.status='ACTIVE'
                LEFT JOIN mch_personnel_store a ON r.resource_type='STAFF' AND a.tenant_no=p.tenant_no
                AND a.entity_no=p.entity_no AND a.personnel_no=p.personnel_no AND a.store_no=s.store_no
                AND a.deleted=0 AND a.status='ACTIVE' AND a.valid_from &lt;= #{at}
                AND (a.valid_to IS NULL OR a.valid_to>#{at})
                WHERE r.tenant_no=#{tenantNo} AND r.entity_no=#{entityNo} AND r.deleted=0 AND r.status='ACTIVE'
                AND e.deleted=0 AND e.status='ACTIVE' AND e.archived_at IS NULL AND s.deleted=0 AND s.status='ACTIVE'
                AND ((r.resource_type='STAFF' AND a.assignment_no IS NOT NULL)
                OR (r.resource_type IN ('ROOM','SEAT','DEVICE') AND r.store_no=s.store_no))
                AND r.resource_no IN
                <foreach collection="resourceNos" item="resource" open="(" separator="," close=")">
                #{resource}</foreach>
                ORDER BY r.resource_no, a.assignment_no LIMIT 201
                </script>
                """)
        List<ResourceFactRow> selectEffectiveFacts(@Param("tenantNo") String tenantNo,
                @Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("resourceNos") Set<String> resourceNos, @Param("at") LocalDateTime at);
    }


    /** Weekly rules retain all related store assignments for explicit shared-resource conflict checks. */
    public interface ScheduleRuleMapper extends BaseMapper<MchScheduleRule> {
        /** One scoped snapshot; conservative dates precede exact time-zone expansion and employment clipping. */
        @Select("""
                <script>
                SELECT w.rule_no, r.resource_no, s.store_no, q.store_no AS requested_store_no, w.timezone,
                w.weekday_mask, w.start_minute, w.end_minute, w.end_day_offset, w.valid_from, w.valid_to,
                (r.resource_type='STAFF') AS staff, a.assignment_no,
                a.valid_from AS employment_from, a.valid_to AS employment_to
                FROM mch_schedule_rule w
                JOIN mch_resource r ON r.tenant_no=w.tenant_no AND r.entity_no=w.entity_no
                AND r.resource_no=w.resource_no
                JOIN mch_entity e ON e.tenant_no=r.tenant_no AND e.entity_no=r.entity_no
                JOIN mch_store s ON s.tenant_no=w.tenant_no AND s.entity_no=w.entity_no AND s.store_no=w.store_no
                JOIN mch_store q ON q.tenant_no=r.tenant_no AND q.entity_no=r.entity_no AND q.store_no=#{storeNo}
                LEFT JOIN mch_personnel p ON r.resource_type='STAFF' AND p.tenant_no=r.tenant_no
                AND p.entity_no=r.entity_no AND p.personnel_no=r.personnel_no AND p.deleted=0 AND p.status='ACTIVE'
                LEFT JOIN mch_personnel_store a ON r.resource_type='STAFF' AND a.tenant_no=p.tenant_no
                AND a.entity_no=p.entity_no AND a.personnel_no=p.personnel_no AND a.store_no=s.store_no
                AND a.deleted=0 AND a.status='ACTIVE' AND a.valid_from &lt; #{until}
                AND (a.valid_to IS NULL OR a.valid_to>#{from})
                WHERE w.tenant_no=#{tenantNo} AND w.entity_no=#{entityNo} AND w.deleted=0 AND w.status='ACTIVE'
                AND r.deleted=0 AND r.status='ACTIVE' AND e.deleted=0 AND e.status='ACTIVE' AND e.archived_at IS NULL
                AND s.deleted=0 AND s.status='ACTIVE' AND q.deleted=0 AND q.status='ACTIVE'
                AND ((r.resource_type='STAFF' AND a.assignment_no IS NOT NULL)
                OR (r.resource_type IN ('ROOM','SEAT','DEVICE') AND r.store_no=s.store_no AND s.store_no=q.store_no))
                AND w.valid_from &lt;= #{lastAnchor} AND (w.valid_to IS NULL OR w.valid_to>=#{firstAnchor})
                AND r.resource_no IN
                <foreach collection="resourceNos" item="resource" open="(" separator="," close=")">
                #{resource}</foreach>
                ORDER BY r.resource_no, w.rule_no, a.assignment_no LIMIT 2001
                </script>
                """)
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        List<ScheduleRuleFactRow> selectWindowRules(@Param("tenantNo") String tenantNo,
                @Param("entityNo") String entityNo, @Param("storeNo") String storeNo,
                @Param("resourceNos") Set<String> resourceNos, @Param("from") LocalDateTime from,
                @Param("until") LocalDateTime until, @Param("firstAnchor") LocalDate firstAnchor,
                @Param("lastAnchor") LocalDate lastAnchor);
    }

    /** Future owner workflows append change evidence; it does not authorize or execute affected booking changes. */
    public interface ScheduleChangeLogMapper extends BaseMapper<MchScheduleChangeLog> {
    }

}
