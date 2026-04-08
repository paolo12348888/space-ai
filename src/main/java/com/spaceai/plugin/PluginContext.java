package com.spaceai.plugin;

import com.spaceai.tool.ToolContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Plugincontesto —— fornisce ai plugin un'interfaccia per accedere alle funzionalità core dell'applicazione.
 * <p>
 * ogniPlugininInizializzazioneaun {@code PluginContext} istanza，
 * package：
 * <ul>
 *   <li>{@link ToolContext} —— Strumentoeseguecontesto（lavoroDirectory、condivisoStato）</li>
 *   <li>lavoroDirectoryPercorso</li>
 *   <li>PluginLogger dedicato —— prefisso log "plugin.{pluginId}"</li>
 * </ul>
 *
 * <p>questoclassecitazioneCampoindopoImmutabile（shallowly immutable），mac'è
 * {@link ToolContext} sìpuò，più plugincondivisoistanza.</p>
 */
public class PluginContext {

    private final ToolContext toolContext;
    private final String workDir;
    private final Logger pluginLogger;

    /**
     * creaPlugincontesto。
     *
     * @param toolContext Strumentoeseguecontesto，non puòè null
     * @param workDir     CorrentelavoroDirectoryPercorso，non puòè null
     * @param pluginId    Pluginidentificatore，increaLogger dedicato，non puòè null
     * @throws NullPointerException Sequalsiasi parametro è null
     */
    public PluginContext(ToolContext toolContext, String workDir, String pluginId) {
        this.toolContext = Objects.requireNonNull(toolContext, "toolContext cannot be null");
        this.workDir = Objects.requireNonNull(workDir, "workDir cannot be null");
        Objects.requireNonNull(pluginId, "pluginId cannot be null");
        this.pluginLogger = LoggerFactory.getLogger("plugin." + pluginId);
    }

    /**
     * ottieniStrumentoeseguecontesto。
     * <p>
     * tramite ToolContext puòconlavoroDirectory、ModelloInformazioneecondivisoStato。
     *
     * @return Strumentoeseguecontesto
     */
    public ToolContext getToolContext() {
        return toolContext;
    }

    /**
     * ottieniCorrentelavoroDirectoryPercorso。
     *
     * @return lavoroDirectorysuPercorsostringa
     */
    public String getWorkDir() {
        return workDir;
    }

    /**
     * ottieniPluginLogger dedicato。
     * <p>
     * Lognome serverformatocome "plugin.{pluginId}"，inLogindivididiversoPluginOutput。
     *
     * @return SLF4J Logger istanza
     */
    public Logger getLogger() {
        return pluginLogger;
    }
}
