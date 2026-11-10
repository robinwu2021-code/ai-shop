package ai.neargo.shop.commercebridge;

import ai.neargo.shop.media.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Retain reserved keys across upload/submit/recovery crashes. */
@Component
public class EntityPlanEvidenceMediaRefs implements MediaRefSource {
  private final JdbcTemplate jdbc;

  public EntityPlanEvidenceMediaRefs(javax.sql.DataSource ds) {
    jdbc = new JdbcTemplate(ds);
  }

  public List<MediaRefColumn> columns() {
    Integer n =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                + " table_name='mch_plan_purchase_evidence'",
            Integer.class);
    return n != null && n > 0
        ? List.of(
            MediaRefColumn.of("mch_plan_purchase_evidence", "asset_key", "主体额度包线下凭证", "order_no"))
        : List.of();
  }
}
