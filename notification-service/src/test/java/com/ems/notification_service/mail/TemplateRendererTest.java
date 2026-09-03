package com.ems.notification_service.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    @Test
    void replacesEveryOccurrenceOfAPlaceholder() {
        String rendered = TemplateRenderer.render("Hi {{username}}, welcome {{username}}.", Map.of("username", "ada"));

        assertThat(rendered).isEqualTo("Hi ada, welcome ada.");
    }

    @Test
    void toleratesWhitespaceInsideTheBraces() {
        assertThat(TemplateRenderer.render("{{ username }}", Map.of("username", "ada")))
                .isEqualTo("ada");
    }

    @Test
    void leavesTextWithNoPlaceholdersAlone() {
        assertThat(TemplateRenderer.render("Nothing to do here.", Map.of())).isEqualTo("Nothing to do here.");
    }

    @Test
    void ignoresModelEntriesTheTemplateDoesNotUse() {
        assertThat(TemplateRenderer.render("Hi {{username}}.", Map.of("username", "ada", "email", "ada@ems.local")))
                .isEqualTo("Hi ada.");
    }

    /**
     * A value is text, not a replacement expression: {@code $1} in someone's name must reach
     * the email as {@code $1} rather than being read as a backreference into the match.
     */
    @Test
    void treatsDollarsAndBackslashesInValuesAsLiteralText() {
        assertThat(TemplateRenderer.render("Hi {{username}}.", Map.of("username", "a$1b\\c")))
                .isEqualTo("Hi a$1b\\c.");
    }

    /** Better a failed delivery, which parks and is visible, than a mail reading "Hi {{username}}". */
    @Test
    void failsOnAPlaceholderTheModelDoesNotProvide() {
        assertThatThrownBy(() -> TemplateRenderer.render("Hi {{username}}.", Map.of("email", "ada@ems.local")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }
}
