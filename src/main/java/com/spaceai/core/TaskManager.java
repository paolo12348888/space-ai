package com.spaceai.core;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

/**
 * gestore attività —— Corrisponde a space-ai funzionalità TaskCreate / TaskGet / TaskList / TaskUpdate in
 * <p>
 * dopoattivitàcrea、esegue、QueryeAggiornamento。
 * c'èmetodoThreadsicurezza，Interno {@link ConcurrentHashMap} Archiviazioneattività、
 * usa un pool di thread daemon per l'esecuzione asincrona di attività con corpo di lavoro {@link Callable}.
 * </p>
 *
 * <h3>due modalità di creazione</h3>
 * <ul>
 *   <li>{@link #createTask(String, Callable)} —— AutomaticamenteesegueModalità：dopoindopoThreadinEsecuzione</li>
 *   <li>{@link #createManualTask(String)} —— Modalità di gestione manuale: crea solo record in stato PENDING,
 *       daEsternotramite {@link #updateTask} per guidare le transizioni di stato</li>
 * </ul>
 */
public class TaskManager {

    /* ------------------------------------------------------------------ */
    /*  stato attivitàenumerazione                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * stato del ciclo di vita dell'attività.
     */
    public enum TaskStatus {
        /** giàcrea，attendeEsegui */
        PENDING,
        /** in esecuzione */
        RUNNING,
        /** esegueSuccessocompletamento */
        COMPLETED,
        /** esegueFallimento */
        FAILED,
        /** annullato */
        CANCELLED
    }

    /* ------------------------------------------------------------------ */
    /*  registro informazioni attività                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Immutabileattivitàsnapshot。ogni voltaStatomodifichecreanuovo {@code TaskInfo} istanzascriveMappaTabella，
     * garantendo che il lato lettura ottenga sempre uno snapshot coerente.
     *
     * @param id          ID univoco dell'attività (primi 8 caratteri dell'UUID)
     * @param description descrizione attività
     * @param status      CorrenteStato
     * @param result      esegueRisultato（può essere {@code null}）
     * @param createdAt   crea
     * @param updatedAt   ora dell'ultimo aggiornamento
     * @param metadata    aggiuntivometadati（Immutabile）
     */
    public record TaskInfo(
            String id,
            String description,
            TaskStatus status,
            String result,
            Instant createdAt,
            Instant updatedAt,
            Map<String, String> metadata
    ) {
        /**
         * creaunAggiornamentoStato、Risultatoenuovosnapshot。
         */
        public TaskInfo withStatusAndResult(TaskStatus newStatus, String newResult) {
            return new TaskInfo(
                    id, description, newStatus, newResult,
                    createdAt, Instant.now(),
                    metadata
            );
        }

        /**
         * creaunSoloAggiornamentoStatoenuovosnapshot。
         */
        public TaskInfo withStatus(TaskStatus newStatus) {
            return withStatusAndResult(newStatus, result);
        }
    }

    /* ------------------------------------------------------------------ */
    /*  InternoStato                                                           */
    /* ------------------------------------------------------------------ */

    /** archiviazione attività: taskId → TaskInfo (snapshot immutabile) */
    private final ConcurrentHashMap<String, TaskInfo> tasks = new ConcurrentHashMap<>();

    /** AutomaticamenteesegueModalitàsottoCorrisponde a Future，in */
    private final ConcurrentHashMap<String, Future<?>> futures = new ConcurrentHashMap<>();

    /** Thread，inesegueAutomaticamenteattività */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "task-worker-" + Thread.currentThread().threadId());
        t.setDaemon(true);
        return t;
    });

    /* ------------------------------------------------------------------ */
    /*  creaattività                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * creaeAutomaticamenteesegueundopoattività。
     * <p>
     * L'attività viene immediatamente inviata al pool di thread per l'esecuzione; al completamento lo stato diventa automaticamente COMPLETED o FAILED.
     * </p>
     *
     * @param description descrizione attività
     * @param work        eseguelavoro
     * @return generatoID attività（UUID prima 8 bit/posizione）
     * @throws NullPointerException se description o work è null
     */
    public String createTask(String description, Callable<String> work) {
        Objects.requireNonNull(description, "Task description cannot be null");
        Objects.requireNonNull(work, "Task work body cannot be null");

        String taskId = generateId();
        Instant now = Instant.now();

        TaskInfo info = new TaskInfo(
                taskId, description, TaskStatus.PENDING, null,
                now, now, Collections.emptyMap()
        );
        tasks.put(taskId, info);

        // aThreadAsincronoesegue
        Future<?> future = executor.submit(() -> {
            // contrassegna come RUNNING
            tasks.computeIfPresent(taskId, (id, old) -> old.withStatus(TaskStatus.RUNNING));
            try {
                String result = work.call();
                // contrassegna come COMPLETED
                tasks.computeIfPresent(taskId, (id, old) ->
                        old.withStatusAndResult(TaskStatus.COMPLETED, result));
            } catch (Exception e) {
                // contrassegna come FAILED，recordeccezioneInformazione
                String errorMsg = e.getClass().getSimpleName() + ": " + e.getMessage();
                tasks.computeIfPresent(taskId, (id, old) ->
                        old.withStatusAndResult(TaskStatus.FAILED, errorMsg));
            }
        });
        futures.put(taskId, future);

        return taskId;
    }

    /**
     * creaeAutomaticamenteesegueunmetadatidopoattività。
     *
     * @param description descrizione attività
     * @param work        eseguelavoro
     * @param metadata    aggiuntivometadati
     * @return generatoID attività
     */
    public String createTask(String description, Callable<String> work,
                             Map<String, String> metadata) {
        Objects.requireNonNull(metadata, "Metadata cannot be null");
        String taskId = createTask(description, work);
        // metadati（creain PENDING/RUNNING fase）
        tasks.computeIfPresent(taskId, (id, old) -> new TaskInfo(
                old.id(), old.description(), old.status(), old.result(),
                old.createdAt(), old.updatedAt(),
                Collections.unmodifiableMap(new LinkedHashMap<>(metadata))
        ));
        return taskId;
    }

    /**
     * creaunManualmenteattività（nonAutomaticamenteesegue）。
     * <p>
     * Statocome PENDING，richiedeEsternotramite {@link #updateTask} guidi manualmente le transizioni di stato.
     * </p>
     *
     * @param description descrizione attività
     * @return generatoID attività
     */
    public String createManualTask(String description) {
        return createManualTask(description, Collections.emptyMap());
    }

    /**
     * creaunmetadatiManualmenteattività。
     *
     * @param description descrizione attività
     * @param metadata    aggiuntivometadati
     * @return generatoID attività
     */
    public String createManualTask(String description, Map<String, String> metadata) {
        Objects.requireNonNull(description, "Task description cannot be null");

        String taskId = generateId();
        Instant now = Instant.now();

        Map<String, String> metaCopy = (metadata == null || metadata.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(metadata));

        TaskInfo info = new TaskInfo(
                taskId, description, TaskStatus.PENDING, null,
                now, now, metaCopy
        );
        tasks.put(taskId, info);
        return taskId;
    }

    /* ------------------------------------------------------------------ */
    /*  Queryattività                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * ottienispecificatoattivitàInformazionesnapshot。
     *
     * @param taskId ID attività
     * @return packageattivitàInformazione Optional，noninRestituisce empty
     */
    public Optional<TaskInfo> getTask(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * elenca tuttiattività。
     *
     * @return lista attività（crea）
     */
    public List<TaskInfo> listTasks() {
        return listTasks(null);
    }

    /**
     * elencaattività，puòStatoFiltraggio。
     *
     * @param statusFilter StatoFiltraggio，{@code null} TabellanonFiltraggio
     * @return uniscicondizionelista attività（crea）
     */
    public List<TaskInfo> listTasks(TaskStatus statusFilter) {
        return tasks.values().stream()
                .filter(t -> statusFilter == null || t.status() == statusFilter)
                .sorted(Comparator.comparing(TaskInfo::createdAt))
                .collect(Collectors.toUnmodifiableList());
    }

    /* ------------------------------------------------------------------ */
    /*  Aggiornamentoattività                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Aggiornamentostato e risultato dell'attività.
     *
     * @param taskId    ID attività
     * @param newStatus nuovoStato
     * @param result    esegueRisultato（puòè null）
     * @return Sese l'attività esiste e l'aggiornamento ha successo restituisce {@code true}; altrimenti {@code false}
     */
    public boolean updateTask(String taskId, TaskStatus newStatus, String result) {
        if (taskId == null || newStatus == null) {
            return false;
        }

        TaskInfo existing = tasks.get(taskId);
        if (existing == null) {
            return false;
        }

        // Non consentitosugià（COMPLETED / FAILED / CANCELLED）attivitàAggiornamento
        if (isTerminal(existing.status())) {
            return false;
        }

        tasks.computeIfPresent(taskId, (id, old) ->
                old.withStatusAndResult(newStatus, result));
        return true;
    }

    /* ------------------------------------------------------------------ */
    /*  attività                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * specificatoattività。
     * <p>
     * SeL'attività viene eseguita dal pool di thread e non è ancora completata; tenta di interrompere il thread di esecuzione.
     * </p>
     *
     * @param taskId ID attività
     * @return Sese l'attività esiste e la cancellazione ha successo restituisce {@code true}
     */
    public boolean cancelTask(String taskId) {
        if (taskId == null) {
            return false;
        }

        TaskInfo existing = tasks.get(taskId);
        if (existing == null) {
            return false;
        }

        // giàinnessuno
        if (isTerminal(existing.status())) {
            return false;
        }

        // tentainThreadinpositivoinEsecuzione Future
        Future<?> future = futures.remove(taskId);
        if (future != null && !future.isDone()) {
            future.cancel(true);
        }

        tasks.computeIfPresent(taskId, (id, old) ->
                old.withStatus(TaskStatus.CANCELLED));
        return true;
    }

    /* ------------------------------------------------------------------ */
    /*  Informazione                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * ritornaCorrentec'èattivitàstringa，unisciUsato per UI visualizza。
     *
     * @return formato -izzatoInformazione
     */
    public String getSummary() {
        if (tasks.isEmpty()) {
            return "No tasks at the moment.";
        }

        Map<TaskStatus, Long> counts = tasks.values().stream()
                .collect(Collectors.groupingBy(TaskInfo::status, Collectors.counting()));

        StringBuilder sb = new StringBuilder();
        sb.append("Task summary (total ").append(tasks.size()).append("):\n");
        for (TaskStatus status : TaskStatus.values()) {
            long count = counts.getOrDefault(status, 0L);
            if (count > 0) {
                sb.append("  ").append(status.name()).append(": ").append(count).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /* ------------------------------------------------------------------ */
    /*  Ciclo di vitaGestisci                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * chiudeexecutor。attendepiù 5 secondipositivoinEsecuzioneattivitàcompletamento，Timeoutdopochiude。
     */
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    /* ------------------------------------------------------------------ */
    /*  Internoausiliariometodo                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * genera ID attività： UUID prima 8 bit/posizione。
     */
    private String generateId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * Statosecome。
     */
    private boolean isTerminal(TaskStatus status) {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }
}
