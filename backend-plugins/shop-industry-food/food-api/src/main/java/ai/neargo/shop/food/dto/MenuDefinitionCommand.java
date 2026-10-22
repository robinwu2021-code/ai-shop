package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/** Explicit disjoint sections prevent accidental clearing of another part of a menu. */
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.EXISTING_PROPERTY, property="section", visible=true)
@JsonSubTypes({@JsonSubTypes.Type(value=ReplaceMenuScheduleReq.class, name="SCHEDULE"),
        @JsonSubTypes.Type(value=ReplaceMenuCompositionReq.class, name="COMPOSITION"),
        @JsonSubTypes.Type(value=ReplaceMenuContextsReq.class, name="CONTEXT")})
public sealed interface MenuDefinitionCommand permits ReplaceMenuScheduleReq, ReplaceMenuCompositionReq,
        ReplaceMenuContextsReq { }
