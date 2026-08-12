package ai.neargo.shop.content.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商品问答。
 *
 * <p><b>已回答的不能再答</b>：要改先隐藏，让改动这件事本身留下痕迹 ——
 * 直接覆盖的话，用户看到的答案变了，而没有任何地方记得它变过。
 */
@Getter
@Setter
@TableName("cnt_question")
public class CntQuestion extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String ANSWERED = "ANSWERED";
    public static final String HIDDEN = "HIDDEN";

    private String questionNo;
    private String skuNo;
    /** 商品名快照：商品改名不该让历史问答对不上 */
    private String skuTitle;
    private String content;
    private String askedBy;
    private String status;
    private String answer;
    private String answeredBy;
    private Long answeredAt;
    private String hideReason;
}
