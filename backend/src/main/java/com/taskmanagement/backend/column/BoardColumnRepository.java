package com.taskmanagement.backend.column;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

// 列を DB から出し入れする係。基本の出し入れ（findById・save・deleteById など）は JpaRepository が用意してくれる
public interface BoardColumnRepository extends JpaRepository<BoardColumn, Long> {

    // 全部の列を、左から順に取る（ボードに並べるため）
    List<BoardColumn> findAllByOrderBySortOrderAsc();

    // 並び順が一番大きい（一番右の）列を1つだけ取る。新しい列を右端に足すときに使う。列が1つもなければ空の Optional
    Optional<BoardColumn> findTopByOrderBySortOrderDesc();

    // 「完了の列」の印がついた列を取る。印がついた列がなければ空の Optional
    Optional<BoardColumn> findByDoneTrue();
}
