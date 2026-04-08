package com.spaceai.console;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * esegue rendering — Corrisponde a space-ai in diff/ Componente。
 * verrà unified diff formatotestoesegue renderingcometerminaleOutput。
 */
public class DiffRenderer {

    private static final Pattern DIFF_HEADER = Pattern.compile("^diff --git a/(.*) b/(.*)$");
    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@(.*)$");
    private static final Pattern STAT_LINE = Pattern.compile("^\\s*(.+?)\\s*\\|\\s*(\\d+)\\s*([+\\-]*)\\s*$");
    private static final Pattern BINARY_FILE = Pattern.compile("^Binary files .* differ$");
    private static final Pattern RENAME_FROM = Pattern.compile("^rename from (.+)$");
    private static final Pattern RENAME_TO = Pattern.compile("^rename to (.+)$");

    /**
     * Esegui rendering unified diff per output colorato nel terminale.
     */
    public static String render(String diffText) {
        if (diffText == null || diffText.isBlank()) {
            return AnsiStyle.dim("  (no changes)");
        }

        StringBuilder sb = new StringBuilder();
        for (String line : diffText.split("\n")) {
            if (line.startsWith("diff --git")) {
                sb.append(AnsiStyle.bold(line)).append("\n");
            } else if (line.startsWith("---") || line.startsWith("+++")) {
                sb.append(AnsiStyle.bold(line)).append("\n");
            } else if (line.startsWith("@@")) {
                sb.append(AnsiStyle.cyan(line)).append("\n");
            } else if (line.startsWith("+")) {
                sb.append(AnsiStyle.green(line)).append("\n");
            } else if (line.startsWith("-")) {
                sb.append(AnsiStyle.red(line)).append("\n");
            } else if (line.startsWith("index ") || line.startsWith("new file") || line.startsWith("deleted file")) {
                sb.append(AnsiStyle.dim(line)).append("\n");
            } else if (BINARY_FILE.matcher(line).matches()) {
                sb.append(AnsiStyle.yellow(line)).append("\n");
            } else if (line.startsWith("rename ")) {
                sb.append(AnsiStyle.magenta(line)).append("\n");
            } else {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * Esegui rendering diff e。
     */
    public static String renderWithLineNumbers(String diffText) {
        if (diffText == null || diffText.isBlank()) {
            return AnsiStyle.dim("  (no changes)");
        }

        List<DiffFile> files = parse(diffText);
        StringBuilder sb = new StringBuilder();

        for (DiffFile file : files) {
            sb.append(AnsiStyle.bold("━━━ " + file.newPath() + " ━━━")).append("\n");

            if (file.binary()) {
                sb.append(AnsiStyle.yellow("  Binary file")).append("\n\n");
                continue;
            }

            for (DiffHunk hunk : file.hunks()) {
                sb.append(AnsiStyle.cyan(hunk.header())).append("\n");

                for (DiffLine dl : hunk.lines()) {
                    String oldNum = dl.oldLineNum() > 0 ? String.format("%4d", dl.oldLineNum()) : "    ";
                    String newNum = dl.newLineNum() > 0 ? String.format("%4d", dl.newLineNum()) : "    ";
                    String lineNums = AnsiStyle.dim(oldNum + " " + newNum + " │ ");

                    switch (dl.type()) {
                        case ADD -> sb.append(lineNums).append(AnsiStyle.green("+ " + dl.content())).append("\n");
                        case REMOVE -> sb.append(lineNums).append(AnsiStyle.red("- " + dl.content())).append("\n");
                        case CONTEXT -> sb.append(lineNums).append("  " + dl.content()).append("\n");
                        case HEADER -> sb.append(AnsiStyle.cyan(dl.content())).append("\n");
                    }
                }
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Esegui rendering stat riepilogo（classe git diff --stat）。
     */
    public static String renderStat(String diffText) {
        if (diffText == null || diffText.isBlank()) {
            return AnsiStyle.dim("  (no changes)");
        }

        List<DiffFile> files = parse(diffText);
        if (files.isEmpty()) {
            return AnsiStyle.dim("  (no changes)");
        }

        int totalAdded = 0, totalRemoved = 0;
        int maxNameLen = 0;
        List<FileStat> stats = new ArrayList<>();

        for (DiffFile file : files) {
            int added = 0, removed = 0;
            for (DiffHunk hunk : file.hunks()) {
                for (DiffLine line : hunk.lines()) {
                    if (line.type() == DiffLine.Type.ADD) added++;
                    else if (line.type() == DiffLine.Type.REMOVE) removed++;
                }
            }
            String name = file.newPath();
            if (name.length() > maxNameLen) maxNameLen = name.length();
            stats.add(new FileStat(name, added, removed, file.binary()));
            totalAdded += added;
            totalRemoved += removed;
        }

        StringBuilder sb = new StringBuilder();
        int barWidth = 40;

        for (FileStat fs : stats) {
            String name = String.format(" %-" + (maxNameLen + 2) + "s", fs.name());

            if (fs.binary()) {
                sb.append(name).append(AnsiStyle.dim(" | ")).append(AnsiStyle.yellow("Bin")).append("\n");
                continue;
            }

            int total = fs.added() + fs.removed();
            sb.append(name).append(AnsiStyle.dim(" | ")).append(String.format("%4d ", total));

            // 
            int maxBar = Math.min(total, barWidth);
            int addBar = total > 0 ? (int) Math.round((double) fs.added() / total * maxBar) : 0;
            int remBar = maxBar - addBar;
            sb.append(AnsiStyle.green("+".repeat(addBar)));
            sb.append(AnsiStyle.red("-".repeat(remBar)));
            sb.append("\n");
        }

        sb.append(AnsiStyle.dim(String.format(" %d file%s changed", files.size(), files.size() == 1 ? "" : "s")));
        if (totalAdded > 0) sb.append(AnsiStyle.green(String.format(", %d insertion%s(+)", totalAdded, totalAdded == 1 ? "" : "s")));
        if (totalRemoved > 0) sb.append(AnsiStyle.red(String.format(", %d deletion%s(-)", totalRemoved, totalRemoved == 1 ? "" : "s")));
        sb.append("\n");

        return sb.toString();
    }

    /**
     * Analizza unified diff come oggetto strutturato.
     */
    public static List<DiffFile> parse(String diffText) {
        if (diffText == null || diffText.isBlank()) return List.of();

        List<DiffFile> files = new ArrayList<>();
        String[] lines = diffText.split("\n");
        int i = 0;

        while (i < lines.length) {
            Matcher headerMatch = DIFF_HEADER.matcher(lines[i]);
            if (!headerMatch.matches()) {
                i++;
                continue;
            }

            String oldPath = headerMatch.group(1);
            String newPath = headerMatch.group(2);
            i++;

            // saltaestensione（index, mode, rename ecc.)
            boolean binary = false;
            while (i < lines.length && !lines[i].startsWith("---") && !lines[i].startsWith("diff --git") && !lines[i].startsWith("@@")) {
                if (BINARY_FILE.matcher(lines[i]).matches()) binary = true;
                if (lines[i].startsWith("rename to ")) {
                    Matcher rm = RENAME_TO.matcher(lines[i]);
                    if (rm.matches()) newPath = rm.group(1);
                }
                i++;
            }

            if (binary) {
                files.add(new DiffFile(oldPath, newPath, List.of(), true));
                continue;
            }

            // salta --- e +++ 
            if (i < lines.length && lines[i].startsWith("---")) i++;
            if (i < lines.length && lines[i].startsWith("+++")) i++;

            // Analizza hunks
            List<DiffHunk> hunks = new ArrayList<>();
            while (i < lines.length && !lines[i].startsWith("diff --git")) {
                Matcher hunkMatch = HUNK_HEADER.matcher(lines[i]);
                if (!hunkMatch.matches()) {
                    i++;
                    continue;
                }

                int oldStart = Integer.parseInt(hunkMatch.group(1));
                int oldCount = hunkMatch.group(2) != null ? Integer.parseInt(hunkMatch.group(2)) : 1;
                int newStart = Integer.parseInt(hunkMatch.group(3));
                int newCount = hunkMatch.group(4) != null ? Integer.parseInt(hunkMatch.group(4)) : 1;
                String hunkHeader = lines[i];
                i++;

                List<DiffLine> diffLines = new ArrayList<>();
                int oldLine = oldStart;
                int newLine = newStart;

                while (i < lines.length && !lines[i].startsWith("diff --git") && !lines[i].startsWith("@@")) {
                    String line = lines[i];
                    if (line.startsWith("+")) {
                        diffLines.add(new DiffLine(DiffLine.Type.ADD, line.substring(1), -1, newLine++));
                    } else if (line.startsWith("-")) {
                        diffLines.add(new DiffLine(DiffLine.Type.REMOVE, line.substring(1), oldLine++, -1));
                    } else if (line.startsWith(" ")) {
                        diffLines.add(new DiffLine(DiffLine.Type.CONTEXT, line.substring(1), oldLine++, newLine++));
                    } else if (line.startsWith("\\")) {
                        // "\ No newline at end of file" — salta
                    } else {
                        break;
                    }
                    i++;
                }

                hunks.add(new DiffHunk(oldStart, oldCount, newStart, newCount, hunkHeader, List.copyOf(diffLines)));
            }

            files.add(new DiffFile(oldPath, newPath, List.copyOf(hunks), false));
        }

        return List.copyOf(files);
    }

    // ==================== Tipo ====================

    /** File */
    public record DiffFile(String oldPath, String newPath, List<DiffHunk> hunks, boolean binary) {}

    /**  */
    public record DiffHunk(int oldStart, int oldCount, int newStart, int newCount, String header, List<DiffLine> lines) {}

    /**  */
    public record DiffLine(Type type, String content, int oldLineNum, int newLineNum) {
        public enum Type { CONTEXT, ADD, REMOVE, HEADER }
    }

    /** File（Interno） */
    private record FileStat(String name, int added, int removed, boolean binary) {}
}
