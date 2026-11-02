package com.vibestory.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.vibestory.provider.*;
import com.vibestory.task.*;
import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="vibestory.dispatch.mode",havingValue="rabbit")
public class RabbitDispatch {
    private static final Logger log=LoggerFactory.getLogger(RabbitDispatch.class);
    private final ConnectionFactory connections;
    private final RabbitTemplate rabbit;
    private final ObjectMapper json;
    private final DispatchStore outbox;
    private final TaskStore tasks;
    private final com.vibestory.admission.GenerationAdmission admission;
    private final com.vibestory.admission.AdmissionStore admissionStore;
    private final TaskWorker worker;
    private final WorkerProperties workers;
    private final DispatchProperties config;
    private final TaskStore.Providers providers;
    private final boolean mockOnly;
    private final List<SimpleMessageListenerContainer> containers=new ArrayList<>();
    private volatile boolean ready;
    private volatile boolean stopping;

    public RabbitDispatch(ConnectionFactory connections,RabbitTemplate rabbit,ObjectMapper json,DispatchStore outbox,
                          TaskStore tasks,TaskWorker worker,WorkerProperties workers,DispatchProperties config,
                          TextProperties text,ImageProperties image,VideoProperties video,com.vibestory.admission.GenerationAdmission admission,com.vibestory.admission.AdmissionStore admissionStore) {
        this.connections=connections;this.rabbit=rabbit;this.json=json;this.outbox=outbox;this.tasks=tasks;
        this.worker=worker;this.workers=workers;this.config=config;this.admission=admission;this.admissionStore=admissionStore;
        this.providers=new TaskStore.Providers(text.provider(),image.provider(),video.provider());
        this.mockOnly=text.provider().equals("mock") && image.provider().equals("mock") && video.provider().equals("mock");
        rabbit.setMandatory(true);
    }
    @EventListener(ApplicationReadyEvent.class)
    public void start() { initialize(); }
    @Scheduled(fixedDelay=5000)
    public synchronized void initialize() {
        if(ready || stopping) return;
        try { initializeBroker(); }
        catch(Exception unavailable) {
            containers.forEach(SimpleMessageListenerContainer::stop);containers.clear();
            log.warn("RabbitMQ 暂不可用，任务保留在数据库等待派发");
        }
    }
    private void initializeBroker() {
        var admin=new RabbitAdmin(connections);
        // The Mock namespace can never share a consumer queue with real-provider work.
        for(boolean mock : new boolean[]{true,false}) {
            if(!mock && mockOnly) continue;
            String lane=config.lane(mock);
            admin.declareExchange(new DirectExchange(lane+".tasks",true,false));
            admin.declareExchange(new DirectExchange(lane+".dead",true,false));
            admin.declareQueue(new org.springframework.amqp.core.Queue(lane+".dead",true));
            admin.declareBinding(new Binding(lane+".dead",Binding.DestinationType.QUEUE,lane+".dead","dead",null));
            for(String type : List.of("text","image","video")) {
                String name=lane+"."+type;
                admin.declareQueue(QueueBuilder.durable(name).deadLetterExchange(lane+".dead").deadLetterRoutingKey("dead").build());
                admin.declareBinding(new Binding(name,Binding.DestinationType.QUEUE,lane+".tasks",type,null));
                if(config.consumes() && workers.enabled()) {
                    int capacity=switch(type) { case "text" -> config.textSlots();case "image" -> config.imageSlots();default -> config.videoSlots(); };
                    // One semaphore per type shared across Mock/live lanes within this process.
                    Semaphore slots=typeSlots.computeIfAbsent(type,k->new Semaphore(capacity));
                    var container=new SimpleMessageListenerContainer(connections);
                    container.setQueueNames(name);container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
                    container.setPrefetchCount(config.prefetch());container.setConcurrentConsumers(1);
                    container.setDefaultRequeueRejected(false);
                    container.setMessageListener((ChannelAwareMessageListener)(message,channel)->consume(message,channel,mock,type,slots));
                    containers.add(container);container.start();
                }
            }
        }
        ready=true;
    }
    private final Map<String,Semaphore> typeSlots=new HashMap<>();

    private void consume(Message message,Channel channel,boolean mock,String type,Semaphore slots) throws Exception {
        long tag=message.getMessageProperties().getDeliveryTag();
        TaskNotification event;
        try {
            if(message.getBody().length>2048) throw new IllegalArgumentException("通知过大");
            event=json.readValue(message.getBody(),TaskNotification.class);event.validate();
            if(!DispatchProperties.channel(event.type()).equals(type)) throw new IllegalArgumentException("通知类型与队列不匹配");
        } catch(Exception invalid) { channel.basicReject(tag,false);return; }
        TaskStore.Candidate candidate=null;
        if(admission.enabled()) {
            try { candidate=tasks.inspectNotification(event,providers,mock).orElse(null); }
            catch(IllegalArgumentException invalid) { channel.basicReject(tag,false);return; }
            catch(Exception infrastructure) { retryInfrastructure(event,channel,tag);return; }
            if(candidate==null) { channel.basicAck(tag,false);outbox.duplicate(event);return; }
        }
        slots.acquire();
        boolean handedOff=false;
        try {
            Optional<TaskStore.Lease> lease;
            com.vibestory.admission.GenerationAdmission.Permit permit=null;
            try {
                if(candidate!=null && !candidate.externalRecovery()) {
                    var decision=admission.request(candidate.taskId(),candidate.type(),candidate.provider());
                    if(!decision.allowed()) {
                        admissionStore.defer(event,decision.reason(),decision.retryAfterMs());
                        channel.basicAck(tag,false);return;
                    }
                    permit=decision.permit();
                }
                lease=tasks.claimNotification(event,worker.nextOwner(),providers,mock);
            }
            catch(IllegalArgumentException invalid) { channel.basicReject(tag,false);return; }
            catch(Exception infrastructure) {
                // SQL failure is never interpreted as a missing task. Retry via the durable outbox.
                try { if(outbox.deliveryFailed(event)) channel.basicAck(tag,false);else channel.basicReject(tag,false); }
                catch(Exception databaseDown) { channel.basicReject(tag,false); }
                return;
            }
            // Transactional proxy has committed the lease before this ACK.
            channel.basicAck(tag,false);
            if(lease.isPresent()) { worker.executeClaimed(lease.get(),slots::release,permit);handedOff=true; }
            else outbox.duplicate(event);
        } finally { if(!handedOff) slots.release(); }
    }

    private void retryInfrastructure(TaskNotification event,Channel channel,long tag) throws Exception {
        try { if(outbox.deliveryFailed(event)) channel.basicAck(tag,false);else channel.basicReject(tag,false); }
        catch(Exception databaseDown) { channel.basicReject(tag,false); }
    }

    @Scheduled(fixedDelay=500)
    public void relay() {
        if(!ready || !config.relays()) return;
        try {
            for(int n=0;n<20;n++) {
                var publication=outbox.take();if(publication==null) return;
                try {
                    var e=publication.notification();
                    var props=new MessageProperties();props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);props.setMessageId(e.eventId().toString());
                    var correlation=new CorrelationData(e.eventId().toString());
                    rabbit.send(config.lane(publication.mock())+".tasks",DispatchProperties.channel(e.type()),new Message(json.writeValueAsBytes(e),props),correlation);
                    var confirmed=correlation.getFuture().get(config.confirmTimeout().toMillis(),TimeUnit.MILLISECONDS);
                    if(!confirmed.isAck() || correlation.getReturned()!=null) throw new IllegalStateException("发布未确认或未路由");
                    outbox.sent(publication);
                } catch(Exception failure) { outbox.publishFailed(publication);log.warn("MQ 发布等待重试 task={}",publication.notification().taskId()); }
            }
        } catch(Exception failure) { log.warn("Outbox Relay 暂不可用"); }
    }
    @Scheduled(fixedDelay=2000)
    public void coordinate() {
        if(!ready || !config.relays()) return;
        try { tasks.recoverExpired(providers);outbox.reconcileQueued(); }
        catch(Exception failure) { log.warn("MQ 恢复协调器暂不可用"); }
    }
    @Scheduled(fixedDelay=2000)
    public void collectDeadLetters() {
        if(!ready || !config.relays()) return;
        for(boolean mock : new boolean[]{true,false}) {
            if(!mock && mockOnly) continue;
            // A channel close requeues uncommitted DLQ receipts. Never ACK a failed DB write.
            try(var connection=connections.createConnection();var channel=connection.createChannel(false)) {
                for(int n=0;n<50;n++) {
                    var delivery=channel.basicGet(config.lane(mock)+".dead",false);if(delivery==null) break;
                    TaskNotification event=null;
                    try {
                        if(delivery.getBody().length<=2048) {
                            var node=json.readTree(delivery.getBody());
                            // Correlate allowlisted identity even if an unknown field caused contract rejection.
                            event=new TaskNotification(node.path("schemaVersion").asInt(),UUID.fromString(node.path("eventId").asText()),
                                node.path("taskId").asLong(),node.path("dispatchGeneration").asLong(),node.path("type").asText(),0,0,null);
                        }
                    } catch(Exception ignored) { }
                    String fingerprint=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(delivery.getBody()));
                    outbox.deadLetter(fingerprint,event,"DEAD_LETTER");
                    channel.basicAck(delivery.getEnvelope().getDeliveryTag(),false);
                }
            } catch(Exception failure) { log.warn("死信归档暂不可用"); }
        }
    }
    @PreDestroy
    public synchronized void stop() { stopping=true;ready=false;containers.forEach(SimpleMessageListenerContainer::stop);containers.clear(); }
}
