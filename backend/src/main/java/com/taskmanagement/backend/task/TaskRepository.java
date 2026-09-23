package com.taskmanagement.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TaskRepository extends JpaRepository<Task, Long> {

    List<Task> findByStatusOrderBySortOrderAsc(String status);

    List<Task> findAllByOrderByStatusAscSortOrderAsc();

    List<Task> findByTextContainingIgnoreCaseOrderByStatusAscSortOrderAsc(String keyword);
}
