package com.vibestory.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findByUserIdAndNameContainingIgnoreCaseOrderByUpdatedAtDescIdDesc(long userId, String name);
    Optional<Project> findByIdAndUserId(long id, long userId);
}
