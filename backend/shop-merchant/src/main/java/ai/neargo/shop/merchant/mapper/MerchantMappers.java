package ai.neargo.shop.merchant.mapper;

import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.entity.MchQualification;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.entity.MchStoreRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * merchant 域的 Mapper 集合（嵌套接口，沿用 powerbank 的写法）。
 * Mapper 只做单表 CRUD 与条件组合，跨表聚合放 Service —— 一旦 Mapper 里出现业务分支，
 * 数据域拦截器与状态机就会被绕过。
 *
 * <p>原先这六个接口与 user 的四个同住 {@code UserMappers}。合在一起的直接后果是：
 * 任何拿到 {@code UserMappers} 的类都顺手能读写商家表，边界靠自觉——
 * 事实上 {@code CommunityServiceImpl} 与 {@code StoreFavoriteServiceImpl} 都这么做了。
 */
public final class MerchantMappers {

    private MerchantMappers() {
    }

    public interface MchEntityMapper extends BaseMapper<MchEntity> {
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

    /** 商家资质。按 expire_at 扫到期，所以那一列有索引。 */
    public interface AdmissionPolicyMapper
            extends BaseMapper<ai.neargo.shop.merchant.entity.MchAdmissionPolicy> {
    }

    public interface DepositMapper extends BaseMapper<ai.neargo.shop.merchant.entity.MchDeposit> {
    }

    public interface DepositTxnMapper extends BaseMapper<ai.neargo.shop.merchant.entity.MchDepositTxn> {
    }

    public interface QualificationMapper extends BaseMapper<MchQualification> {
    }

    /** 门店（mch_store）。V44 起一主体可有多行。 */
    public interface MchStoreMapper extends BaseMapper<MchStore> {
    }

    /** 商家子账号：账号 ↔ 主体的成员关系。**身份来源**（取代 mch_entity.owner_user_no）。 */
    public interface MchAccountMapper extends BaseMapper<MchAccount> {
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
}
