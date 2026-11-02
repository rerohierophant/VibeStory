package com.vibestory.dispatch;

import java.util.UUID;

/** No provider parameters, prompts, media or credentials cross the broker. */
public record TaskNotification(int schemaVersion, UUID eventId, long taskId, long dispatchGeneration,
                               String type, long userId, long projectId, Long episodeId) {
    public void validate() {
        if (schemaVersion!=1 || eventId==null || taskId<1 || dispatchGeneration<1 || userId<1 || projectId<1
            || type==null || (episodeId!=null && episodeId<1)) throw new IllegalArgumentException("无效的通知契约");
        DispatchProperties.channel(type);
    }
}
