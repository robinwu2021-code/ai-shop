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

        /**
         * <b>物理删一个人身上的一个标签。</b>唯一键 {@code uk_mbr_member_tag(tenant_no, member_no, tag_no)}
         * 不含 {@code deleted}：逻辑删之后旧行仍占着那个组合，<b>去掉再打回去就撞键</b> ——
         * 活动商品表与受众表是同一个坑（见 {@code PromotionMappers}）。关系行是派生数据，
         * 谁打的、何时打的只对「现在有没有」有意义，没有独立的历史价值。
         */
        @org.apache.ibatis.annotations.Delete(
                "DELETE FROM mbr_member_tag WHERE member_no = #{memberNo} AND tag_no = #{tagNo}")
        int hardDelete(@org.apache.ibatis.annotations.Param("memberNo") String memberNo,
                       @org.apache.ibatis.annotations.Param("tagNo") String tagNo);

        /** 物理删一行（合并时两个标签都有的人删掉源那一条）。理由同上 */
        @org.apache.ibatis.annotations.Delete("DELETE FROM mbr_member_tag WHERE id = #{id}")
        int hardDeleteById(@org.apache.ibatis.annotations.Param("id") Long id);
    }

    /** 人群：一组条件。发券、活动受众、触达都引用它 */
    public interface SegmentMapper extends BaseMapper<ai.neargo.shop.member.entity.MbrSegment> {
    }

    /** 触达记录。频次闸与效果回看都读它 */
    public interface ReachLogMapper
            extends BaseMapper<ai.neargo.shop.member.entity.MbrReachLog> {
    }

    public interface TagMergeLogMapper
            extends BaseMapper<ai.neargo.shop.member.entity.MbrTagMergeLog> {
    }
}
