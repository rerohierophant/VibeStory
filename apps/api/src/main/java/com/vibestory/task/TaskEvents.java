package com.vibestory.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import org.jooq.DSLContext;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Allowlisted metadata only. A rolled-back transition must never be logged as committed. */
@Component
public class TaskEvents {
    private final DSLContext db;
    private final ObjectMapper json;

    public TaskEvents(DSLContext db, ObjectMapper json) { this.db = db; this.json = json; }

    public void record(String event, long taskId, Long attemptId) {
        var row = db.fetchOne("""
            select t.id, t.user_id, t.project_id, t.episode_id, t.type, t.provider,
                t.status as task_status, t.input_snapshot, a.id as attempt_id, a.attempt_no,
                a.status as attempt_status, a.error_code
            from task t left join task_attempt a on a.task_id=t.id and a.id=? where t.id=?
            """, attemptId, taskId);
        if (row == null) return;
        var fields = new LinkedHashMap<String, Object>();
        fields.put("event", event);
        for (String key : new String[]{"id", "user_id", "project_id", "episode_id", "type", "provider",
                "task_status", "attempt_id", "attempt_no", "attempt_status", "error_code"}) {
            if (row.get(key) != null) fields.put(key.equals("id") ? "task_id" : key, row.get(key));
        }
        // Never serialize a prompt, storage key, endpoint, raw response or credential into logs.
        try {
            var input = json.readTree(row.get("input_snapshot", String.class));
            if (input.has("sourceImageId")) fields.put("source_image_id", input.path("sourceImageId").asLong());
            if (input.has("sourceImageVersion")) fields.put("source_image_version", input.path("sourceImageVersion").asLong());
            var versions = new ArrayList<String>();
            for (var reference : input.path("references")) {
                if (reference.hasNonNull("versionId")) versions.add(reference.path("versionId").asText());
            }
            if (!versions.isEmpty()) fields.put("source_asset_version_ids", versions);
        } catch (java.io.IOException error) {
            fields.put("source_metadata_unreadable", true);
        }
        Runnable emit = () -> {
            var line = LoggerFactory.getLogger(TaskEvents.class).atInfo();
            fields.forEach(line::addKeyValue);
            line.log("Task lifecycle");
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { emit.run(); }
            });
        } else emit.run();
    }
}
