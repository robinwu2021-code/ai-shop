package ai.neargo.shop.member.service;

import ai.neargo.shop.member.dto.MemberVOs.MemberQuery;
import ai.neargo.shop.member.dto.MemberVOs.SegmentPreviewVO;
import ai.neargo.shop.member.dto.MemberVOs.SegmentVO;

import java.util.List;

/**
 * 人群：一组筛选条件，可命名保存、反复用。
 *
 * <p><b>为什么要有它</b>：发券、活动受众、发消息都要回答「给哪一群人」。
 * 三处各存一份条件的话，同一群人会算出三个数，而商家分不清哪个对。
 *
 * <p><b>存条件不存名单</b>：名单每天都在变。{@link #resolve} 一律当场算。
 */
public interface MemberSegmentService {

    List<SegmentVO> list(String entityNo);

    /** 保存或改名。同名视为同一个人群（覆盖条件），不报错 */
    SegmentVO save(String entityNo, String segmentNo, String name, String scopeStoreNo,
                   MemberQuery rule);

    void remove(String entityNo, String segmentNo);

    /** 试算：此刻命中多少人、其中多少人能真正收到。界面上那句「命中 N 人」就是它 */
    SegmentPreviewVO preview(String entityNo, String scopeStoreNo, MemberQuery rule);

    /**
     * 解析成<b>可触达</b>的会员号列表 —— 发券与触达直接照这份发。
     *
     * <p><b>当场算，不吃缓存</b>：按两周前的名单发券是错的。
     * <p><b>只给可触达的</b>：线索会员（商家手录、本人还没在平台出现）与退订的人不在内。
     * 想看完整命中人数用 {@link #preview}。
     */
    List<String> resolve(String entityNo, String segmentNo);

    List<String> resolve(String entityNo, String scopeStoreNo, MemberQuery rule);
}
