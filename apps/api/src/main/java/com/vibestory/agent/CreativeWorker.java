package com.vibestory.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.admission.AdmissionProperties;
import com.vibestory.agent.CreativeSkill.EpisodeDraft;
import com.vibestory.agent.CreativeSkill.Role;
import com.vibestory.agent.CreativeSkill.ScriptDraft;
import com.vibestory.common.ApiException;
import com.vibestory.common.TextInput;
import com.vibestory.dispatch.DispatchProperties;
import com.vibestory.provider.ProviderException;
import com.vibestory.provider.ShotDraftValidation;
import com.vibestory.provider.TextProvider;
import com.vibestory.task.WorkerProperties;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CreativeWorker {
    private final DSLContext db;
    private final CreativeWorkflows workflows;
    private final CreativeModel model;
    private final AgentRuntime runtime;
    private final ObjectMapper json;
    private final ShotDraftValidation validation;
    private final WorkerProperties worker;
    private final DispatchProperties dispatch;
    private final AdmissionProperties admission;
    private final Semaphore slot = new Semaphore(1);
    private final java.util.concurrent.ExecutorService execution = Executors.newVirtualThreadPerTaskExecutor();

    CreativeWorker(DSLContext db, CreativeWorkflows workflows, CreativeModel model, ObjectMapper json,
                   ShotDraftValidation validation, WorkerProperties worker, DispatchProperties dispatch, AdmissionProperties admission, AgentRuntime runtime) {
        this.db = db;
        this.workflows = workflows;
        this.model = model;
        this.json = json;
        this.validation = validation;
        this.worker = worker;
        this.dispatch = dispatch;
        this.admission = admission;
        this.runtime = runtime;
    }

    @Scheduled(fixedDelayString = "${vibestory.worker.poll-interval}")
    public void poll() {
        if (!worker.enabled() || dispatch.role().equals("api") || admission.enabled() || !slot.tryAcquire()) return;
        execution.submit(() -> {
            try { runOne(); runtime.resumeOne(); }
            finally { slot.release(); }
        });
    }

    public void runOne() {
        if (admission.enabled()) return;
        db.execute("""
            update creative_turn set state='UNKNOWN', error='工作进程中断，文本请求结果未知',
                lease_id=null, lease_until=null
            where state='RUNNING' and provider<>'mock' and lease_until < clock_timestamp()
            """);
        UUID lease = UUID.randomUUID();
        var row = db.fetchOne("""
            update creative_turn set state='RUNNING',lease_id=?,lease_until=clock_timestamp()+(? * interval '1 millisecond')
            where id=(select id from creative_turn
                where state='QUEUED' or (state='RUNNING' and provider='mock' and lease_until < clock_timestamp())
                order by seq for update skip locked limit 1) returning *
            """, lease, model.timeout().plusSeconds(15).toMillis());
        if (row == null) return;
        UUID id = row.get("id", UUID.class);
        try {
            var session = db.fetchOne("select * from creative_session where id=?", row.get("session_id"));
            if (session == null) return;
            var config = new CreativeModel.Config(row.get("provider", String.class), row.get("endpoint", String.class),
                row.get("model", String.class));
            if (!model.configuration().equals(config)) {
                fail(id, lease, "FAILED", "文本配置已变化，请重新发起");
                return;
            }
            switch (Role.valueOf(session.get("role", String.class))) {
                case PLAN -> plan(row, config, id, lease);
                case SCRIPT, STORYBOARD -> runtime.runChild(row, session, lease);
                case MAIN -> fail(id, lease, "FAILED", "主会话无模型子任务");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (ProviderException error) {
            fail(id, lease, error.unknown() ? "UNKNOWN" : "FAILED", error.getMessage());
        } catch (IOException error) {
            fail(id, lease, "UNKNOWN", "文本连接中断，请核对后重新发起");
        } catch (ApiException | IllegalArgumentException invalid) {
            fail(id, lease, "FAILED", "产物未通过结构或范围校验，请调整要求后重新发起");
        } catch (RuntimeException error) {
            fail(id, lease, "UNKNOWN", "创作执行异常，请核对后重新发起");
        }
    }

    private void plan(Record turn, CreativeModel.Config config, UUID id, UUID lease) throws IOException, InterruptedException {
        var source = db.fetchOne("select body from creative_source where id=?", turn.get("source_id"));
        if (source == null) return;
        String body = source.get(0, String.class);
        int cursor = turn.get("cursor", Integer.class);
        var blocks = CreativeSkill.window(body, cursor);
        var drafts = new ArrayList<>(workflows.drafts(turn));
        int end = blocks.getLast().end();
        if (body.substring(cursor, end).isBlank()) {
            if (!drafts.isEmpty()) {
                var last = drafts.removeLast();
                drafts.add(new EpisodeDraft(last.start(), end, last.title(), last.summary()));
            }
        } else {
            String continuity = drafts.isEmpty() ? "" : drafts.getLast().summary();
            var cuts = model.plan(config, blocks, turn.get("instruction", String.class), continuity);
            if (cuts == null || cuts.isEmpty()) throw new IllegalArgumentException();
            int previousBlock = 0;
            int start = drafts.isEmpty() ? 0 : drafts.getLast().end();
            for (var cut : cuts) {
                if (cut.lastBlock() <= previousBlock || cut.lastBlock() > blocks.size()) throw new IllegalArgumentException();
                int stop = blocks.get(cut.lastBlock() - 1).end();
                if (body.substring(start, stop).isBlank()) throw new IllegalArgumentException();
                String title = TextInput.name(cut.title(), "集名");
                TextInput.validate(cut.summary(), 800, "分集梗概");
                drafts.add(new EpisodeDraft(start, stop, title, cut.summary()));
                previousBlock = cut.lastBlock();
                start = stop;
            }
            if (previousBlock != blocks.size() || drafts.size() > 200) throw new IllegalArgumentException();
        }
        finish(id, lease, end == body.length() ? "READY" : "QUEUED", end, drafts,
            end == body.length() ? "分集草案包含 " + drafts.size() + " 集，待审阅确认" : "已规划 " + drafts.size() + " 集");
    }

    private void finish(UUID id, UUID lease, String state, int cursor, Object result, String summary) {
        db.execute("""
            update creative_turn set state=?, cursor=?, result=?::jsonb, summary=?, lease_id=null, lease_until=null
            where id=? and lease_id=? and lease_until >= clock_timestamp()
            """, state, cursor, workflows.encode(result), summary, id, lease);
    }

    private void fail(UUID id, UUID lease, String state, String error) {
        db.execute("""
            update creative_turn set state=?,error=?,lease_id=null,lease_until=null
            where id=? and lease_id=? and lease_until >= clock_timestamp()
            """, state, error, id, lease);
    }

    @PreDestroy
    void stop() { execution.shutdownNow(); }
}
