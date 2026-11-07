package com.taskmanagement.backend.write;

import java.sql.Connection;
import java.sql.SQLException;

// 書き込みの鍵をとった直後に、同じ DB の接続で行う確かめ（WriteLockTransactionManager が呼ぶ）
// 鍵をとってからなので、確かめてから書くまでの間に、同じ鍵をとるほかの書き込み（テストデータの投入も）が割り込まない
// だめなら RuntimeException を投げる（そのトランザクションは始まらず、投げた例外がそのまま Controller の外へ出る）
@FunctionalInterface
public interface AfterLockCheck {

    // 何もしない確かめ（確かめる係がないとき）
    AfterLockCheck NONE = connection -> {
    };

    void check(Connection connection) throws SQLException;
}
