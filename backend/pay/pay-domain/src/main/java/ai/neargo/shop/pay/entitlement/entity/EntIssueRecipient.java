package ai.neargo.shop.pay.entitlement.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** Lifetime issuance facts are keyed by template, never reset by publication. */
@Getter
@Setter
@TableName("ent_issue_recipient")
public class EntIssueRecipient extends BaseEntity {
    private String templateNo;
    private String userNo;
    private Integer heldCount;
    private Integer issuedCount;
}
