package com.vibestory.task;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("vibestory.worker")
public record WorkerProperties(boolean enabled, @Min(1) int concurrency, @NotNull Duration pollInterval,
                               @NotNull Duration leaseDuration, @NotNull Duration heartbeatInterval,
                               @Min(1) int maxAttempts) {
    public WorkerProperties {
        if (leaseDuration == null || heartbeatInterval == null || pollInterval == null ||
                leaseDuration.isNegative() || leaseDuration.isZero() ||
                heartbeatInterval.isNegative() || heartbeatInterval.isZero() ||
                heartbeatInterval.compareTo(leaseDuration) >= 0 ||
                pollInterval.isNegative() || pollInterval.isZero()) {
            throw new IllegalArgumentException("轮询、租约和续约间隔必须为正数，续约间隔必须小于租约");
        }
    }
}
