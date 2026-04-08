package com.spaceai.permission;

import com.spaceai.permission.PermissionTypes.PermissionBehavior;
import com.spaceai.permission.PermissionTypes.PermissionRule;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * impostazioni permessipersistenza —— Utenteelivello progettoregola permessiFile。
 * <p>
 * Archiviazione：
 * <ul>
 *   <li>Utente: ~/.space-ai-java/settings.json</li>
 *   <li>livello progetto: .space-ai-java/settings.json</li>
 * </ul>
 * caricapriorità: livello progetto > Utente > Sessione
 */
public class PermissionSettings {

    private static final String SETTINGS_DIR = ".space-ai-java";
    private static final String SETTINGS_FILE = "settings.json";
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** memoriainunisceRegola（dac'ècaricadopounisce） */
    private final List<PermissionRule> sessionRules = new ArrayList<>();
    private PermissionTypes.PermissionMode currentMode = PermissionTypes.PermissionMode.DEFAULT;

    private final Path userSettingsPath;
    private final Path projectSettingsPath;

    private SettingsData userData = new SettingsData();
    private SettingsData projectData = new SettingsData();

    public PermissionSettings() {
        this(Path.of(System.getProperty("user.home")),
             Path.of(System.getProperty("user.dir")));
    }

    public PermissionSettings(Path userHome, Path projectDir) {
        this.userSettingsPath = userHome.resolve(SETTINGS_DIR).resolve(SETTINGS_FILE);
        this.projectSettingsPath = projectDir.resolve(SETTINGS_DIR).resolve(SETTINGS_FILE);
    }

    /** carica tutte le impostazioni dal disco */
    public void load() {
        userData = loadFromFile(userSettingsPath);
        projectData = loadFromFile(projectSettingsPath);
        // livello progettoModalitàpriorità
        if (projectData.permissions.mode != null) {
            currentMode = projectData.permissions.mode;
        } else if (userData.permissions.mode != null) {
            currentMode = userData.permissions.mode;
        }
    }

    /** ottieni tuttiuniscedopoRegola（livello progetto > Utente > Sessione） */
    public List<PermissionRule> getAllRules() {
        var rules = new ArrayList<PermissionRule>();
        // livello progettopriorità
        rules.addAll(toRules(projectData.permissions.alwaysAllow, PermissionBehavior.ALLOW));
        rules.addAll(toRules(projectData.permissions.alwaysDeny, PermissionBehavior.DENY));
        // Utente
        rules.addAll(toRules(userData.permissions.alwaysAllow, PermissionBehavior.ALLOW));
        rules.addAll(toRules(userData.permissions.alwaysDeny, PermissionBehavior.DENY));
        // Sessione
        rules.addAll(sessionRules);
        return rules;
    }

    /** aggiungeRegolaesalvaaUtenteImposta */
    public void addUserRule(PermissionRule rule) {
        if (rule.behavior() == PermissionBehavior.ALLOW) {
            userData.permissions.alwaysAllow.add(formatRule(rule));
        } else if (rule.behavior() == PermissionBehavior.DENY) {
            userData.permissions.alwaysDeny.add(formatRule(rule));
        }
        saveToFile(userSettingsPath, userData);
    }

    /** aggiungeRegolaaSessione（nonpersistenza） */
    public void addSessionRule(PermissionRule rule) {
        sessionRules.add(rule);
    }

    /** rimuoveUtenteRegola */
    public void removeUserRule(String ruleStr) {
        userData.permissions.alwaysAllow.remove(ruleStr);
        userData.permissions.alwaysDeny.remove(ruleStr);
        saveToFile(userSettingsPath, userData);
    }

    /** cancella tuttiRegola */
    public void clearAll() {
        userData.permissions.alwaysAllow.clear();
        userData.permissions.alwaysDeny.clear();
        projectData.permissions.alwaysAllow.clear();
        projectData.permissions.alwaysDeny.clear();
        sessionRules.clear();
        saveToFile(userSettingsPath, userData);
    }

    public PermissionTypes.PermissionMode getCurrentMode() {
        return currentMode;
    }

    public void setCurrentMode(PermissionTypes.PermissionMode mode) {
        this.currentMode = mode;
        userData.permissions.mode = mode;
        saveToFile(userSettingsPath, userData);
    }

    /** ottieni tuttigiàsalvaRegolapuòLista */
    public List<String> listRules() {
        var result = new ArrayList<String>();
        for (var r : userData.permissions.alwaysAllow) {
            result.add("[user] ALLOW " + r);
        }
        for (var r : userData.permissions.alwaysDeny) {
            result.add("[user] DENY  " + r);
        }
        for (var r : projectData.permissions.alwaysAllow) {
            result.add("[proj] ALLOW " + r);
        }
        for (var r : projectData.permissions.alwaysDeny) {
            result.add("[proj] DENY  " + r);
        }
        for (var r : sessionRules) {
            result.add("[sess] " + r.behavior() + " " + formatRule(r));
        }
        return result;
    }

    // ── Internometodo ──

    private SettingsData loadFromFile(Path path) {
        if (!Files.exists(path)) return new SettingsData();
        try {
            return MAPPER.readValue(path.toFile(), SettingsData.class);
        } catch (IOException e) {
            return new SettingsData();
        }
    }

    private void saveToFile(Path path, SettingsData data) {
        try {
            Files.createDirectories(path.getParent());
            MAPPER.writeValue(path.toFile(), data);
        } catch (IOException e) {
            // Fallimento，non influenza il principalestream
        }
    }

    private List<PermissionRule> toRules(List<String> ruleStrings, PermissionBehavior behavior) {
        return ruleStrings.stream()
                .map(s -> parseRule(s, behavior))
                .toList();
    }

    /** analisiRegolastringa，formato: "ToolName(pattern)" o "ToolName" */
    static PermissionRule parseRule(String ruleStr, PermissionBehavior behavior) {
        int parenStart = ruleStr.indexOf('(');
        if (parenStart > 0 && ruleStr.endsWith(")")) {
            String toolName = ruleStr.substring(0, parenStart);
            String content = ruleStr.substring(parenStart + 1, ruleStr.length() - 1);
            return new PermissionRule(toolName, content, behavior);
        }
        return PermissionRule.forTool(ruleStr, behavior);
    }

    /** formatoRegolacomestringa */
    static String formatRule(PermissionRule rule) {
        if ("*".equals(rule.ruleContent())) {
            return rule.toolName();
        }
        return rule.toolName() + "(" + rule.ruleContent() + ")";
    }

    // ── JSON dati ──

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class SettingsData {
        public PermissionsBlock permissions = new PermissionsBlock();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PermissionsBlock {
        public PermissionTypes.PermissionMode mode;
        public List<String> alwaysAllow = new ArrayList<>();
        public List<String> alwaysDeny = new ArrayList<>();
        public List<String> additionalDirectories = new ArrayList<>();
    }

    /** Imposta la modalità permessi (usato nei test) */
    public void setMode(PermissionTypes.PermissionMode mode) {
        this.currentMode = mode;
    }

}
