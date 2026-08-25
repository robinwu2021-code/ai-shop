package ai.neargo.shop.message.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 常见问题（帮助中心）。{@code published=true} 的条目才对 C 端可见。 */
@Getter
@Setter
@TableName("msg_faq")
public class MsgFaq extends BaseEntity {

    private String faqNo;
    private String question;
    /** 富文本/Markdown；不超过 2000 字 */
    private String answer;
    private String category;
    private Integer sort;
    /** C 端只看 published=true 的条目 */
    private Boolean published;
}
