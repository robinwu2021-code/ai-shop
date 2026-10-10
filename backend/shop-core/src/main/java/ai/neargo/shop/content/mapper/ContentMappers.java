package ai.neargo.shop.content.mapper;

import ai.neargo.shop.content.entity.CntMaterial;
import ai.neargo.shop.content.entity.CntPost;
import ai.neargo.shop.content.entity.CntQuestion;
import ai.neargo.shop.content.entity.CntRanking;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 内容域的四个 Mapper。
 *
 * <p>都只用 {@code BaseMapper} —— 内容审核没有并发争抢：
 * 同一条内容被两个审核员同时裁决是极小概率，且后果是「后写的赢」而不是资金错乱。
 * 与券的超发、积分的双花不是一个量级，不需要带条件的 UPDATE。
 */
public final class ContentMappers {

    private ContentMappers() {
    }

    public interface PostMapper extends BaseMapper<CntPost> {
    }

    public interface QuestionMapper extends BaseMapper<CntQuestion> {
    }

    public interface RankingMapper extends BaseMapper<CntRanking> {
    }

    public interface MaterialMapper extends BaseMapper<CntMaterial> {
    }
}
