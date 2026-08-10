package ai.neargo.shop.merchant.mapper;

import ai.neargo.shop.merchant.entity.MchAccount;
import ai.neargo.shop.merchant.entity.MchEntity;
import ai.neargo.shop.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.entity.MchStoreRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

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
    }

    /** 门店（mch_store）。V44 起一主体可有多行。 */
    public interface MchStoreMapper extends BaseMapper<MchStore> {
    }

    /** 商家子账号：账号 ↔ 主体的成员关系。**身份来源**（取代 mch_entity.owner_user_no）。 */
    public interface MchAccountMapper extends BaseMapper<MchAccount> {
    }

    /** 子账号在各门店的角色（每店一个角色）。 */
    public interface MchStoreRoleMapper extends BaseMapper<MchStoreRole> {
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
}
