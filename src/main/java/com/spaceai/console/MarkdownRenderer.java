package com.spaceai.console;

import java.io.PrintStream;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * rendering Markdown（potenziamento） —— Corrisponde a space-ai/src/renderers/markdown.ts。
 * <p>
 * verrà AI in Markdown formatoConversionecometerminale ANSI stileOutput。
 * supportablocco di codicealtoevidenzia、c'èLista、citazione、Tabellaecc.。
 */
public class MarkdownRenderer {

    private final PrintStream out;

    // parola chiaveInsieme，incodicealtoevidenzia
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
            "class", "const", "continue", "default", "do", "double", "else", "enum",
            "extends", "final", "finally", "float", "for", "goto", "if", "implements",
            "import", "instanceof", "int", "interface", "long", "native", "new", "package",
            "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient",
            "try", "void", "volatile", "while", "var", "record", "sealed", "permits",
            "yield", "when");

    private static final Set<String> JS_KEYWORDS = Set.of(
            "async", "await", "break", "case", "catch", "class", "const", "continue",
            "debugger", "default", "delete", "do", "else", "export", "extends", "false",
            "finally", "for", "from", "function", "if", "import", "in", "instanceof",
            "let", "new", "null", "of", "return", "super", "switch", "this", "throw",
            "true", "try", "typeof", "undefined", "var", "void", "while", "with", "yield");

    private static final Set<String> PYTHON_KEYWORDS = Set.of(
            "and", "as", "assert", "async", "await", "break", "class", "continue",
            "def", "del", "elif", "else", "except", "False", "finally", "for", "from",
            "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not",
            "or", "pass", "raise", "return", "True", "try", "while", "with", "yield");

    private static final Set<String> SHELL_KEYWORDS = Set.of(
            "if", "then", "else", "elif", "fi", "for", "while", "do", "done", "case",
            "esac", "function", "return", "exit", "echo", "export", "source", "set",
            "unset", "local", "readonly", "declare", "cd", "pwd", "ls", "cat", "grep",
            "sed", "awk", "find", "mkdir", "rm", "cp", "mv", "chmod", "chown");

    private static final Set<String> SQL_KEYWORDS = Set.of(
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "VALUES", "UPDATE", "SET",
            "DELETE", "CREATE", "TABLE", "ALTER", "DROP", "INDEX", "JOIN", "LEFT",
            "RIGHT", "INNER", "OUTER", "ON", "AND", "OR", "NOT", "NULL", "IS",
            "IN", "LIKE", "BETWEEN", "ORDER", "BY", "GROUP", "HAVING", "LIMIT",
            "OFFSET", "AS", "DISTINCT", "COUNT", "SUM", "AVG", "MAX", "MIN");

    /** aparola chiaveMappa */
    private static final Map<String, Set<String>> LANG_KEYWORDS;
    static {
        var map = new java.util.HashMap<String, Set<String>>();
        map.put("java", JAVA_KEYWORDS);
        map.put("javascript", JS_KEYWORDS);
        map.put("js", JS_KEYWORDS);
        map.put("typescript", JS_KEYWORDS);
        map.put("ts", JS_KEYWORDS);
        map.put("python", PYTHON_KEYWORDS);
        map.put("py", PYTHON_KEYWORDS);
        map.put("bash", SHELL_KEYWORDS);
        map.put("sh", SHELL_KEYWORDS);
        map.put("shell", SHELL_KEYWORDS);
        map.put("sql", SQL_KEYWORDS);
        LANG_KEYWORDS = Map.copyOf(map);
    }

    // altoevidenziapositivo
    private static final Pattern STRING_PATTERN = Pattern.compile("(\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"|'[^'\\\\]*(\\\\.[^'\\\\]*)*')");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b(\\d+\\.?\\d*[fFdDlL]?|0x[0-9a-fA-F]+)\\b");
    private static final Pattern SINGLE_LINE_COMMENT = Pattern.compile("(//.*|#.*)$");
    private static final Pattern ANNOTATION_PATTERN = Pattern.compile("(@\\w+)");

    public MarkdownRenderer(PrintStream out) {
        this.out = out;
    }

    /** Esegui rendering Markdown testo */
    public void render(String markdown) {
        if (markdown == null || markdown.isBlank()) return;

        boolean inCodeBlock = false;
        String codeBlockLang = "";

        for (String line : markdown.lines().toList()) {
            // blocco di codice
            if (line.stripLeading().startsWith("```")) {
                if (!inCodeBlock) {
                    codeBlockLang = line.stripLeading().substring(3).strip().toLowerCase();
                    inCodeBlock = true;
                    String langLabel = codeBlockLang.isEmpty() ? "code" : codeBlockLang;
                    out.println(AnsiStyle.dim("  ┌─" + langLabel + "─" + "─".repeat(Math.max(0, 40 - langLabel.length()))));
                    continue;
                } else {
                    inCodeBlock = false;
                    out.println(AnsiStyle.dim("  └" + "─".repeat(42)));
                    codeBlockLang = "";
                    continue;
                }
            }

            if (inCodeBlock) {
                out.println("  " + AnsiStyle.DIM + "│" + AnsiStyle.RESET + " " + highlightCode(line, codeBlockLang));
                continue;
            }

            // titolo
            if (line.startsWith("### ")) {
                out.println(AnsiStyle.bold(AnsiStyle.CYAN + "  " + line.substring(4)) + AnsiStyle.RESET);
            } else if (line.startsWith("## ")) {
                out.println(AnsiStyle.bold(AnsiStyle.BLUE + "  " + line.substring(3)) + AnsiStyle.RESET);
            } else if (line.startsWith("# ")) {
                out.println(AnsiStyle.bold(AnsiStyle.MAGENTA + "  " + line.substring(2)) + AnsiStyle.RESET);
            }
            // citazione
            else if (line.stripLeading().startsWith("> ")) {
                String quoteText = line.stripLeading().substring(2);
                out.println("  " + AnsiStyle.DIM + "┃" + AnsiStyle.RESET + " " + AnsiStyle.ITALIC + renderInline(quoteText) + AnsiStyle.RESET);
            }
            // c'èLista
            else if (line.stripLeading().matches("^\\d+\\.\\s+.*")) {
                Matcher m = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.*)").matcher(line);
                if (m.matches()) {
                    String indent = m.group(1);
                    String num = m.group(2);
                    String text = m.group(3);
                    out.println("  " + indent + AnsiStyle.CYAN + num + "." + AnsiStyle.RESET + " " + renderInline(text));
                } else {
                    out.println("  " + renderInline(line));
                }
            }
            // nessunoLista
            else if (line.stripLeading().startsWith("- ") || line.stripLeading().startsWith("* ")) {
                int indent = line.length() - line.stripLeading().length();
                String prefix = " ".repeat(indent);
                out.println("  " + prefix + AnsiStyle.CYAN + "•" + AnsiStyle.RESET + " " + renderInline(line.stripLeading().substring(2)));
            }
            // casella di controlloLista
            else if (line.stripLeading().startsWith("- [ ] ")) {
                out.println("  " + AnsiStyle.DIM + "☐" + AnsiStyle.RESET + " " + renderInline(line.stripLeading().substring(6)));
            } else if (line.stripLeading().startsWith("- [x] ") || line.stripLeading().startsWith("- [X] ")) {
                out.println("  " + AnsiStyle.GREEN + "☑" + AnsiStyle.RESET + " " + renderInline(line.stripLeading().substring(6)));
            }
            // linea divisoria
            else if (line.strip().matches("^[-*]{3,}$")) {
                out.println(AnsiStyle.dim("  " + "─".repeat(42)));
            }
            // testo
            else {
                out.println("  " + renderInline(line));
            }
        }
    }

    // ==================== evidenziazione sintassi ====================

    /**
     * basato sucodicealtezza rigaevidenzia。
     * colorazionePriorità:commento > stringa > annotazione > parola chiave > carattere。
     */
    private String highlightCode(String line, String lang) {
        if (lang.isEmpty() || !LANG_KEYWORDS.containsKey(lang)) {
            // non ancora：Solo
            return AnsiStyle.BRIGHT_GREEN + line + AnsiStyle.RESET;
        }

        Set<String> keywords = LANG_KEYWORDS.get(lang);
        StringBuilder result = new StringBuilder();

        // altezza rigaevidenzia：rilevacommentoestringaintervallo，suintervallocolorazioneparola chiave
        // per semplificare l'implementazione, adotta una strategia di sostituzione segmentata

        String processed = line;

        // 1. commento（ // o #）—— corsivo
        Matcher commentMatcher = SINGLE_LINE_COMMENT.matcher(processed);
        if (commentMatcher.find()) {
            String beforeComment = processed.substring(0, commentMatcher.start());
            String comment = commentMatcher.group();
            return highlightNonComment(beforeComment, keywords, lang)
                    + AnsiStyle.BRIGHT_BLACK + AnsiStyle.ITALIC + comment + AnsiStyle.RESET;
        }

        return highlightNonComment(processed, keywords, lang);
    }

    /** sucommentopartealtezza rigaevidenzia */
    private String highlightNonComment(String code, Set<String> keywords, String lang) {
        // segnapostostringacarattere
        var stringRanges = new java.util.ArrayList<int[]>();
        Matcher strMatcher = STRING_PATTERN.matcher(code);
        while (strMatcher.find()) {
            stringRanges.add(new int[]{strMatcher.start(), strMatcher.end()});
        }

        StringBuilder result = new StringBuilder();
        int pos = 0;

        for (int[] range : stringRanges) {
            // altoevidenziastringaprimaparte
            if (range[0] > pos) {
                result.append(highlightSegment(code.substring(pos, range[0]), keywords, lang));
            }
            // stringa
            result.append(AnsiStyle.YELLOW).append(code, range[0], range[1]).append(AnsiStyle.RESET);
            pos = range[1];
        }

        // dopo
        if (pos < code.length()) {
            result.append(highlightSegment(code.substring(pos), keywords, lang));
        }

        return result.toString();
    }

    /** sucodice（nessunostringa）esegueparola chiaveecaratterealtoevidenzia */
    private String highlightSegment(String segment, Set<String> keywords, String lang) {
        // annotazione（@Annotation）— Solo Java/Python
        if (lang.equals("java") || lang.equals("python") || lang.equals("py")) {
            Matcher annMatcher = ANNOTATION_PATTERN.matcher(segment);
            segment = annMatcher.replaceAll(AnsiStyle.BRIGHT_YELLOW + "$1" + AnsiStyle.RESET);
        }

        // parola chiavecolorazione —  word boundary corrisponde
        for (String kw : keywords) {
            // SQL parola chiavedimensionenon
            if (lang.equals("sql")) {
                segment = segment.replaceAll("(?i)\\b(" + Pattern.quote(kw) + ")\\b",
                        AnsiStyle.BRIGHT_CYAN + "$1" + AnsiStyle.RESET);
            } else {
                segment = segment.replaceAll("\\b(" + Pattern.quote(kw) + ")\\b",
                        AnsiStyle.BRIGHT_CYAN + "$1" + AnsiStyle.RESET);
            }
        }

        // caratterecolorazione
        Matcher numMatcher = NUMBER_PATTERN.matcher(segment);
        segment = numMatcher.replaceAll(AnsiStyle.BRIGHT_MAGENTA + "$1" + AnsiStyle.RESET);

        // true/false/null colorazione
        segment = segment.replaceAll("\\b(true|false|null|None|nil)\\b",
                AnsiStyle.BRIGHT_RED + "$1" + AnsiStyle.RESET);

        return segment;
    }

    // ==================== internoformato ====================

    /** internoformatoEsegui rendering */
    private String renderInline(String text) {
        // grassetto **text**
        text = text.replaceAll("\\*\\*(.+?)\\*\\*", AnsiStyle.BOLD + "$1" + AnsiStyle.RESET);
        // internocodice `text`
        text = text.replaceAll("`(.+?)`", AnsiStyle.BRIGHT_GREEN + "$1" + AnsiStyle.RESET);
        // corsivo *text*（corrispondegrassettoin *）
        text = text.replaceAll("(?<!\\*)\\*([^*]+?)\\*(?!\\*)", AnsiStyle.ITALIC + "$1" + AnsiStyle.RESET);
        // Eliminazione ~~text~~
        text = text.replaceAll("~~(.+?)~~", AnsiStyle.DIM + "$1" + AnsiStyle.RESET);
        // collegamento [text](url) → text (url)
        text = text.replaceAll("\\[(.+?)]\\((.+?)\\)", AnsiStyle.UNDERLINE + "$1" + AnsiStyle.RESET + AnsiStyle.DIM + " ($2)" + AnsiStyle.RESET);
        return text;
    }

    // ==================== stream rendering Markdown ====================

    /**
     * streamesegue renderingStato —— tracciablocco di codiceecc.più。
     * ogni volta REPL Conversazionecreaunnuovoistanza.
     */
    public static class StreamState {
        boolean inCodeBlock = false;
        String codeLang = "";
    }

    /**
     * esegue renderingstreamtesto（nessunoprefisso，dachiamataGestisce）。
     * <p>
     * supporta:blocco di codice（altoevidenzia）、titolo、Lista、citazione、internoformato（grassetto/corsivo/codice）。
     * blocco di codiceStatotramite {@link StreamState} 。
     *
     * @param line  una rigacompletotesto（nonnewline）
     * @param state Stato（blocco di codicetraccia）
     * @return  ANSI stileesegue renderingRisultato
     */
    public String renderStreamingLine(String line, StreamState state) {
        String stripped = line.stripLeading();

        // blocco di codiceconfine
        if (stripped.startsWith("```")) {
            if (!state.inCodeBlock) {
                state.codeLang = stripped.substring(3).strip().toLowerCase();
                state.inCodeBlock = true;
                String langLabel = state.codeLang.isEmpty() ? "code" : state.codeLang;
                return AnsiStyle.dim("┌─" + langLabel + "─" + "─".repeat(Math.max(0, 40 - langLabel.length())));
            } else {
                state.inCodeBlock = false;
                state.codeLang = "";
                return AnsiStyle.dim("└" + "─".repeat(42));
            }
        }

        // blocco di codicecontenuto（altoevidenzia）
        if (state.inCodeBlock) {
            return AnsiStyle.DIM + "│" + AnsiStyle.RESET + " " + highlightCode(line, state.codeLang);
        }

        // titolo
        if (stripped.startsWith("### ")) {
            return AnsiStyle.bold(AnsiStyle.CYAN + stripped.substring(4)) + AnsiStyle.RESET;
        } else if (stripped.startsWith("## ")) {
            return AnsiStyle.bold(AnsiStyle.BLUE + stripped.substring(3)) + AnsiStyle.RESET;
        } else if (stripped.startsWith("# ")) {
            return AnsiStyle.bold(AnsiStyle.MAGENTA + stripped.substring(2)) + AnsiStyle.RESET;
        }

        // citazione
        if (stripped.startsWith("> ")) {
            return AnsiStyle.DIM + "┃" + AnsiStyle.RESET + " " + AnsiStyle.ITALIC + renderInline(stripped.substring(2)) + AnsiStyle.RESET;
        }

        // casella di controlloLista（innessunoListaprimarileva）
        if (stripped.startsWith("- [ ] ")) {
            return AnsiStyle.DIM + "☐" + AnsiStyle.RESET + " " + renderInline(stripped.substring(6));
        }
        if (stripped.startsWith("- [x] ") || stripped.startsWith("- [X] ")) {
            return AnsiStyle.GREEN + "☑" + AnsiStyle.RESET + " " + renderInline(stripped.substring(6));
        }

        // nessunoLista
        if (stripped.startsWith("- ") || stripped.startsWith("* ")) {
            int indent = line.length() - stripped.length();
            String prefix = " ".repeat(indent);
            return prefix + AnsiStyle.CYAN + "•" + AnsiStyle.RESET + " " + renderInline(stripped.substring(2));
        }

        // c'èLista
        if (stripped.matches("^\\d+\\.\\s+.*")) {
            Matcher m = Pattern.compile("^(\\s*)(\\d+)\\.\\s+(.*)").matcher(line);
            if (m.matches()) {
                return m.group(1) + AnsiStyle.CYAN + m.group(2) + "." + AnsiStyle.RESET + " " + renderInline(m.group(3));
            }
        }

        // linea divisoria
        if (stripped.matches("^[-*]{3,}$")) {
            return AnsiStyle.dim("─".repeat(42));
        }

        // testo（internoformatoesegue rendering）
        return renderInline(line);
    }
}
