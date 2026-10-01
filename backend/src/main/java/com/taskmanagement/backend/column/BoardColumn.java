package com.taskmanagement.backend.column;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

// 列1つを表す。DB の board_columns テーブルの1行に対応する
// テーブルの形の正本は Flyway の SQL（V3__create_board_columns.sql）。ここを変えたら、SQL も足すこと
// （Column という名前は、すぐ下で使う @Column と紛らわしいので、BoardColumn にする）
@Entity
@Table(name = "board_columns")
public class BoardColumn {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    // 列の並び順（小さいほど左）。タスクと同じく小数なので、2つの列の間にも入れられる
    @Column(nullable = false)
    private Double sortOrder;

    // 「完了の列」の印。完了ボタン（○）を押したタスクは、この印がついた列へ移る。全部の列の中で1つまで
    @Column(name = "is_done", nullable = false)
    private boolean done;

    protected BoardColumn() {
        // JPA（Hibernate）が DB から読んだ行を BoardColumn に詰めるときに使う。アプリのコードからは呼ばない
    }

    public BoardColumn(String name, Double sortOrder, boolean done) {
        this.name = name;
        this.sortOrder = sortOrder;
        this.done = done;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Double getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Double sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isDone() {
        return done;
    }

    public void setDone(boolean done) {
        this.done = done;
    }
}
