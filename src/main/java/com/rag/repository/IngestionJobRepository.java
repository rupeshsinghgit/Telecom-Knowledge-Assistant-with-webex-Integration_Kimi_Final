package com.rag.repository;

import com.rag.model.IngestionJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface IngestionJobRepository extends JpaRepository<IngestionJob, String> {
}
