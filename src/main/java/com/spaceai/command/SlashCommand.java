package com.spaceai.command;

import java.util.List;

/**
 * Interfaccia comandi slash — corrisponde al tipo Command in space-ai/src/commands.ts.
 * <p>
 * Gestisce i comandi utente che iniziano con /.
 */
public interface SlashCommand {

    /** Nome del comando (senza prefisso /) */
    String name();

    /** Descrizione del comando */
    String description();

    /** Lista degli alias del comando */
    default List<String> aliases() {
        return List.of();
    }

    /**
     * Esegue il comando.
     *
     * @param args    Argomenti del comando (parte dopo il nome del comando)
     * @param context Contesto di esecuzione del comando
     * @return Testo di output del comando
     */
    String execute(String args, CommandContext context);
}
