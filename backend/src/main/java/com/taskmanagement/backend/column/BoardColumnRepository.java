package com.taskmanagement.backend.column;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

// 列を DB から出し入れする係。基本の出し入れ（findById・save・deleteById など）は JpaRepository が用意してくれる
// 並べるときは、sortOrder のあとに、必ず id も並びの決まりに入れる（〜IdAsc・〜IdDesc）
// sortOrder が同じ列どうしの並びを、DB の都合（行のしまい方）に任せず、毎回同じにするため
public interface BoardColumnRepository extends JpaRepository<BoardColumn, Long> {

    // 全部の列を、左から順に取る（ボードに並べるため）
    // 名前は今までのまま（テストからも使っている）。並びの第 2 のキー id は、@Query の ORDER BY で足している
    @Query("SELECT c FROM BoardColumn c ORDER BY c.sortOrder ASC, c.id ASC")
    List<BoardColumn> findAllByOrderBySortOrderAsc();

    // 並び順が一番大きい（一番右の）列を1つだけ取る。新しい列を右端に足すときに使う。列が1つもなければ空の Optional
    Optional<BoardColumn> findTopByOrderBySortOrderDescIdDesc();

    // 並び順が一番小さい（一番左の）列を1つだけ取る。列を指定せずにタスクを作ったときの置き場所。列が1つもなければ空の Optional
    // 並びが同じ列があっても、画面の一番左（findAllByOrderBySortOrderAsc の先頭）と同じ列になる
    Optional<BoardColumn> findTopByOrderBySortOrderAscIdAsc();

    // 「完了の列」の印がついた列を取る。印がついた列がなければ空の Optional
    Optional<BoardColumn> findByDoneTrue();
}
