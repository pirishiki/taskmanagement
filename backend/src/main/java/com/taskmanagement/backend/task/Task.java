package com.taskmanagement.backend.task;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

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

    // どの列にいるか（列の番号。board_columns テーブルの id）
    // 番号が本当にある列かどうかは、DB の外部キーが確かめる
    @Column(nullable = false)
    private Long columnId;

    // DB には小文字（"high" など）で保存する。変換は TaskPriorityConverter が受け持つ
    @Column(nullable = false)
    @Convert(converter = TaskPriorityConverter.class)
    private TaskPriority priority;

    private LocalDate dueDate;

    // 同じ列の中での並び順（小さいほど上）。小数なので、2枚のカードの間（例：0 と 1 の間の 0.5）にも入れられる
    @Column(nullable = false)
    private Double sortOrder;

    // 版の番号（docs/同時編集の設計書.md の 3 節）。作ったときは 0。中身（上の 5 つの項目）が変わって保存されるたびに、
    // JPA（Hibernate）が自動で 1 上げる。何も変わらなかったら上がらない
    // 書くときは「UPDATE … WHERE version = 読んだときの版」になり、もしずれていれば例外になる（DB の安全網。4.5 節）
    // 画面から送られた版と比べるのは TaskService（書き込みの鍵をとったあと）。アプリのコードからは書き換えない（set を用意しない）
    // 例外：ドラッグのときの番号の振り直し（TaskRepository.renumber）は、版に触らない一括の UPDATE で行う
    @Version
    @Column(nullable = false)
    private Long version;

    protected Task() {
        // JPA（Hibernate）が DB から読んだ行を Task に詰めるときに使う。アプリのコードからは呼ばない
    }

    public Task(String text, Long columnId, TaskPriority priority, LocalDate dueDate, Double sortOrder) {
        this.text = text;
        this.columnId = columnId;
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

    public Long getColumnId() {
        return columnId;
    }

    public void setColumnId(Long columnId) {
        this.columnId = columnId;
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

    public Double getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Double sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Long getVersion() {
        return version;
    }
}
