package org.telegram.relay.mattermost.text;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The language of a fenced code block as the app's highlighter names it (Prism grammars, which
 * Telegram's code blocks use on iOS and Android).
 *
 * <p>Mattermost's clients highlight with highlight.js, whose names and aliases differ from Prism's in
 * places ({@code golang}, {@code c++}, {@code zsh}, {@code pgsql}, …). Prism's own aliases ({@code js},
 * {@code py}, {@code sh}, {@code yml}, …) pass through, lowercased. Port of the iOS {@code codeLanguage}.
 */
public final class CodeLanguage {
    private CodeLanguage() {
    }

    private static final Map<String, String> ALIASES = new HashMap<String, String>();

    static {
        String[] pairs = {
            "golang", "go",
            "c++", "cpp",
            "cxx", "cpp",
            "hpp", "cpp",
            "cc", "cpp",
            "h", "c",
            "c#", "csharp",
            "f#", "fsharp",
            "objective-c", "objectivec",
            "obj-c", "objc",
            "zsh", "bash",
            "terminal", "bash",
            "console", "shell-session",
            "ps1", "powershell",
            "pwsh", "powershell",
            "ps", "powershell",
            "bat", "batch",
            "cmd", "batch",
            "proto", "protobuf",
            "tf", "hcl",
            "terraform", "hcl",
            "erl", "erlang",
            "ex", "elixir",
            "exs", "elixir",
            "pl", "perl",
            "pm", "perl",
            "rs", "rust",
            "htm", "html",
            "xhtml", "html",
            "plist", "xml",
            "pgsql", "sql",
            "postgresql", "sql",
            "postgres", "sql",
            "mysql", "sql",
            "sqlite", "sql",
            "jsonc", "json",
            "gql", "graphql",
            "mk", "makefile",
            "make", "makefile",
            "patch", "diff",
            "clj", "clojure",
            "cljs", "clojure",
            "ml", "ocaml",
            "delphi", "pascal",
            "vue", "markup",
            "svelte", "markup",
            "mjs", "javascript",
            "cjs", "javascript",
            "node", "javascript",
        };
        for (int i = 0; i < pairs.length; i += 2) {
            ALIASES.put(pairs[i], pairs[i + 1]);
        }
    }

    /** The highlighter's name for a fence info string's language, or null for an empty one. */
    public static String normalize(String language) {
        if (language == null) {
            return null;
        }
        String trimmed = CharClass.trimWhitespaceAndNewlines(language).toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty()) {
            return null;
        }
        String alias = ALIASES.get(trimmed);
        return alias != null ? alias : trimmed;
    }
}
