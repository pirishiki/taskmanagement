package com.taskmanagement.backend.task;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

// JpaSpecificationExecutor：条件の部品（Specification、TaskSpecifications で作る）を渡して、
// findAll(条件, 並び順) のように取ってこられるようにする
// 並べるときは、sortOrder のあとに、必ず id も並びの決まりに入れる（〜IdAsc・〜IdDesc）
// sortOrder が同じタスクどうしの並びを、DB の都合（行のしまい方）に任せず、毎回同じにするため
public interface TaskRepository extends JpaRepository<Task, Long>, JpaSpecificationExecutor<Task> {

    // その列（columnId）で並び順が一番大きい（一番下の）タスクを1件だけ取る。列が空なら空の Optional
    Optional<Task> findTopByColumnIdOrderBySortOrderDescIdDesc(Long columnId);

    // その列に、タスクが1件でもあるか（列を消してよいかを確かめるため）
    boolean existsByColumnId(Long columnId);

    // ここから下は、カードを動かすとき（TaskService の move）に使う
    // 画面に見えていないカードも含めた、DB の本当の並びで探す。動かしているカード自身（excludedId）は数えない

    // その列の一番上のタスク。列が空なら空の Optional
    Optional<Task> findFirstByColumnIdAndIdNotOrderBySortOrderAscIdAsc(Long columnId, Long excludedId);

    // その列で、sortOrder の番号より下にあるタスクのうち、一番上のもの（＝すぐ下のタスク）。なければ空の Optional
    Optional<Task> findFirstByColumnIdAndSortOrderGreaterThanAndIdNotOrderBySortOrderAscIdAsc(
            Long columnId, Double sortOrder, Long excludedId);

    // その列のタスク全部を、上から順に取る（番号のすき間がなくなったときに、0, 1, 2… と振り直すため・書き出しのため）
    // 名前は今までのまま（テストからも使っている）。並びの第 2 のキー id は、@Query の ORDER BY で足している
    @Query("SELECT t FROM Task t WHERE t.columnId = :columnId ORDER BY t.sortOrder ASC, t.id ASC")
    List<Task> findByColumnIdOrderBySortOrderAsc(Long columnId);

    // その列の番号を、今の順番（sortOrder の小さい順、同じなら id の小さい順）のまま、上から 0, 1, 2… と振り直す
    // （move で、真ん中の番号を作るすき間がなくなったとき、すき間をまた空けるため）
    // 版（version）には触らない一括の UPDATE にする（docs/同時編集の設計書.md の 3.2 節）。見た目の順番は変わらないので、
    // 版を上げると、ほかのカードを見ている画面の次の操作が、まちがって 409 で止まってしまう
    // ROW_NUMBER() OVER (ORDER BY …)：並べた順に 1, 2, 3… の番号を付ける SQL の関数。1 を引いて 0 から始める
    // clearAutomatically：書き換えたあと、手元に読んであるタスク（古い番号のまま）を捨てる。使う側は読み直すこと
    // flushAutomatically：書き換える前に、まだ DB に送っていない変更を先に送る（順番が入れ替わらないように）
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE tasks AS t SET sort_order = r.position
            FROM (SELECT id, ROW_NUMBER() OVER (ORDER BY sort_order, id) - 1 AS position
                  FROM tasks WHERE column_id = :columnId) AS r
            WHERE t.id = r.id
            """, nativeQuery = true)
    void renumber(Long columnId);
}
