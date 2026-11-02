package com.vibestory.task;

import com.vibestory.provider.ProviderException;
import com.vibestory.provider.VideoProvider;
import com.vibestory.provider.VideoProviders;
import com.vibestory.provider.VideoProperties;
import com.vibestory.provider.ImageProperties;
import com.vibestory.provider.TextProperties;
import com.vibestory.provider.TextProviders;
import com.vibestory.breakdown.BreakdownService;
import com.vibestory.provider.ImageProviders;
import com.vibestory.image.ImageTaskService;
import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class TaskWorker {
    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private final TaskStore store;
    private final VideoProviders provider;
    private final TextProviders text;
    private final BreakdownService breakdowns;
    private final ImageProviders images;
    private final ImageTaskService imageTasks;
    private final WorkerProperties config;
    private final TaskStore.Providers enabledProviders;
    private final String workerId = UUID.randomUUID().toString();
    private final Semaphore slots;
    private final com.vibestory.dispatch.DispatchProperties dispatch;
    private final ExecutorService execution = Executors.newVirtualThreadPerTaskExecutor();
    private final ScheduledExecutorService heartbeats = Executors.newScheduledThreadPool(1,
        Thread.ofPlatform().daemon().name("task-heartbeat-", 0).factory());

    public TaskWorker(TaskStore store, VideoProviders provider, WorkerProperties config, TextProviders text, BreakdownService breakdowns, ImageProviders images, ImageTaskService imageTasks,
                      TextProperties textConfig,ImageProperties imageConfig,VideoProperties videoConfig, com.vibestory.dispatch.DispatchProperties dispatch) {
        this.store = store;
        this.dispatch = dispatch;
        this.provider = provider;
        this.config = config;
        this.text = text;
        this.breakdowns = breakdowns;
        this.images=images; this.imageTasks=imageTasks;
        this.slots = new Semaphore(config.concurrency());
        this.enabledProviders=new TaskStore.Providers(textConfig.provider(),imageConfig.provider(),videoConfig.provider());
    }

    @Scheduled(fixedDelayString = "${vibestory.worker.poll-interval}")
    public void poll() {
        if (config.enabled() && !dispatch.rabbit()) dispatchAvailable();
    }

    void dispatchAvailable() {
        while (slots.tryAcquire()) {
            try {
                var lease = store.claim(workerId + ":" + UUID.randomUUID(),enabledProviders);
                if (lease.isEmpty()) { slots.release(); return; }
                execution.submit(() -> execute(lease.get()));
            } catch (Exception error) {
                slots.release();
                log.error("领取任务失败", error);
                return;
            }
        }
    }

    public String nextOwner() { return workerId + ":" + UUID.randomUUID(); }

    public void executeClaimed(TaskStore.Lease lease, Runnable release) {
        execution.submit(() -> execute(lease, release));
    }

    private void execute(TaskStore.Lease lease) { execute(lease, slots::release); }

    private void execute(TaskStore.Lease lease, Runnable release) {
        var running = new Running(lease);
        var heartbeat = heartbeats.scheduleAtFixedRate(running::renew, config.heartbeatInterval().toMillis(),
            config.heartbeatInterval().toMillis(), TimeUnit.MILLISECONDS);
        try {
            if (lease.type().equals("BREAKDOWN")) {
                var result = text.generate(breakdowns.input(lease.input()));
                synchronized (running) {
                    if (!running.lost && !store.succeedBreakdown(lease, running.version, result))
                        log.info("忽略未应用的拆解结果 task={}", lease.taskId());
                }
            } else if (lease.type().endsWith("IMAGE")) {
                var result = images.generate(imageTasks.input(lease.input()), lease.signature());
                synchronized (running) {
                    if (!running.lost && !store.succeedImage(lease, running.version, result))
                        log.info("忽略已失去租约的图片结果 task={}", lease.taskId());
                }
            } else {
                var result = provider.generate(lease.request(),lease.externalTaskId(),running::checkpoint);
                synchronized (running) {
                    if (!running.lost && !store.succeed(lease, running.version, result))
                        log.info("忽略已失去租约的任务结果 task={}", lease.taskId());
                }
            }
        } catch (InterruptedException error) {
            // Preserve RUNNING for lease recovery after process shutdown.
            Thread.currentThread().interrupt();
        } catch (ProviderException error) {
            running.fail(error);
        } catch (Exception error) {
            log.error("Worker 执行异常 task={}", lease.taskId(), error);
            // Unexpected adapter/runtime failures have no proven external execution outcome.
            running.fail(new ProviderException("WORKER_RESULT_UNKNOWN", "执行异常，结果未知", false, true));
        } finally {
            heartbeat.cancel(false);
            release.run();
        }
    }

    private class Running {
        private final TaskStore.Lease lease;
        private long version;
        private boolean lost;
        Running(TaskStore.Lease lease) { this.lease = lease; this.version = lease.version(); }

        synchronized boolean checkpoint(String externalId) {
            if(lost) return false;
            if(!store.checkpoint(lease,version,externalId)) lost=true;
            return !lost;
        }

        synchronized void renew() {
            if (lost) return;
            try {
                var renewed = store.heartbeat(lease, version);
                if (renewed.isEmpty()) lost = true;
                else version = renewed.get();
            } catch (Exception error) {
                // Fail closed; a fresh Worker may safely replay the local Mock after expiry.
                lost = true;
                log.warn("任务续约失败 task={}", lease.taskId(), error);
            }
        }

        synchronized void fail(ProviderException error) {
            try { if (!lost) store.fail(lease, version, error); }
            catch (Exception failure) { log.error("任务异常状态写入失败 task={}", lease.taskId(), failure); }
        }
    }

    @PreDestroy
    void stop() {
        heartbeats.shutdownNow();
        execution.shutdownNow();
    }
}
