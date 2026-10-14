package ai.neargo.shop.marketing.visit.mapper;

import ai.neargo.shop.marketing.visit.entity.MktStoreVisit;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/** 访问埋点域的 Mapper 集合。 */
public final class VisitMappers {

    private VisitMappers() {
    }

    public interface StoreVisitMapper extends BaseMapper<MktStoreVisit> {

        /**
         * 一个主体近来的到访**人数**（UV）：按 user_no 回落 device_id 去重。
         * PV 用 {@code selectCount} 就够，UV 的 count(distinct coalesce) Wrappers 表达不了，所以单列一条。
         * COALESCE 在 H2 与 MySQL 语义一致（见 sql-dialect-h2-vs-mariadb）。
         */
        @org.apache.ibatis.annotations.Select(
                "SELECT COUNT(DISTINCT COALESCE(user_no, device_id)) FROM mkt_store_visit "
                        + "WHERE entity_no = #{entityNo} AND at >= #{sinceMs}")
        long countUvSince(@org.apache.ibatis.annotations.Param("entityNo") String entityNo,
                          @org.apache.ibatis.annotations.Param("sinceMs") long sinceMs);
    }
}
