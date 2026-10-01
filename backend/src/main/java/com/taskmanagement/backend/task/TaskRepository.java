package com.taskmanagement.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

// JpaSpecificationExecutor：条件の部品（Specification、TaskSpecifications で作る）を渡して、
// findAll(条件, 並び順) のように取ってこられるようにする
public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    // その列（columnId）で並び順が一番大きい（一番下の）タスクを1件だけ取る。列が空なら空の Optional
    Optional<Task> findTopByColumnIdOrderBySortOrderDesc(Long columnId);

    // その列に、タスクが1件でもあるか（列を消してよいかを確かめるため）
    boolean existsByColumnId(Long columnId);

    // ここから下は、カードを動かすとき（TaskService の move）に使う
    // 画面に見えていないカードも含めた、DB の本当の並びで探す。動かしているカード自身（excludedId）は数えない

    // その列の一番上のタスク。列が空なら空の Optional
    Optional<Task> findFirstByColumnIdAndIdNotOrderBySortOrderAsc(Long columnId, Long excludedId);

    // その列で、sortOrder の番号より下にあるタスクのうち、一番上のもの（＝すぐ下のタスク）。なければ空の Optional
    Optional<Task> findFirstByColumnIdAndSortOrderGreaterThanAndIdNotOrderBySortOrderAsc(
            Long columnId, Double sortOrder, Long excludedId);

    // その列のタスク全部を、上から順に取る（番号のすき間がなくなったときに、0, 1, 2… と振り直すため）
    List<Task> findByColumnIdOrderBySortOrderAsc(Long columnId);
}
