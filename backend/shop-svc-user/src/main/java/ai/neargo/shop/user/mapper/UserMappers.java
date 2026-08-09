package ai.neargo.shop.user.mapper;

import ai.neargo.shop.user.community.entity.CmtCommunity;
import ai.neargo.shop.user.merchant.entity.MchPaymentMerchant;
import ai.neargo.shop.user.merchant.entity.MchEntityCommunity;
import ai.neargo.shop.user.community.entity.CmtPickupPoint;
import ai.neargo.shop.user.entity.UsrAddress;
import ai.neargo.shop.user.merchant.entity.MchEntity;
import ai.neargo.shop.user.entity.UsrStoreFavorite;
import ai.neargo.shop.user.entity.UsrAccount;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * user 域的 Mapper 集合（嵌套接口，沿用 powerbank 的写法）。
 * Mapper 只做单表 CRUD 与条件组合，跨表聚合放 Service —— 一旦 Mapper 里出现业务分支，
 * 数据域拦截器与状态机就会被绕过。
 */
public final class UserMappers {

    private UserMappers() {
    }

    public interface UserMapper extends BaseMapper<UsrAccount> {
    }

    public interface MchEntityMapper extends BaseMapper<MchEntity> {
    }

    public interface CommunityMapper extends BaseMapper<CmtCommunity> {
    }

    public interface PickupPointMapper extends BaseMapper<CmtPickupPoint> {
    }

    public interface AddressMapper extends BaseMapper<UsrAddress> {
    }

    public interface StoreFavoriteMapper extends BaseMapper<UsrStoreFavorite> {
    }

    /** 商家覆盖的社区：C 端「本社区可见商家」的反查索引所在。 */
    public interface MchEntityCommunityMapper extends BaseMapper<MchEntityCommunity> {
    }

    /** 门店（mch_store）。V44 起一主体可有多行。 */
    public interface MchStoreMapper extends BaseMapper<ai.neargo.shop.user.merchant.entity.MchStore> {
    }

    /** 商家子账号：账号 ↔ 主体的成员关系。**身份来源**（取代 mch_entity.owner_user_no）。 */
    public interface MchAccountMapper extends BaseMapper<ai.neargo.shop.user.merchant.entity.MchAccount> {
    }

    /** 子账号在各门店的角色（每店一个角色）。 */
    public interface MchStoreRoleMapper extends BaseMapper<ai.neargo.shop.user.merchant.entity.MchStoreRole> {
    }

    /** 商家支付进件：每通道一条。分账回调只带 sub_mchid，靠 idx_mp_sub_mchid 反查商家。 */
    public interface MchPaymentMapper extends BaseMapper<MchPaymentMerchant> {
    }


}
