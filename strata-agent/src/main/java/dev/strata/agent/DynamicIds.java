package dev.strata.agent;

import java.util.List;
import java.util.regex.Pattern;

/** Java copy of {@code dev.strata.core.DynamicIds} (CLAUDE.md §5.5). Keep the two in sync. */
final class DynamicIds {
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("\\d{4,}"),
            Pattern.compile("[0-9a-f]{8}-"),
            Pattern.compile("^ember\\d+"),
            Pattern.compile("^mat-[a-z-]+-\\d+"),
            Pattern.compile("^react-select-\\d+"),
            Pattern.compile("^cdk-"),
            Pattern.compile("^:r[0-9a-z]+:$"),
            Pattern.compile("^ext-gen\\d+"),
            Pattern.compile("^ui-id-\\d+"));

    private DynamicIds() {
    }

    static boolean isDynamic(final String id) {
        for (final Pattern p : PATTERNS) {
            if (p.matcher(id).find()) {
                return true;
            }
        }
        return false;
    }
}
