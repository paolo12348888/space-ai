package com.spaceai.plugin;

import com.spaceai.tool.Tool;
import com.spaceai.command.SlashCommand;

import java.util.List;

/**
 * PluginInterfaccia —— Corrisponde a space-ai modulo plugins/ in
 * <p>
 * PluginpuòconfornisceesternoStrumentoeComando，estensioneFunzionalità principali。
 * ogniPluginc'èCiclo di vita：Inizializza → Esecuzione → Distruzione。
 *
 * <h3>Implementazione</h3>
 * <ul>
 *   <li>ogniPluginObbligatorioc'èunivoco {@link #id()}， kebab-case formato（ "my-plugin"）</li>
 *   <li>{@link #initialize(PluginContext)} incaricachiamatauna volta，inInizializzazionerisorse</li>
 *   <li>{@link #getTools()} e {@link #getCommands()} ritornaPluginfornito daestensione</li>
 *   <li>{@link #destroy()} indisinstallachiamata，risorse</li>
 * </ul>
 *
 * <h3>JAR Pluginpackage</h3>
 * <p>
 * packagecome JAR richiedein {@code META-INF/MANIFEST.MF} inspecificato：
 * <pre>
 * Plugin-Class: com.example.MyPlugin
 * </pre>
 */
public interface Plugin {

    /**
     * Pluginidentificatore univoco。
     * <p>
     *  kebab-case formato， "output-style"、"git-helper"。
     * identificatoreinapplicazioneCiclo di vitainternoObbligatoriounivoco。
     *
     * @return vuotoPluginidentificatorestringa
     */
    String id();

    /**
     * Pluginvisualizzanome。
     *
     * @return nome leggibile del plugin
     */
    String name();

    /**
     * PluginVersione。
     * <p>
     * Versione（SemVer）， "1.0.0"。
     *
     * @return Versionestringa
     */
    String version();

    /**
     * PluginDescrizione delle funzionalità。
     *
     * @return breveDescrizione delle funzionalità
     */
    String description();

    /**
     * InizializzazionePlugin。
     * <p>
     * incaricamento plugindopochiamata，in：
     * <ul>
     *   <li>InizializzazioneInternoStatoerisorse</li>
     *   <li>leggeConfigurazione</li>
     *   <li>stabilisceEsternoConnessione</li>
     * </ul>
     * SeInizializzazioneFallimentoLanciaeccezione，Pluginverrànonregistra。
     *
     * @param context Plugincontesto，fornisceapplicazioneFunzionalità principaliInterfaccia
     * @throws RuntimeException InizializzazioneFallimentoLancia
     */
    void initialize(PluginContext context);

    /**
     * ottieniPluginfornito daStrumentoLista。
     * <p>
     * ritornaStrumentoverràregistra in {@link com.spaceai.tool.ToolRegistry}，
     * può LLM chiamata。
     *
     * @return StrumentoLista，Predefinitolista vuota
     */
    default List<Tool> getTools() {
        return List.of();
    }

    /**
     * ottieniPluginfornito dacomando slashLista。
     * <p>
     * ritornaComandoverràregistra in {@link com.spaceai.command.CommandRegistry}，
     * Utentepuòtramite /{@code name} chiamata。
     *
     * @return ComandoLista，Predefinitolista vuota
     */
    default List<SlashCommand> getCommands() {
        return List.of();
    }

    /**
     * DistruzionePlugin，risorse。
     * <p>
     * inPlugindisinstallaoapplicazionechiudechiamata。Implementazione：
     * <ul>
     *   <li>chiudeapreConnessioneestream</li>
     *   <li>ferma il thread in background</li>
     *   <li>Esternorisorse</li>
     * </ul>
     * questometodononLanciaeccezione。
     */
    default void destroy() {
    }
}
