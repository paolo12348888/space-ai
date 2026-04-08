package com.spaceai.command;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test unitari per CommandRegistry.
 * Verifica registrazione, dispatch e gestione degli alias.
 */
@DisplayName("CommandRegistry — registro e dispatch comandi slash")
class CommandRegistryTest {

    private CommandRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new CommandRegistry();
    }

    /** Comando stub per i test */
    private SlashCommand fakeCommand(String name, String... aliases) {
        return new SlashCommand() {
            @Override public String name() { return name; }
            @Override public String description() { return "Comando di test: " + name; }
            @Override public List<String> aliases() { return List.of(aliases); }
            @Override public String execute(String args, CommandContext ctx) {
                return "eseguito:" + name + (args.isBlank() ? "" : ":" + args);
            }
        };
    }

    @Test
    @DisplayName("Registrazione singola: comando trovato per nome")
    void register_commandFoundByName() {
        registry.register(fakeCommand("help"));
        assertThat(registry.getCommandNames()).contains("help");
    }

    @Test
    @DisplayName("Registrazione con alias: comando trovato anche tramite alias")
    void register_commandFoundByAlias() {
        registry.register(fakeCommand("exit", "quit", "q"));
        assertThat(registry.getCommandNames()).contains("exit", "quit", "q");
    }

    @Test
    @DisplayName("getCommands() restituisce ogni comando una sola volta (no duplicati alias)")
    void getCommands_noDuplicates() {
        registry.register(fakeCommand("version", "ver", "v"));
        long unique = registry.getCommands().stream()
            .filter(c -> c.name().equals("version"))
            .count();
        assertThat(unique).isEqualTo(1);
    }

    @Test
    @DisplayName("dispatch('/help') → esegue il comando")
    void dispatch_knownCommand_executes() {
        registry.register(fakeCommand("help"));
        var result = registry.dispatch("/help", null);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("eseguito:help");
    }

    @Test
    @DisplayName("dispatch('/help arg1') → passa gli argomenti al comando")
    void dispatch_withArguments_passesArgs() {
        registry.register(fakeCommand("model"));
        var result = registry.dispatch("/model gpt-4o", null);
        assertThat(result).isPresent();
        assertThat(result.get()).isEqualTo("eseguito:model:gpt-4o");
    }

    @Test
    @DisplayName("dispatch() con alias → funziona come il nome principale")
    void dispatch_viaAlias_works() {
        registry.register(fakeCommand("exit", "quit"));
        var result = registry.dispatch("/quit", null);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("eseguito:exit");
    }

    @Test
    @DisplayName("dispatch() di comando sconosciuto → messaggio di errore")
    void dispatch_unknownCommand_returnsError() {
        var result = registry.dispatch("/nonexistent", null);
        assertThat(result).isPresent();
        assertThat(result.get()).containsIgnoringCase("unknown");
    }

    @Test
    @DisplayName("dispatch() senza slash → restituisce empty")
    void dispatch_noSlash_returnsEmpty() {
        registry.register(fakeCommand("help"));
        var result = registry.dispatch("help", null);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("isCommand() → true solo se inizia con /")
    void isCommand_trueOnlyForSlashPrefix() {
        assertThat(registry.isCommand("/help")).isTrue();
        assertThat(registry.isCommand("/clear")).isTrue();
        assertThat(registry.isCommand("help")).isFalse();
        assertThat(registry.isCommand("")).isFalse();
        assertThat(registry.isCommand(null)).isFalse();
    }

    @Test
    @DisplayName("Case insensitive: '/HELP' trova il comando 'help'")
    void dispatch_caseInsensitive() {
        registry.register(fakeCommand("help"));
        var result = registry.dispatch("/HELP", null);
        assertThat(result).isPresent();
        assertThat(result.get()).contains("eseguito:help");
    }

    @Test
    @DisplayName("registerAll() registra più comandi in una sola chiamata")
    void registerAll_registersMultiple() {
        registry.registerAll(fakeCommand("help"), fakeCommand("clear"), fakeCommand("exit"));
        assertThat(registry.getCommandNames()).contains("help", "clear", "exit");
        assertThat(registry.getCommands()).hasSize(3);
    }
}
