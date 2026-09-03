package com.ems.notification_service.mail;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code {{placeholder}}} in a stored template with the values a handler supplies.
 *
 * <p>Deliberately the smallest thing that works, and deliberately not a template engine.
 * There is no logic, no iteration and no expression syntax, which means a template stored in
 * the database can only ever interpolate values it is handed — an email body is edited far
 * more casually than code is, and this keeps the worst outcome of a bad edit a wrong word
 * rather than something executing.
 *
 * <p>A placeholder the model does not cover is an error, not an empty string. Sending
 * "Welcome, {{username}}" to a real person is worse than not sending at all: failing here
 * puts the delivery through the retry and parking path, where it is visible.
 */
public final class TemplateRenderer {

    /** Tolerates inner whitespace, so {@code {{ username }}} reads the same as {@code {{username}}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*}}");

    private TemplateRenderer() {}

    /**
     * @param template text containing zero or more {@code {{placeholder}}} markers
     * @param model the value for each placeholder name
     * @return the template with every placeholder replaced
     * @throws IllegalArgumentException if the template refers to a name the model has no
     *     value for
     */
    public static String render(String template, Map<String, String> model) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = model.get(name);
            if (value == null) {
                throw new IllegalArgumentException(
                        "Template refers to {{%s}}, which the model does not provide".formatted(name));
            }
            // Quoted because a value containing $ or \ would otherwise be read as a
            // backreference into the match rather than as text.
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
