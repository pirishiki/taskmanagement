package com.taskmanagement.backend.task;

import jakarta.persistence.Column;
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

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private String priority;

    private LocalDate dueDate;

    @Column(nullable = false)
    private Integer sortOrder;

    protected Task() {
        // JPA（Hibernate）が DB から読んだ行を Task に詰めるときに使う。アプリのコードからは呼ばない
    }

    public Task(String text, String status, String priority, LocalDate dueDate, Integer sortOrder) {
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getPriority() {
        return priority;
    }

    public void setPriority(String priority) {
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
