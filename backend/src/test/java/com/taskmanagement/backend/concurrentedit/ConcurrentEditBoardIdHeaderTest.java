package com.taskmanagement.backend.concurrentedit;

import com.taskmanagement.backend.board.BoardIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

// 同時編集の設計書（docs/同時編集の設計書.md）7.1 節 CE-45b・CE-45c：返事を作っている間に、ボードの番号が変わったとき
// サーバーは、番号を Controller の前（BoardIdInterceptor）と、返事の中身を書く直前（BoardIdResponseAdvice）の 2 回読む
// 2 回の番号がちがえば、データが前の DB のものか新しい DB のものか分からない。どちらの本物の番号を付けても、
// 画面が「そのボードのデータ」と思い込むおそれがある（古いデータ＋新しい番号なら、古い画面の入力が、番号が同じ別のカードを
// 版 0 どうしで上書きしかねない）。そこで、どの本物の番号とも一致しない値を付け、画面に黄色の帯を出させる
// 番号を読む係（BoardIds）を見張り、決まった回目に読む前後で、投入の代わりにボードの番号を SQL で変える
class ConcurrentEditBoardIdHeaderTest extends ConcurrentEditTestBase {

    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    @MockitoSpyBean
    private BoardIds boardIds;

    private String boardIdNow() {
        return jdbc.queryForObject("SELECT board_id::text FROM board_meta", String.class);
    }

    // GET /api/tasks を送り、返事の Board-Id が、変える前の番号とも、変えたあとの番号ともちがう（UUID の形）ことを確かめる
    private void assertHeaderMatchesNoRealBoard(String before) {
        MvcTestResult result = get("/api/tasks");

        assertThat(statusOf(result)).isEqualTo(200);
        String after = boardIdNow();
        assertThat(after).isNotEqualTo(before);
        String header = result.getResponse().getHeader("Board-Id");
        assertThat(header).matches(UUID_FORMAT);
        assertThat(header).isNotEqualTo(before);
        assertThat(header).isNotEqualTo(after);
    }

    @Test
    @DisplayName("CE-45b 1 回目に番号を読んだ直後（データを読む前）に番号が変わると、返事の Board-Id はどの本物の番号ともちがう")
    void ce45bChangedBeforeDataRead() {
        createT();
        String before = boardIdNow();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            UUID value = (UUID) invocation.callRealMethod();
            // 1 回目（Controller の前）に読んだ直後に、投入と同じくボードの番号を新しくする
            if (calls.incrementAndGet() == 1) {
                jdbc.update("UPDATE board_meta SET board_id = gen_random_uuid()");
            }
            return value;
        }).when(boardIds).current();

        assertHeaderMatchesNoRealBoard(before);
    }

    @Test
    @DisplayName("CE-45c データを読んだあと、2 回目に番号を読む直前に番号が変わっても、返事の Board-Id はどの本物の番号ともちがう"
            + "（古いデータに新しい番号を付けない）")
    void ce45cChangedAfterDataRead() {
        createT();
        String before = boardIdNow();
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            // 2 回目（返事の中身を書く直前。データはもう読み終えている）に読む直前に、ボードの番号を新しくする
            if (calls.incrementAndGet() == 2) {
                jdbc.update("UPDATE board_meta SET board_id = gen_random_uuid()");
            }
            return invocation.callRealMethod();
        }).when(boardIds).current();

        assertHeaderMatchesNoRealBoard(before);
    }
}
