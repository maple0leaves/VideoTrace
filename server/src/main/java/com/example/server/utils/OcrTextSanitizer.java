package com.example.server.utils;

import java.util.regex.Pattern;

/** Removes recognizer diagnostics that must never become video evidence. */
public final class OcrTextSanitizer {

    private static final Pattern INVALID_RESOLUTION_WARNING = Pattern.compile(
            "^\\s*warning[.:]\\s*invalid resolution\\s+\\d+(?:\\.\\d+)?\\s*dpi\\.?"
                    + "(?:\\s*using\\s+\\d+(?:\\.\\d+)?\\s+instead\\.?)?"
                    + "(?:\\s*estimating resolution as\\s+\\d+(?:\\.\\d+)?\\.?)?\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ESTIMATED_RESOLUTION = Pattern.compile(
            "^\\s*estimating resolution as\\s+\\d+(?:\\.\\d+)?\\.?\\s*$",
            Pattern.CASE_INSENSITIVE);

    private OcrTextSanitizer() {
    }

    public static String sanitize(String value) {
        if (value == null || value.isBlank()) return "";
        return value.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .filter(line -> !isToolDiagnostic(line))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    public static boolean isToolDiagnostic(String value) {
        if (value == null || value.isBlank()) return false;
        String line = value.trim();
        return INVALID_RESOLUTION_WARNING.matcher(line).matches()
                || ESTIMATED_RESOLUTION.matcher(line).matches();
    }
}
