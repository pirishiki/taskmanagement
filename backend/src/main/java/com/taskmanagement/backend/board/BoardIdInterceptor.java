package com.taskmanagement.backend.board;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

// ボードの番号（ヘッダー Board-Id）の係（docs/同時編集の設計書.md の 5 節）
// Controller より前（JSON を読む・入力チェックより前）に動く。ここで投げた例外も GlobalExceptionHandler が返事にする
// 1. /api/ のすべての返事に、今のボードの番号を Board-Id として付ける（エラーの返事にも付く。画面は、覚えた番号と比べる）
// 2. 書き込み（POST・PUT・PATCH・DELETE）に Board-Id が付いていたら、今の番号と比べる
//    ・UUID の形でない → 400
//    ・ちがう → 409（BOARD_CHANGED）。ほかのどの確かめよりも先（入力がおかしくても、もうない番号の削除でも 409）
//    ・ない → 確かめない（古い画面・API を直接呼ぶ人のため。版を省略したときと同じ考え）
// 読み取り（GET）は確かめない（読むだけなので困らない）
// ここ（鍵をとる前）を通ったあと、書き込みの鍵を待つ間にテストデータの投入が終わることがあるので、
// 鍵をとった直後にも、もう一度比べる（BoardIdGuard。そのために、送られた番号をリクエストに覚えておく）
public class BoardIdInterceptor implements HandlerInterceptor {

    public static final String HEADER = "Board-Id";

    // 画面が送ってきたボードの番号（UUID）を、リクエストの間だけ覚えておく場所の名前（BoardIdGuard が読む）
    public static final String SENT_ATTRIBUTE = BoardIdInterceptor.class.getName() + ".sent";

    // ここ（Controller の前）で読んだ今の番号（UUID）を、リクエストの間だけ覚えておく場所の名前（BoardIdResponseAdvice が読む）
    public static final String READ_ATTRIBUTE = BoardIdInterceptor.class.getName() + ".read";

    private static final Set<String> WRITE_METHODS = Set.of(
            HttpMethod.POST.name(), HttpMethod.PUT.name(), HttpMethod.PATCH.name(), HttpMethod.DELETE.name());

    // UUID の形（8-4-4-4-12 の 16 進。大文字も受け付ける）。UUID.fromString は「1-1-1-1-1」のような短い形も通すので、形は先にここで見る
    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final BoardIds boardIds;

    public BoardIdInterceptor(BoardIds boardIds) {
        this.boardIds = boardIds;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        UUID current = boardIds.current();
        // 返事のヘッダーは、先に付けておく（このあと 400・409 で止めるときの返事にも付く）
        response.setHeader(HEADER, current.toString());
        // 読んだ番号を覚えておく（BoardIdResponseAdvice が、返事の中身を書く直前に読み直した番号と比べる）
        request.setAttribute(READ_ATTRIBUTE, current);

        String sent = request.getHeader(HEADER);
        if (sent == null || !WRITE_METHODS.contains(request.getMethod())) {
            return true;
        }
        if (!UUID_FORMAT.matcher(sent).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ボードの番号（Board-Id）は UUID の形で送ってください");
        }
        UUID sentBoardId = UUID.fromString(sent);
        if (!sentBoardId.equals(current)) {
            throw new BoardChangedException();
        }
        // 書き込みの鍵をとったあとに、もう一度比べるために覚えておく（BoardIdGuard。鍵を待つ間の DB の投入に気づくため）
        request.setAttribute(SENT_ATTRIBUTE, sentBoardId);
        return true;
    }
}
