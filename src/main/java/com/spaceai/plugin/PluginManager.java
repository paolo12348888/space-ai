package com.spaceai.plugin;

import com.spaceai.command.CommandRegistry;
import com.spaceai.command.SlashCommand;
import com.spaceai.tool.Tool;
import com.spaceai.tool.ToolContext;
import com.spaceai.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * gestione plugin — negativoPlugincarica、registraeCiclo di vita。
 * <p>
 * Corrisponde a space-ai meccanismo di caricamento plugin in, supporta il caricamento dinamico di plugin da JAR esterni.
 *
 * <h3>caricamento pluginmodalità</h3>
 * <ol>
 *   <li><b>plugin globali</b>：da {@code ~/.space-ai-java/plugins/} DirectoryCarica JAR File</li>
 *   <li><b>progettoPlugin</b>：carica file JAR dalla directory {@code .space-ai/plugins/} del progetto</li>
 * </ol>
 *
 * <h3>JAR Plugin</h3>
 * <ul>
 *   <li>{@code META-INF/MANIFEST.MF} deve contenere l'attributo {@code Plugin-Class} in</li>
 *   <li>specificatoclasseObbligatorioImplementazione {@link Plugin} Interfaccia</li>
 *   <li>classeObbligatorioc'ènessuno</li>
 * </ul>
 *
 * <h3>Threadsicurezza</h3>
 * <p>
 * PluginListausa {@link CopyOnWriteArrayList} per l'archiviazione, supporta la lettura concorrente.
 * caricaedisinstallaoperazionenonsìatomico，inapplicazioneavviofaseodaThreadesegue。
 * </p>
 *
 * @see Plugin
 * @see PluginContext
 */
public class PluginManager {

    private static final Logger log = LoggerFactory.getLogger(PluginManager.class);

    /** caricatoPluginInformazioneLista， COW ListaConcorrentesicurezza */
    private final List<PluginInfo> plugins = new CopyOnWriteArrayList<>();

    /** Strumentoeseguecontesto，ogniPlugin */
    private final ToolContext toolContext;

    /** directory plugin globale: ~/.space-ai-java/plugins/ */
    private final Path globalPluginDir;

    /** progettoPluginDirectory：{project}/.space-ai/plugins/ */
    private final Path projectPluginDir;

    /**
     * creagestione plugin.
     *
     * @param toolContext Strumentoeseguecontesto，non puòè null
     * @throws NullPointerException Se toolContext è null
     */
    public PluginManager(ToolContext toolContext) {
        this.toolContext = Objects.requireNonNull(toolContext, "toolContext cannot be null");
        this.globalPluginDir = Path.of(
                System.getProperty("user.home"), ".space-ai-java", "plugins");
        this.projectPluginDir = toolContext.getWorkDir().resolve(".space-ai").resolve("plugins");
    }

    /**
     * Scansiona ecaricac'èPluginDirectoryinPlugin。
     * <p>
     * scansiona in sequenza la directory plugin globale e quella del progetto.
     * caricaFallimentosingoloPluginnonaltriPlugincarica。
     */
    public void loadAll() {
        loadFromDirectory(globalPluginDir, "global");
        loadFromDirectory(projectPluginDir, "project");
        log.info("Loaded {} plugins in total", plugins.size());
    }

    /**
     * scansiona e carica tutti i plugin JAR dalla directory specificata.
     *
     * @param dir   PluginDirectoryPercorso
     * @param scope Pluginidentificatore scope ("global" o "project"）
     */
    private void loadFromDirectory(Path dir, String scope) {
        if (!Files.isDirectory(dir)) {
            log.debug("Plugin directory does not exist, skipping: {}", dir);
            return;
        }
        try (var stream = Files.list(dir)) {
            stream.filter(p -> p.toString().endsWith(".jar"))
                    .forEach(jar -> loadJarPlugin(jar, scope));
        } catch (IOException e) {
            log.warn("Failed to scan plugin directory: {}", dir, e);
        }
    }

    /**
     * caricasingolo JAR Plugin。
     * <p>
     * stream：
     * <ol>
     *   <li>Leggi JAR  MANIFEST.MF，Ottieni Plugin-Class attributo</li>
     *   <li>carica le classi plugin con URLClassLoader</li>
     *   <li>ValidazionePluginclasseImplementazione {@link Plugin} Interfaccia</li>
     *   <li>istanzaeInizializzazionePlugin</li>
     *   <li>verràPluginInformazioneaggiungeacaricatoLista</li>
     * </ol>
     *
     * @param jarPath JAR FilePercorso
     * @param scope   Pluginscope
     */
    private void loadJarPlugin(Path jarPath, String scope) {
        // ：da JAR inLeggi Plugin-Class attributo（ try-with-resources chiude）
        String pluginClassName;
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            Manifest manifest = jar.getManifest();
            pluginClassName = (manifest != null)
                    ? manifest.getMainAttributes().getValue("Plugin-Class")
                    : null;
        } catch (IOException e) {
            log.error("Failed to read JAR manifest: {}", jarPath.getFileName(), e);
            return;
        }

        if (pluginClassName == null) {
            log.warn("JAR {} missing Plugin-Class attribute, skipping", jarPath.getFileName());
            return;
        }

        // ：caricaeistanzaPlugin（FallimentoChiudi ClassLoader）
        URLClassLoader loader = null;
        boolean success = false;
        try {
            // creaclass loader，conCorrenteclass loadercomecaricatore
            loader = new URLClassLoader(
                    new URL[]{jarPath.toUri().toURL()},
                    getClass().getClassLoader()
            );

            Class<?> clazz = loader.loadClass(pluginClassName);
            if (!Plugin.class.isAssignableFrom(clazz)) {
                log.warn("{} does not implement Plugin interface, skipping", pluginClassName);
                return;
            }

            Plugin plugin = (Plugin) clazz.getDeclaredConstructor().newInstance();

            // controllaPlugin ID seripete
            if (findPlugin(plugin.id()) != null) {
                log.warn("Plugin ID '{}' already exists, skipping duplicate load: {}", plugin.id(), jarPath.getFileName());
                return;
            }

            // creaPlugincontestoeInizializzazione
            PluginContext ctx = new PluginContext(
                    toolContext, toolContext.getWorkDir().toString(), plugin.id());
            plugin.initialize(ctx);

            plugins.add(new PluginInfo(plugin, scope, jarPath, loader));
            log.info("Loaded plugin: {} v{} [{}] ({})", plugin.name(), plugin.version(), plugin.id(), scope);
            success = true;

        } catch (Exception e) {
            log.error("Failed to load plugin: {}", jarPath.getFileName(), e);
        } finally {
            // SoloincaricaFallimentochiudeclass loader；Successoda PluginInfo c'è
            if (!success) {
                safeClose(loader);
            }
        }
    }

    /**
     * carica un singolo plugin dal percorso JAR specificato (per il caricamento dinamico a runtime).
     *
     * @param jarPath JAR FilePercorso
     * @return caricaSuccessoRestituisce true，noRestituisce false
     */
    public boolean loadPlugin(Path jarPath) {
        if (!Files.isRegularFile(jarPath) || !jarPath.toString().endsWith(".jar")) {
            log.warn("Invalid plugin path: {}", jarPath);
            return false;
        }
        loadJarPlugin(jarPath, "dynamic");
        // tramitecontrollasec'èPluginchiusoquesto JAR PercorsosecaricaSuccesso
        return plugins.stream().anyMatch(info -> jarPath.equals(info.jarPath()));
    }

    /**
     * verràc'ècaricatoPluginregistrazione strumentia ToolRegistry。
     *
     * @param toolRegistry registrazione strumenticentro
     */
    public void registerTools(ToolRegistry toolRegistry) {
        for (PluginInfo info : plugins) {
            for (Tool tool : info.plugin().getTools()) {
                toolRegistry.register(tool);
                log.debug("Registered plugin tool: {} (from {})", tool.name(), info.plugin().name());
            }
        }
    }

    /**
     * verràc'ècaricatoPluginregistrazione comandia CommandRegistry。
     *
     * @param commandRegistry registrazione comandicentro
     */
    public void registerCommands(CommandRegistry commandRegistry) {
        for (PluginInfo info : plugins) {
            for (SlashCommand cmd : info.plugin().getCommands()) {
                commandRegistry.register(cmd);
                log.debug("Registered plugin command: /{} (from {})", cmd.name(), info.plugin().name());
            }
        }
    }

    /**
     * disinstalla specificato ID Plugin。
     * <p>
     * chiamataPlugin {@link Plugin#destroy()} metodo，echiudeil suoclass loader。
     * Attenzione：registratoa ToolRegistry / CommandRegistry StrumentoeComandononAutomaticamenterimuove。
     * <p>
     * usa {@link CopyOnWriteArrayList#remove(Object)} invece dell'eliminazione tramite iteratore,
     * come CopyOnWriteArrayList iterazionenonsupporta remove operazione。
     *
     * @param pluginId Pluginidentificatore univoco
     * @return disinstallaSuccessoRestituisce true，non ancoraaRestituisce false
     */
    public boolean unload(String pluginId) {
        for (PluginInfo info : plugins) {
            if (info.plugin().id().equals(pluginId)) {
                try {
                    info.plugin().destroy();
                } catch (Exception e) {
                    log.warn("Plugin {} exception during destroy", pluginId, e);
                }
                safeClose(info.classLoader());
                plugins.remove(info); // CopyOnWriteArrayList.remove(Object) sìsicurezza
                log.info("Unloaded plugin: {} ({})", info.plugin().name(), pluginId);
                return true;
            }
        }
        log.warn("Plugin not found: {}", pluginId);
        return false;
    }

    /**
     * ottieni tutticaricatoPluginInformazione（snapshot）。
     *
     * @return ImmutabilePluginInformazioneLista
     */
    public List<PluginInfo> getPlugins() {
        return List.copyOf(plugins);
    }

    /**
     * in base a ID cercacaricatoPluginInformazione。
     *
     * @param pluginId Pluginidentificatore univoco
     * @return PluginInformazione，non ancoraaRestituisce null
     */
    public PluginInfo findPlugin(String pluginId) {
        for (PluginInfo info : plugins) {
            if (info.plugin().id().equals(pluginId)) {
                return info;
            }
        }
        return null;
    }

    /**
     * ottienicaricatoPluginriepilogoInformazione。
     *
     * @return formato -izzatoPluginListastringa
     */
    public String getSummary() {
        if (plugins.isEmpty()) {
            return "No plugins loaded";
        }
        StringBuilder sb = new StringBuilder();
        for (PluginInfo info : plugins) {
            Plugin p = info.plugin();
            sb.append(String.format("  %s v%s [%s] - %s (tools: %d, commands: %d)%n",
                    p.name(), p.version(), info.scope(), p.description(),
                    p.getTools().size(), p.getCommands().size()));
        }
        return sb.toString();
    }

    /**
     * chiudec'èPluginerisorse。
     * <p>
     * chiama il metodo {@link Plugin#destroy()} di ogni plugin in sequenza,
     * dopochiudeCorrisponde aclass loader。questometodoinapplicazionechiudechiamata。
     */
    public void shutdown() {
        log.info("Shutting down {} plugins...", plugins.size());
        for (PluginInfo info : plugins) {
            try {
                info.plugin().destroy();
            } catch (Exception e) {
                log.warn("Plugin {} exception during destroy", info.plugin().id(), e);
            }
            safeClose(info.classLoader());
        }
        plugins.clear();
        log.info("All plugins shut down");
    }

    /**
     * sicurezzaChiudi AutoCloseable risorse，eccezioneSoloRegistra DEBUG Log。
     *
     * @param closeable chiuderisorse，puòconè null
     */
    private void safeClose(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.debug("Exception closing resource ({}): {}",
                        closeable.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /**
     * PluginInformazioneRegistra —— caricatoPluginmetadati。
     *
     * @param plugin      Pluginistanza
     * @param scope       Pluginscope ("global"、"project" o "dynamic"）
     * @param jarPath     JAR FilePercorso
     * @param classLoader Pluginclass loader，internoPluginè null
     */
    public record PluginInfo(
            Plugin plugin,
            String scope,
            Path jarPath,
            URLClassLoader classLoader
    ) {
    }
}
