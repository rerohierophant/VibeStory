package com.vibestory.style;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface CustomStyleRepository extends JpaRepository<CustomStyle, String> {
    List<CustomStyle> findByUserIdAndDeletedAtIsNullOrderByCreatedAt(long userId);
    Optional<CustomStyle> findByIdAndUserId(String id, long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CustomStyle s where s.id = :id and s.userId = :userId")
    Optional<CustomStyle> lock(long userId, String id);
}
