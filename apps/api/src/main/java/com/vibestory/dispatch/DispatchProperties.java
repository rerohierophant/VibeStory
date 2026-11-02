package com.vibestory.dispatch;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("vibestory.dispatch")
public record DispatchProperties(String mode, String role, String namespace, boolean relayEnabled,
                                 int prefetch, int textSlots, int imageSlots, int videoSlots,
                                 int maxFailures, Duration confirmTimeout, Duration relayLease, Duration redeliveryAfter) {
    public DispatchProperties {
        if (!java.util.Set.of("database","rabbit").contains(mode) || !java.util.Set.of("all","api","worker").contains(role)
            || namespace == null || !namespace.matches("[a-zA-Z0-9._-]{1,80}")
            || prefetch<1 || textSlots<1 || imageSlots<1 || videoSlots<1 || maxFailures<1
            || confirmTimeout == null || confirmTimeout.isNegative() || confirmTimeout.isZero()
            || relayLease == null || relayLease.compareTo(confirmTimeout)<=0
            || redeliveryAfter == null || redeliveryAfter.isNegative() || redeliveryAfter.isZero())
            throw new IllegalArgumentException("无效的派发模式、运行角色或 MQ 配置");
        if (mode.equals("database") && !role.equals("all"))
            throw new IllegalArgumentException("独立 API / Worker 角色要求 DISPATCH_MODE=rabbit");
    }
    public boolean rabbit() { return mode.equals("rabbit"); }
    public boolean consumes() { return rabbit() && !role.equals("api"); }
    public boolean relays() { return rabbit() && relayEnabled && !role.equals("worker"); }
    public String lane(boolean mock) { return namespace + (mock ? ".mock" : ".live"); }
    public static String channel(String type) {
        return switch(type) { case "BREAKDOWN" -> "text"; case "IMAGE","ASSET_IMAGE" -> "image"; case "VIDEO" -> "video";
            default -> throw new IllegalArgumentException("不支持的任务类型"); };
    }
}
