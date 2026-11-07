package com.taskmanagement.backend.board;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

// 「ボードの番号」（board_meta の board_id）を DB から読む係（docs/同時編集の設計書.md の 5 節）
// DB を作り直す・テストデータを投入すると番号が変わるので、覚えておかずに、毎回 DB から読む
// （1 行だけの小さな表を主キーで読むので、速い）
@Component
public class BoardIds {

    private final JdbcTemplate jdbc;

    public BoardIds(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // 今のボードの番号
    public UUID current() {
        return jdbc.queryForObject("SELECT board_id FROM board_meta WHERE id = 1", UUID.class);
    }
}
