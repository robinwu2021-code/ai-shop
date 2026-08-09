package ai.neargo.shop.marketing.campaign.mapper;

import ai.neargo.shop.marketing.campaign.entity.MktCampaign;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/** 商家营销活动的 Mapper。 */
public final class CampaignMappers {

    private CampaignMappers() {
    }

    public interface CampaignMapper extends BaseMapper<MktCampaign> {

        /**
         * 原子领取：额度判断写在 WHERE 里，靠影响行数判定成功与否。
         * 先查后改在并发下必然超发 —— 两个请求都查到「还剩 1 张」，与券表同一个道理。
         *
         * @return 1=领取成功，0=已发完
         */
        @Update("""
                UPDATE mkt_campaign SET taken_count = taken_count + 1, version = version + 1
                WHERE campaign_no = #{campaignNo} AND deleted = 0 AND status = 'RUNNING'
                  AND (total_count IS NULL OR taken_count < total_count)
                """)
        int tryTake(@Param("campaignNo") String campaignNo);
    }
}
