package ai.neargo.shop.merchant.mapper;

import ai.neargo.shop.common.config.PlatformSqlMapper;
import ai.neargo.shop.common.config.PlatformTableMapper;
import ai.neargo.shop.common.config.TradeTableMapper;
import ai.neargo.shop.merchant.dto.OperatorAccessRow;
import ai.neargo.shop.merchant.dto.OperatorPersonnelFactRow;
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
import ai.neargo.shop.merchant.booking.entity.MchBookingOwner;
import ai.neargo.shop.merchant.booking.entity.MchResourceOccupation;
import ai.neargo.shop.merchant.booking.entity.MchResourceOccupationUnit;
import ai.neargo.shop.merchant.booking.entity.MchResourceOccupationSegment;
import ai.neargo.shop.merchant.booking.entity.MchResourceOccupationBucket;
import ai.neargo.shop.merchant.booking.entity.MchResourceCommand;
import ai.neargo.shop.merchant.booking.entity.MchResourceBucket;
import ai.neargo.shop.merchant.booking.entity.MchBookingHold;
import ai.neargo.shop.merchant.booking.entity.MchBookingHoldUnit;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.paymerchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.paymerchant.entity.MchPayoutAccount;
import ai.neargo.shop.merchant.entity.MchPersonnel;
import ai.neargo.shop.merchant.entity.MchPersonnelAccount;
import ai.neargo.shop.merchant.entity.MchPersonnelPosition;
import ai.neargo.shop.merchant.booking.entity.MchPersonnelSkill;
import ai.neargo.shop.merchant.entity.MchPersonnelStore;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.booking.entity.MchResource;
import ai.neargo.shop.merchant.booking.entity.MchScheduleChangeLog;
import ai.neargo.shop.merchant.booking.entity.MchScheduleRule;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.entity.MchStoreCategory;
import ai.neargo.shop.merchant.entity.MchStoreRole;
import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
    public interface OperatorAccessMapper extends PlatformSqlMapper {
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








    public interface MchEntityMapper extends PlatformTableMapper<MchEntity> {
        /** Financial history remains scoped but readable after soft deletion; MP data-scope still applies. */
        @org.apache.ibatis.annotations.Select("SELECT entity_no FROM mch_entity WHERE tenant_no='MAIN' AND entity_no=#{entityNo}")
        String financialVisibleEntity(@org.apache.ibatis.annotations.Param("entityNo") String entityNo);
    }


    /** 商家覆盖的社区：C 端「本社区可见商家」的反查索引所在。 */
    public interface MchEntityCommunityMapper extends PlatformTableMapper<MchEntityCommunity> {

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
    public interface ServiceAreaMapper extends PlatformTableMapper<ai.neargo.shop.merchant.entity.MchServiceArea> {

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
            extends PlatformTableMapper<ai.neargo.shop.merchant.entity.MchFulfillmentChannel> {
    }

    /** 自提路 × 取货点（P1 启用）。 */
    public interface ChannelPickupMapper
            extends PlatformTableMapper<ai.neargo.shop.merchant.entity.MchChannelPickup> {

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
            extends PlatformTableMapper<ai.neargo.shop.merchant.entity.MchServiceAreaCell> {

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
            extends PlatformTableMapper<ai.neargo.shop.merchant.entity.MchChannelArea> {

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

    public interface QualificationMapper extends PlatformTableMapper<MchQualification> {
    }

    /** 门店（mch_store）。V44 起一主体可有多行。 */
    /**
     * 门店经营类目。**声明语义不是覆盖语义** —— 见
     * {@link ai.neargo.shop.merchant.entity.MchStoreCategory} 的类注释。
     */
    public interface MchStoreCategoryMapper
            extends TradeTableMapper<MchStoreCategory> {
        /** 经营货架只取门店通用且映射到平台类目的行，目录专用分类由目录服务管理。 */
        static LambdaQueryWrapper<MchStoreCategory> shelves(String storeNo) {
            return Wrappers.<MchStoreCategory>lambdaQuery()
                    .eq(MchStoreCategory::getTenantNo, "MAIN")
                    .eq(MchStoreCategory::getStoreNo, storeNo)
                    .and(q -> q.isNull(MchStoreCategory::getCatalogNo).or().eq(MchStoreCategory::getCatalogNo, ""))
                    .ne(MchStoreCategory::getCategoryNo, "");
        }

        /** 普通快照读先定位旧行，避免对不存在的货架先做 UPDATE 而锁住索引间隙。 */
        @Select("""
                SELECT id FROM prd_store_category
                 WHERE tenant_no='MAIN' AND store_no=#{storeNo} AND entity_no=#{entityNo}
                   AND platform_category_no=#{categoryNo} AND (catalog_no IS NULL OR catalog_no='')
                   AND deleted=1
                """)
        Long removedShelf(@Param("storeNo") String storeNo, @Param("categoryNo") String categoryNo,
                          @Param("entityNo") String entityNo);

        /**
         * 把一条**被逻辑删除的**经营类目复活。
         *
         * <p>撤类目走的是逻辑删除（{@code deleted=1}），而唯一键
         * {@code uk_food_1 (category_no)} <b>不含这一列</b> ——
         * 那一行还占着位置。所以「移出再加回来」不能 INSERT，会撞唯一键，
         * 端上只看到一句「系统开小差了」（2026-09-20 线上真实发生）。
         *
         * <p>不写成 {@code selectList} + {@code updateById}：逻辑删除的行选不出来
         * （MyBatis-Plus 自动加 {@code deleted=0}），绕开它要关全局配置。
         *
         * @return 影响行数；0 = 这家店压根没有过这个类目，调用方去 INSERT
         */
        @Update("""
                UPDATE prd_store_category
                   SET deleted = 0, enabled = 1, sort_no = #{sort}, name = #{displayName},
                       updated_at = NOW(), version = version + 1
                 WHERE id = #{id} AND tenant_no='MAIN' AND store_no = #{storeNo} AND entity_no = #{entityNo}
                   AND platform_category_no = #{categoryNo}
                   AND (catalog_no IS NULL OR catalog_no = '') AND deleted = 1
                """)
        int revive(@Param("id") long id, @Param("storeNo") String storeNo, @Param("categoryNo") String categoryNo,
                   @Param("entityNo") String entityNo, @Param("displayName") String displayName,
                   @Param("sort") int sort);
    }

    public interface MchStoreMapper extends PlatformTableMapper<MchStore> {
    }


    /** 商家子账号：账号 ↔ 主体的成员关系。**身份来源**（取代 mch_entity.owner_user_no）。 */
    public interface MchAccountMapper extends PlatformTableMapper<MchAccount> {
        /** Personnel account linking and suspension serialize on the existing canonical membership row. */
        @Select("SELECT * FROM mch_account WHERE tenant_no='MAIN' AND entity_no=#{entity} "
                + "AND mch_account_no=#{account} AND deleted=0 FOR UPDATE")
        @Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
        MchAccount lockPersonnelAccount(@Param("entity") String entity, @Param("account") String account);

    }

    /** 子账号在各门店的角色（每店一个角色）。 */
    public interface MchStoreRoleMapper extends PlatformTableMapper<MchStoreRole> {

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
    public interface MchRoleMapper extends PlatformTableMapper<ai.neargo.shop.merchant.entity.MchRole> {
    }

    /**
     * 员工与授权的操作日志（B-11.10.3）。**只写不改** ——
     * 审计记录被更新过就不再是审计记录。
     */
    public interface MchStaffLogMapper
            extends BaseMapper<ai.neargo.shop.merchant.entity.MchStaffLog> {
    }




    /** 店招与公告的人审队列。 */
    public interface StoreAuditMapper extends BaseMapper<ai.neargo.shop.merchant.entity.MchStoreAudit> {
    }





    /** Shared personnel master; login accounts are optional independent associations. */
    public interface PersonnelMapper extends PlatformTableMapper<MchPersonnel> {
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
                     AND u.entity_no=b.entity_no AND u.bucket_no=b.bucket_no AND u.released_at IS NULL AND u.consumed_at IS NULL)))
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
    public interface PersonnelAccountMapper extends PlatformTableMapper<MchPersonnelAccount> {
        /** Effective joins preserve every candidate so overlapping assignments fail closed at the owner. */
        @Select(
                """
SELECT p.personnel_no,a.assignment_no,p.version AS personnel_revision,
  a.version AS assignment_revision,l.version AS link_revision,a.valid_from,a.valid_to,l.link_no
FROM mch_personnel_account l
JOIN mch_personnel p ON CAST(p.tenant_no AS BINARY)=CAST(l.tenant_no AS BINARY)
  AND CAST(p.entity_no AS BINARY)=CAST(l.entity_no AS BINARY)
  AND CAST(p.personnel_no AS BINARY)=CAST(l.personnel_no AS BINARY)
JOIN mch_personnel_store a ON CAST(a.tenant_no AS BINARY)=CAST(p.tenant_no AS BINARY)
  AND CAST(a.entity_no AS BINARY)=CAST(p.entity_no AS BINARY)
  AND CAST(a.personnel_no AS BINARY)=CAST(p.personnel_no AS BINARY)
JOIN mch_store s ON CAST(s.tenant_no AS BINARY)=CAST(a.tenant_no AS BINARY)
  AND CAST(s.entity_no AS BINARY)=CAST(a.entity_no AS BINARY)
  AND CAST(s.store_no AS BINARY)=CAST(a.store_no AS BINARY)
JOIN mch_entity e ON CAST(e.tenant_no AS BINARY)=CAST(s.tenant_no AS BINARY)
  AND CAST(e.entity_no AS BINARY)=CAST(s.entity_no AS BINARY)
WHERE CAST(l.tenant_no AS BINARY)=CAST(#{tenantNo} AS BINARY)
  AND CAST(l.entity_no AS BINARY)=CAST(#{entityNo} AS BINARY)
  AND CAST(s.store_no AS BINARY)=CAST(#{storeNo} AS BINARY)
  AND CAST(l.mch_account_no AS BINARY)=CAST(#{accountNo} AS BINARY)
  AND l.deleted=0 AND p.deleted=0 AND a.deleted=0 AND s.deleted=0 AND e.deleted=0
  AND p.status='ACTIVE' AND a.status='ACTIVE' AND s.status='ACTIVE' AND e.status='ACTIVE'
  AND e.archived_at IS NULL AND a.valid_from<=#{at}
  AND (a.valid_to IS NULL OR #{at}<a.valid_to)
LIMIT 2
""")
        List<OperatorPersonnelFactRow> selectCurrentOperatorPersonnel(
                @Param("tenantNo") String tenantNo,
                @Param("entityNo") String entityNo,
                @Param("storeNo") String storeNo,
                @Param("accountNo") String accountNo,
                @Param("at") LocalDateTime at);
    }

    /** Bounded effective employment projection; overlapping rows remain visible for explicit ambiguity rejection. */
    public interface PersonnelStoreMapper extends PlatformTableMapper<MchPersonnelStore> {
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
    public interface PersonnelPositionMapper extends PlatformTableMapper<MchPersonnelPosition> {
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






}
