package com.taskmanagement.backend.task;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

// タスク1件を表す。DB の tasks テーブルの1行に対応する
// テーブルの形（列・インデックス）の正本は Flyway の SQL（src/main/resources/db/migration）。ここを変えたら、SQL も足すこと
// （Hibernate は、ここと DB のテーブルが合っているかを起動時に確かめるだけ）
@Entity
@Table(name = "tasks")
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
