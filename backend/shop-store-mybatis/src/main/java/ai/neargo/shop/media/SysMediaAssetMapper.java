package ai.neargo.shop.media;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 图片资产记账表是基础设施表：上传写、扫描改、运营端读，不参与模块归属划分。 */
public interface SysMediaAssetMapper extends BaseMapper<SysMediaAsset> {

    /**
     * 把 {@code marked_at} 置空 —— 救回时用。
     *
     * <p><b>不能用 {@code updateById}</b>：MyBatis-Plus 默认跳过实体里为 null 的字段，
     * 于是「把一列改成 NULL」这个意图会被静默忽略。表现是救回之后 {@code marked_at}
     * 还留着上一次的值，「在清单里待了多少天」从此是错的，而且不报错。
     */
    @Update("UPDATE sys_media_asset SET marked_at = NULL WHERE id = #{id}")
    int clearMarkedAt(@Param("id") Long id);
}
