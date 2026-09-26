package com.taskmanagement.backend.task;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "tasks", indexes = @Index(name = "idx_tasks_status_sort_order", columnList = "status, sort_order"))
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String text;

    // DB には小文字（"todo" など）で保存する。変換は TaskStatusConverter が受け持つ
    @Column(nullable = false)
    @Convert(converter = TaskStatusConverter.class)
    private TaskStatus status;

    // DB には小文字（"high" など）で保存する。変換は TaskPriorityConverter が受け持つ
    @Column(nullable = false)
    @Convert(converter = TaskPriorityConverter.class)
    private TaskPriority priority;

    private LocalDate dueDate;

    @Column(nullable = false)
    private Integer sortOrder;

    protected Task() {
        // JPA（Hibernate）が DB から読んだ行を Task に詰めるときに使う。アプリのコードからは呼ばない
    }

    public Task(String text, TaskStatus status, TaskPriority priority, LocalDate dueDate, Integer sortOrder) {
        this.text = text;
        this.status = status;
        this.priority = priority;
        this.dueDate = dueDate;
        this.sortOrder = sortOrder;
    }

    public Long getId() {
        return id;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(TaskStatus status) {
        this.status = status;
    }

    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(TaskPriority priority) {
        this.priority = priority;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public void setDueDate(LocalDate dueDate) {
        this.dueDate = dueDate;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }
}
