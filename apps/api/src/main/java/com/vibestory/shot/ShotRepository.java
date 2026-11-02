package com.vibestory.shot;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShotRepository extends JpaRepository<Shot, Long> {
    Optional<Shot> findByIdAndUserId(long id, long userId);
    List<Shot> findByEpisodeIdAndUserIdOrderBySeq(long episodeId, long userId);
}
