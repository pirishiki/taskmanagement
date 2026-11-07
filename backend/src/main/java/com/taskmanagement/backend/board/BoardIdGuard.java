package com.taskmanagement.backend.board;

import com.taskmanagement.backend.write.AfterLockCheck;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

// ボードの番号の 2 回目の確かめ（書き込みの鍵をとった直後。docs/同時編集の設計書.md の 5 節）
// 1 回目（BoardIdInterceptor。Controller の前）を前の番号で通ったあと、鍵を待っている間に、テストデータの投入
// （同じ鍵をとって、番号を振り直し、ボードの番号を新しくする）が終わることがある。そのまま書くと、投入された
// 「番号（id）が同じ別のカード」（版も 0 どうしで見分けられない）を書き換えてしまうので、鍵をとったあとにもう一度比べる
// 投入は同じ鍵をとるので、ここで比べてから書き終わるまでの間に、番号が変わることはない
@Component
public class BoardIdGuard implements AfterLockCheck {

    @Override
    public void check(Connection connection) throws SQLException {
        // 画面が送ってきたボードの番号（1 回目の確かめで覚えたもの）。書き込みの API 以外（テストの準備など）や、
        // ヘッダーを付けない書き込みでは、ないので確かめない
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        Object sent = request == null ? null
                : request.getAttribute(BoardIdInterceptor.SENT_ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (!(sent instanceof UUID sentBoardId)) {
            return;
        }
        // 鍵をとったトランザクションと同じ接続で、今の番号を読む
        try (PreparedStatement statement = connection.prepareStatement("SELECT board_id FROM board_meta WHERE id = 1");
                ResultSet result = statement.executeQuery()) {
            if (!result.next() || !sentBoardId.equals(result.getObject(1, UUID.class))) {
                throw new BoardChangedException();
            }
        }
    }
}
