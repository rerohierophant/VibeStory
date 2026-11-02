package com.vibestory.storage;

import java.io.IOException;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class StorageCleanup {
    private static final Logger log = LoggerFactory.getLogger(StorageCleanup.class);
    private final DSLContext db;
    private final LocalStorage storage;
    private final boolean enabled;

    public StorageCleanup(DSLContext db, LocalStorage storage,
                          @Value("${vibestory.storage.cleanup-enabled:true}") boolean enabled) {
        this.db = db;
        this.storage = storage;
        this.enabled = enabled;
    }

    @Transactional
    public void enqueue(String prefix) {
        db.execute("insert into storage_cleanup(prefix) values (?) on conflict do nothing", prefix);
    }

    @Scheduled(fixedDelayString = "${vibestory.storage.cleanup-interval:30s}")
    public void poll() { if (enabled) sweep(); }

    public void sweep() {
        // Keep tombstones: an expired Worker can still publish a local file after the first sweep.
        for (String prefix : db.fetch("select prefix from storage_cleanup order by created_at").getValues("prefix", String.class)) {
            try { storage.deleteTree(prefix); }
            catch (IOException error) { log.warn("本地媒体清理失败，将在下轮重试 prefix={}", prefix, error); }
        }
    }
}
