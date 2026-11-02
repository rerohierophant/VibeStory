package com.vibestory.admission;

import java.util.concurrent.Callable;

/** One-use gate at the adapter's actual new-request boundary, after local payload preparation. */
public final class SubmissionBoundary {
    private SubmissionBoundary() {}
    @FunctionalInterface public interface Gate { void enter() throws InterruptedException; }
    private static final ThreadLocal<Ticket> active=new ThreadLocal<>();
    private static class Ticket { final Gate gate;boolean used;Ticket(Gate gate) { this.gate=gate; } }
    public static <T> T invoke(Gate gate,Callable<T> call) throws Exception {
        if(active.get()!=null) throw new IllegalStateException("生成提交不可嵌套");
        var ticket=new Ticket(gate);active.set(ticket);
        try { T result=call.call();if(!ticket.used) throw new IllegalStateException("适配器缺少生成提交准入边界");return result; }
        finally { active.remove(); }
    }
    public static void beforeSubmission() throws InterruptedException {
        var ticket=active.get();if(ticket==null) return; // Direct adapter fixtures have no business execution context.
        if(ticket.used) throw new IllegalStateException("同一执行上下文只能提交一次生成");
        ticket.gate.enter();ticket.used=true;
    }
}
