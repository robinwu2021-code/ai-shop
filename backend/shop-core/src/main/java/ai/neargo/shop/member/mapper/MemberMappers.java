package ai.neargo.shop.member.mapper;

import ai.neargo.shop.member.entity.MbrMember;
import ai.neargo.shop.member.entity.MbrMemberSource;
import ai.neargo.shop.member.entity.MbrMemberStore;
import ai.neargo.shop.member.entity.MbrSetting;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 会员域的 Mapper 集合（嵌套接口，与 {@code UserMappers} / {@code MerchantMappers} 同一写法）。
 *
 * <p>Mapper 只做单表 CRUD 与条件组合 —— 一旦这里出现业务分支，数据域拦截器就会被绕过。
 */
public final class MemberMappers {

    private MemberMappers() {
    }

    public interface MemberMapper extends BaseMapper<MbrMember> {
    }

    /** 门店维度。**单店主体没有行** —— 读不到就回落主表 */
    public interface MemberStoreMapper extends BaseMapper<MbrMemberStore> {
    }

    /** 来源明细。每一次来源一行，不覆盖 */
    public interface MemberSourceMapper extends BaseMapper<MbrMemberSource> {
    }

    public interface SettingMapper extends BaseMapper<MbrSetting> {
    }
}
