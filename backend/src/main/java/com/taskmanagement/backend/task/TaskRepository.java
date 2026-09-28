package com.taskmanagement.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

// JpaSpecificationExecutor：条件の部品（Specification、TaskSpecifications で作る）を渡して、
// findAll(条件, 並び順) のように取ってこられるようにする
public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    // その列で並び順が一番大きい（一番下の）タスクを1件だけ取る。列が空なら空の Optional
    Optional<Task> findTopByStatusOrderBySortOrderDesc(TaskStatus status);
}
