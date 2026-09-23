package com.habittracker.lambda.env;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SsmBackedPropertiesEnvironmentPostProcessorTest {

    @Test
    @DisplayName("parses the newline-separated name=path form the lambda module emits")
    void parsesTheTerraformForm() {
        Map<String, String> parsed =
                SsmBackedPropertiesEnvironmentPostProcessor.parse(
                        "jwt.access-secret=/services/auth-dev/jwt/access-secret\n"
                                + "billing.stripe.secret-key=/services/api-dev/stripe/secret-key");

        assertThat(parsed)
                .containsExactly(
                        Map.entry("jwt.access-secret", "/services/auth-dev/jwt/access-secret"),
                        Map.entry(
                                "billing.stripe.secret-key",
                                "/services/api-dev/stripe/secret-key"));
    }

    @Test
    @DisplayName("an absent or blank specification resolves nothing and calls no AWS API")
    void absentSpecificationIsANoOp() {
        assertThat(SsmBackedPropertiesEnvironmentPostProcessor.parse(null)).isEmpty();
        assertThat(SsmBackedPropertiesEnvironmentPostProcessor.parse("   ")).isEmpty();
    }

    @Test
    @DisplayName("blank lines are skipped rather than failing the whole application")
    void blankLinesAreSkipped() {
        assertThat(SsmBackedPropertiesEnvironmentPostProcessor.parse("a=/one\n\n  \nb=/two"))
                .containsExactly(Map.entry("a", "/one"), Map.entry("b", "/two"));
    }

    @Test
    @DisplayName("a malformed entry fails fast rather than silently dropping a secret")
    void malformedEntryFailsFast() {
        assertThatThrownBy(() -> SsmBackedPropertiesEnvironmentPostProcessor.parse("no-separator"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("name=path");

        assertThatThrownBy(() -> SsmBackedPropertiesEnvironmentPostProcessor.parse("trailing="))
                .isInstanceOf(IllegalStateException.class);
    }
}
