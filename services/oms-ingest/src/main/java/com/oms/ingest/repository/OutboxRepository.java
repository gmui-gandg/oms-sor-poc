package com.oms.ingest.repository;

import com.oms.ingest.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {
    
    @Query("SELECT o FROM OutboxEvent o WHERE o.published = false ORDER BY o.createdAt ASC")
    List<OutboxEvent> findUnpublishedEvents(org.springframework.data.domain.Pageable pageable);
    
    @Modifying
    @Query("UPDATE OutboxEvent o SET o.published = true, o.publishedAt = :publishedAt WHERE o.id = :id")
    int markAsPublished(Long id, Instant publishedAt);
}
