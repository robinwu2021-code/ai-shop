package ai.neargo.shop.link.mapper;

import ai.neargo.shop.link.entity.ShortLink;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 短链 Mapper。{@code hits++} 走原生 UPDATE —— 点击计数是并发热点，
 * 读-改-写会丢更新，交给库的原子自增。
 */
public interface ShortLinkMapper extends BaseMapper<ShortLink> {

    /** 命中一次：点击量 +1。不走乐观锁（version）—— 这一列允许并发自增，不该因 version 冲突失败 */
    @Update("UPDATE lnk_short SET hits = hits + 1 WHERE id = #{id}")
    int bumpHits(@Param("id") Long id);
}
