package com.vibestory.common;

import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Removes owned rows while the caller holds the project lock. Media cleanup stays in the caller.
 */
@Component
@Transactional(propagation = Propagation.MANDATORY)
public class ProjectDataCleanup {
    private final DSLContext db;

    public ProjectDataCleanup(DSLContext db) { this.db = db; }

    public void project(long user, long project) {
        var episodes = db.fetch("select id from episode where project_id=? and user_id=?", project, user)
            .getValues("id", Long.class).toArray(Long[]::new);
        deleteContent(user, project, episodes, true);

        // Lock origins before clearing references so a concurrent recipe save cannot restore one.
        var origins = db.fetch("select id from image_creation where project_id=? and user_id=? order by id for update", project, user)
            .getValues("id", UUID.class).toArray(UUID[]::new);
        db.execute("update image_recipe_version set origin_id=null where origin_id=any(cast(? as uuid[]))", (Object) origins);
        db.execute("update image_preference set origin_id=null where origin_id=any(cast(? as uuid[]))", (Object) origins);
        db.execute("delete from image_preference where project_id=? and user_id=?", project, user);
        db.execute("delete from image_creation where project_id=? and user_id=?", project, user);
        db.execute("delete from creative_source where project_id=?", project);
        db.execute("delete from asset_ref where project_id=?", project);
        db.execute("delete from asset_version where project_id=? and user_id=?", project, user);
        db.execute("delete from character_asset where project_id=? and user_id=?", project, user);
        db.execute("delete from scene_asset where project_id=? and user_id=?", project, user);
        db.execute("delete from episode where project_id=? and user_id=?", project, user);
        if (db.fetchOne("select to_regclass('storyboard_experience') is not null").get(0, Boolean.class))
            db.execute("delete from storyboard_experience where project_id=? and user_id=?", project, user);
    }

    public void episode(long user, long project, long episode) {
        deleteContent(user, project, new Long[]{episode}, false);
    }

    public void shots(long user, long project, long episode) {
        deleteShots(user, project, new Long[]{episode});
    }

    private void deleteContent(long user, long project, Long[] episodes, boolean wholeProject) {
        // Block claim/receipt writers before deleting their dependent rows.
        var tasks = db.fetch("""
            select id from task where project_id=? and user_id=? and (? or episode_id=any(cast(? as bigint[])))
            order by id for update
            """, project, user, wholeProject, episodes).getValues("id", Long.class).toArray(Long[]::new);
        deleteAgentData(user, project, episodes, wholeProject);
        deleteShots(user, project, episodes);
        db.execute("delete from generation_batch_member where batch_id in (select id from generation_batch where episode_id=any(cast(? as bigint[])))",
            (Object) episodes);
        db.execute("delete from generation_batch where episode_id=any(cast(? as bigint[]))", (Object) episodes);
        deleteTasks(tasks);
    }

    private void deleteAgentData(long user, long project, Long[] episodes, boolean wholeProject) {
        var conversations = db.fetch("""
            select id from agent_conversation where user_id=? and creative_episode_id=any(cast(? as bigint[]))
            order by id for update
            """, user, episodes).getValues("id", UUID.class).toArray(UUID[]::new);
        var runs = db.fetch("""
            select id from agent_run where user_id=? and (episode_id=any(cast(? as bigint[])) or (? and project_id=?))
            order by id for update
            """, user, episodes, wholeProject, project).getValues("id", UUID.class).toArray(UUID[]::new);
        db.execute("delete from agent_compaction_call where summary_id in (select id from agent_context_summary where run_id=any(cast(? as uuid[])))", (Object) runs);
        db.execute("delete from agent_context_summary where run_id=any(cast(? as uuid[]))", (Object) runs);
        db.execute("delete from agent_context_event where run_id=any(cast(? as uuid[]))", (Object) runs);
        db.execute("delete from agent_model_call where run_id=any(cast(? as uuid[]))", (Object) runs);
        db.execute("delete from agent_tool_call where run_id=any(cast(? as uuid[]))", (Object) runs);
        db.execute("update agent_chat_message set agent_run_id=null where agent_run_id=any(cast(? as uuid[]))", (Object) runs);
        db.execute("delete from agent_run where id=any(cast(? as uuid[]))", (Object) runs);
        db.execute("delete from agent_chat_image where message_id in (select id from agent_chat_message where conversation_id=any(cast(? as uuid[])))",
            (Object) conversations);
        db.execute("delete from agent_chat_message where conversation_id=any(cast(? as uuid[]))", (Object) conversations);
        db.execute("delete from agent_workflow where episode_id=any(cast(? as bigint[])) or conversation_id=any(cast(? as uuid[]))", episodes, conversations);
        db.execute("delete from agent_conversation where id=any(cast(? as uuid[]))", (Object) conversations);
        db.execute("delete from agent_command_receipt where user_id=? and (episode_id=any(cast(? as bigint[])) or (? and project_id=?))",
            user, episodes, wholeProject, project);
        db.execute("delete from creative_episode_source where episode_id=any(cast(? as bigint[]))", (Object) episodes);
        db.execute("""
            delete from creative_turn where session_id in (
                select id from creative_session where project_id=? and (? or episode_id=any(cast(? as bigint[])))
            )
            """, project, wholeProject, episodes);
        db.execute("delete from creative_session where project_id=? and (? or episode_id=any(cast(? as bigint[])))", project, wholeProject, episodes);
    }

    private void deleteShots(long user, long project, Long[] episodes) {
        // V18 was present in some early local databases. Its remaining rows still belong to these shots.
        if (db.fetchOne("select to_regclass('storyboard_experience') is not null").get(0, Boolean.class))
            db.execute("delete from storyboard_experience where episode_id=any(cast(? as bigint[]))", (Object) episodes);
        if (db.fetchOne("select to_regclass('storyboard_experience_preview') is not null").get(0, Boolean.class))
            db.execute("delete from storyboard_experience_preview where episode_id=any(cast(? as bigint[]))", (Object) episodes);
        db.execute("delete from asset_ref where project_id=? and shot_id in (select id from shot where episode_id=any(cast(? as bigint[])))", project, episodes);
        db.execute("delete from shot_edit_proposal where episode_id=any(cast(? as bigint[]))", (Object) episodes);
        db.execute("delete from video where episode_id=any(cast(? as bigint[]))", (Object) episodes);
        db.execute("delete from shot_image where episode_id=any(cast(? as bigint[]))", (Object) episodes);
        db.execute("delete from shot where project_id=? and user_id=? and episode_id=any(cast(? as bigint[]))", project, user, episodes);
    }

    private void deleteTasks(Long[] tasks) {
        db.execute("update image_creation set source_task_id=null where source_task_id=any(cast(? as bigint[]))", (Object) tasks);
        db.execute("update image_creation set result_task_id=null where result_task_id=any(cast(? as bigint[]))", (Object) tasks);
        db.execute("update asset_version set task_id=null where task_id=any(cast(? as bigint[]))", (Object) tasks);
        db.execute("delete from generation_admission where task_id=any(cast(? as bigint[]))", (Object) tasks);
        db.execute("delete from dispatch_dead_letter where task_id=any(cast(? as bigint[]))", (Object) tasks);
        db.execute("delete from task_outbox where task_id=any(cast(? as bigint[]))", (Object) tasks);
        db.execute("delete from task_attempt where task_id=any(cast(? as bigint[]))", (Object) tasks);
        db.execute("delete from task where id=any(cast(? as bigint[]))", (Object) tasks);
    }
}
