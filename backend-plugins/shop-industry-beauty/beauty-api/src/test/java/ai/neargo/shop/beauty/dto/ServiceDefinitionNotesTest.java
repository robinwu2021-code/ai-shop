package ai.neargo.shop.beauty.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Notes are bounded immutable input and old callers retain the empty-notes semantics. */
class ServiceDefinitionNotesTest {
    private PublishServiceDefinitionVersionReq command(List<String> notes) {
        return new PublishServiceDefinitionVersionReq("SKU", 0L,
                new PublishServiceDefinitionVersionReq.Duration(60, 0, 0),
                List.of(new PublishServiceDefinitionVersionReq.Requirement("TECH", "STAFF", "G", 1,
                        0, 0, 60, "ANY_QUALIFIED")), notes);
    }
    @Test
    void freezesNotesAndKeepsExistingConstructorCompatible() {
        var notes = new ArrayList<>(List.of("请提前到店"));
        var command = command(notes);
        notes.clear();
        assertThat(command.notes()).containsExactly("请提前到店");
        assertThatThrownBy(command.notes()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new PublishServiceDefinitionVersionReq(command.skuNo(), command.expectedRevision(),
                command.duration(), command.requirements()).notes()).isEmpty();
    }
    @Test
    void rejectsExcessBlankNullAndOversizedNotes() {
        for (List<String> notes : List.of(Collections.nCopies(21, "note"), List.of(" "),
                Arrays.asList((String) null), List.of("n".repeat(501)))) {
            assertThatThrownBy(() -> command(notes)).isInstanceOf(BizException.class);
        }
        assertThat(command(null).notes()).isEmpty();
    }
}
