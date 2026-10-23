package ai.neargo.shop.food.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Explicit constraints are validated against actual option identities rather than client-provided expressions. */
public record SelectionRules(@NotNull @Size(max=100) List<List<String>> mutexSets,
        @NotNull @Size(max=100) List<@Valid SelectionDependency> requires) {
    public SelectionRules {
        if (mutexSets != null) mutexSets = mutexSets.stream().map(s -> s == null ? null : List.copyOf(s)).toList();
        if (requires != null) requires = List.copyOf(requires);
    }
    @JsonAnySetter
    public void rejectUnknown(String name, Object value) { throw new IllegalArgumentException("Unknown rule field"); }

    /** Dependencies are business references within one group and must form an acyclic graph. */
    public record SelectionDependency(String optionItemNo, List<String> requiresItemNos) {
        public SelectionDependency { if (requiresItemNos != null) requiresItemNos = List.copyOf(requiresItemNos); }
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown dependency field");
        }
    }
}
