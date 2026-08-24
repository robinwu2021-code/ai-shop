package ai.neargo.shop.user.mapper;

import ai.neargo.shop.user.entity.UsrAccount;
import ai.neargo.shop.user.entity.UsrAddress;
import ai.neargo.shop.user.entity.UsrIdentity;
import ai.neargo.shop.user.entity.UsrStoreFavorite;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * user 域的 Mapper 集合（嵌套接口，沿用 powerbank 的写法）。
 * Mapper 只做单表 CRUD 与条件组合，跨表聚合放 Service —— 一旦 Mapper 里出现业务分支，
 * 数据域拦截器与状态机就会被绕过。
 *
 * <p>商家的六个 Mapper 已迁往 {@code MerchantMappers}（S3），
 * 社区与自提点迁往 {@code CommunityMappers}（S4）。
 */
public final class UserMappers {

    private UserMappers() {
    }

    public interface UserMapper extends BaseMapper<UsrAccount> {
    }

    /** 登录凭证。一个人多条，唯一键 (identity_type, identity_value)。 */
    public interface IdentityMapper extends BaseMapper<UsrIdentity> {

        /**
         * <b>物理</b>删除某人的全部凭证 —— 注销账号专用。
         *
         * <p>不能用 `delete()`：`BaseEntity` 上有 `@TableLogic`，那条会变成
         * `update ... set deleted = 1`，而 <b>{@code uk_identity} 唯一键里没有 deleted</b>，
         * 软删掉的行仍然占着 (type, value)。后果是**同一个微信永远注册不回来** ——
         * 注销之后再进小程序，建号那一步撞唯一键、整个登录 500，
         * 而报错是「系统开小差」，跟注销一点关系都看不出来。
         *
         * <p>这也是注销该做的事：凭证要真的还回去，不是留着占位。
         */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM usr_identity WHERE user_no = #{userNo}")
        int deleteAllByUserPhysically(@org.apache.ibatis.annotations.Param("userNo") String userNo);
    }

    public interface AddressMapper extends BaseMapper<UsrAddress> {
    }

    public interface StoreFavoriteMapper extends BaseMapper<UsrStoreFavorite> {
    }
}
