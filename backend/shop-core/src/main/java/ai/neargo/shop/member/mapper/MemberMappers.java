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

    /** 标签字典。改名只动这里一行 */
    public interface TagMapper extends BaseMapper<ai.neargo.shop.member.entity.MbrTag> {
    }

    /** 标签关系。合并时整批改指目标（唯一键会挡住重复） */
    public interface MemberTagMapper extends BaseMapper<ai.neargo.shop.member.entity.MbrMemberTag> {
    }

    /** 人群：一组条件。发券、活动受众、触达都引用它 */
    public interface SegmentMapper extends BaseMapper<ai.neargo.shop.member.entity.MbrSegment> {
    }

    public interface TagMergeLogMapper
            extends BaseMapper<ai.neargo.shop.member.entity.MbrTagMergeLog> {
    }
}
