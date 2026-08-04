package com.workflowplatform.controlplane.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Append-oriented dead-letter service with redacted errors, notes and redispatch history. */
public final class DeadLetterService {

    private static final int MAXIMUM_SUMMARY = 500;
    private final Map<UUID, DeadLetter> records = new ConcurrentHashMap<>();

    public DeadLetter record(UUID taskId, String errorCode, String summary, Instant now) {
        UUID id = UUID.randomUUID();
        DeadLetter letter =
                new DeadLetter(id, taskId, errorCode, redact(summary), now, List.of(), 0);
        records.put(id, letter);
        return letter;
    }

    public synchronized DeadLetter addNote(UUID id, String actor, String note, Instant now) {
        DeadLetter current = require(id);
        List<Note> notes = new ArrayList<>(current.notes());
        notes.add(new Note(actor, redact(note), now));
        DeadLetter updated = current.withNotes(notes);
        records.put(id, updated);
        return updated;
    }

    public synchronized DeadLetter redispatch(UUID id) {
        DeadLetter current = require(id);
        DeadLetter updated = current.withRedispatchCount(current.redispatchCount() + 1);
        records.put(id, updated);
        return updated;
    }

    private DeadLetter require(UUID id) {
        DeadLetter value = records.get(id);
        if (value == null) {
            throw new IllegalArgumentException("dead letter not found");
        }
        return value;
    }

    private static String redact(String value) {
        if (value == null || value.isBlank()) {
            return "<redacted>";
        }
        String cleaned =
                value.replaceAll("[\\r\\n\\t]", " ")
                        .replaceAll("(?i)(token|password|secret)=[^ ]+", "$1=<redacted>");
        return cleaned.substring(0, Math.min(cleaned.length(), MAXIMUM_SUMMARY));
    }

    public record DeadLetter(
            UUID id,
            UUID taskId,
            String errorCode,
            String summaryRedacted,
            Instant createdAt,
            List<Note> notes,
            int redispatchCount) {
        public DeadLetter {
            notes = List.copyOf(notes);
        }

        DeadLetter withNotes(List<Note> value) {
            return new DeadLetter(
                    id, taskId, errorCode, summaryRedacted, createdAt, value, redispatchCount);
        }

        DeadLetter withRedispatchCount(int value) {
            return new DeadLetter(id, taskId, errorCode, summaryRedacted, createdAt, notes, value);
        }
    }

    public record Note(String actor, String textRedacted, Instant createdAt) {}
}
