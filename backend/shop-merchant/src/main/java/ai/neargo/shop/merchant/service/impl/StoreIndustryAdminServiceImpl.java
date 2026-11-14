package ai.neargo.shop.merchant.service.impl;

import ai.neargo.commerce.api.catalog.CatalogFacade;
import ai.neargo.commerce.api.common.ActorContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.service.StoreIndustryAdminService;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(prefix="commerce", name={"enabled", "catalog.enabled"}, havingValue="true")
public class StoreIndustryAdminServiceImpl implements StoreIndustryAdminService {
    private final JdbcTemplate jdbc;
    private final CatalogFacade catalog;

    public StoreIndustryAdminServiceImpl(JdbcTemplate jdbc, CatalogFacade catalog) {
        this.jdbc = jdbc;
        this.catalog = catalog;
    }

    @Override public List<View> list(ActorContext actor) {
        return jdbc.query("SELECT industry_code,sub_industry_code,business_type_code,name_zh,name_tw,name_en,sort,enabled,version "
                + "FROM sys_store_industry WHERE tenant_no=? AND deleted=0 ORDER BY industry_code,sort,sub_industry_code",
                this::view, actor.tenantNo());
    }

    @Override @Transactional
    public View save(ActorContext actor, Save c) {
        if (catalog.overview(actor).businessTypes().stream()
                .noneMatch(t -> t.draft().typeCode().equals(c.businessTypeCode())))
            throw BizException.of(ErrorCode.BAD_REQUEST);
        String sub = c.subIndustryCode() == null ? "" : c.subIndustryCode();
        String industry = c.industryCode();
        // 使用父项真实编码。MySQL 业务键查询不区分大小写，Java 组树按编码精确关联。
        if (!sub.isEmpty()) {
            var parents = jdbc.queryForList(
                    "SELECT industry_code FROM sys_store_industry WHERE tenant_no=? AND industry_code=? AND sub_industry_code='' AND deleted=0 FOR UPDATE",
                    String.class, actor.tenantNo(), industry);
            if (parents.isEmpty()) throw BizException.of(ErrorCode.NOT_FOUND);
            industry = parents.getFirst();
        }
        if (c.expectedVersion() == null) {
            try {
                jdbc.update("INSERT INTO sys_store_industry(tenant_no,industry_code,sub_industry_code,business_type_code,name_zh,name_tw,name_en,sort,enabled,created_at,created_by,updated_at,updated_by,version,deleted) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP,?,CURRENT_TIMESTAMP,?,0,0)",
                        actor.tenantNo(), industry, sub, c.businessTypeCode(), c.nameZh(), c.nameTw(), c.nameEn(), c.sort(), c.enabled(), actor.principalNo(), actor.principalNo());
            } catch (DuplicateKeyException duplicate) {
                // 原业务键已存在：让并发或未知结果的创建者刷新原行，不覆盖另一人的配置。
                throw BizException.of(ErrorCode.CONFLICT);
            }
        } else if (jdbc.update("UPDATE sys_store_industry SET business_type_code=?,name_zh=?,name_tw=?,name_en=?,sort=?,enabled=?,updated_at=CURRENT_TIMESTAMP,updated_by=?,version=version+1 "
                + "WHERE tenant_no=? AND industry_code=? AND sub_industry_code=? AND version=? AND deleted=0",
                c.businessTypeCode(), c.nameZh(), c.nameTw(), c.nameEn(), c.sort(), c.enabled(), actor.principalNo(), actor.tenantNo(), industry, sub, c.expectedVersion()) != 1) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return jdbc.queryForObject("SELECT industry_code,sub_industry_code,business_type_code,name_zh,name_tw,name_en,sort,enabled,version "
                + "FROM sys_store_industry WHERE tenant_no=? AND industry_code=? AND sub_industry_code=? AND deleted=0",
                this::view, actor.tenantNo(), industry, sub);
    }

    private View view(ResultSet r, int row) throws SQLException {
        return new View(r.getString(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                r.getString(6), r.getInt(7), r.getBoolean(8), r.getLong(9));
    }
}
