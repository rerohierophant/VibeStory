package com.vibestory.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vibestory.task.TaskStore;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Optional local acceptance probe at the actual Mock adapter entry; records identities only. */
@Component
public class MockSubmissionProbe {
    private final Path path;
    private final ObjectMapper json;
    private final ThreadLocal<Map<String,Object>> context=new ThreadLocal<>();
    public MockSubmissionProbe(@Value("${vibestory.mock.submission-probe:}") String path,ObjectMapper json) { this.path=path.isBlank()?null:Path.of(path);this.json=json; }
    public <T> T invoke(TaskStore.Lease lease,UUID permit,Callable<T> call) throws Exception {
        if(path==null) return call.call();
        var fields=new HashMap<String,Object>();fields.put("taskId",lease.taskId());fields.put("attemptId",lease.attemptId());fields.put("owner",lease.owner());
        if(permit!=null) fields.put("permitId",permit.toString());context.set(fields);
        try { return call.call(); } finally { context.remove(); }
    }
    public synchronized void entered(String type) {
        if(path==null || context.get()==null) return;
        var fields=new HashMap<>(context.get());fields.put("type",type);fields.put("enteredAt",java.time.Instant.now().toString());
        try { Files.createDirectories(path.toAbsolutePath().getParent());Files.writeString(path,json.writeValueAsString(fields)+"\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND); }
        catch(java.io.IOException error) { throw new IllegalStateException("Mock 验收探测记录失败",error); }
    }
    public void permit(UUID permit) { if(context.get()!=null && permit!=null) context.get().put("permitId",permit.toString()); }
}
