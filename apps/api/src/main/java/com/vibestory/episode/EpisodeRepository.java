package com.vibestory.episode;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface EpisodeRepository extends JpaRepository<Episode, Long> {
    Optional<Episode> findByIdAndUserId(long id, long userId);
    List<Episode> findByProjectIdAndUserIdOrderByEpisodeIndex(long projectId, long userId);
    @Query("select coalesce(max(e.episodeIndex), 0) from Episode e where e.projectId = :projectId")
    int maxIndex(long projectId);
}
