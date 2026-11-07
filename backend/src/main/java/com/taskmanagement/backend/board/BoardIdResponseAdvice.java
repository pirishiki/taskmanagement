package com.taskmanagement.backend.board;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.UUID;

// 返事の中身を書く直前に、ボードの番号を読み直して確かめる係（docs/同時編集の設計書.md の 5 節）
// BoardIdInterceptor は Controller の前（データを読む前）に番号を読んで付ける。そのあと、返事を作っている間に
// テストデータの投入がコミットされると、データが前の DB のものか、新しい DB のものか、サーバーには分からない
// ・前の番号のまま付けると：新しい DB のデータを、画面が前のボードのものと思い込む
// ・読み直した新しい番号を付けると：前の DB の（古い）データを、画面が新しいボードのものと思い込み、古い画面の入力が、
//   番号（id）が同じ別のカードを、版 0 どうしで黙って上書きするおそれがある
// そこで、2 回の番号が同じなら、そのまま（データはそのボードのもの）。ちがえば、どの本物の番号とも一致しない値を付ける
// 画面は、覚えた番号とちがうので黄色の帯を出す（最初の返事なら、その値を覚えるが、どの書き込みも 409 で止まり、帯が出る）
// 中身のない返事（204 など）は、ここを通らない（書き込みは、鍵のあとに BoardIdGuard が番号を確かめている）
@ControllerAdvice
public class BoardIdResponseAdvice implements ResponseBodyAdvice<Object> {

    private static final Logger LOG = LoggerFactory.getLogger(BoardIdResponseAdvice.class);

    private final BoardIds boardIds;

    public BoardIdResponseAdvice(BoardIds boardIds) {
        this.boardIds = boardIds;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType, ServerHttpRequest request,
            ServerHttpResponse response) {
        // BoardIdInterceptor が番号を読んだ返事（/api/ のもの。/api/health は除く）だけを確かめる
        if (!(request instanceof ServletServerHttpRequest servletRequest)
                || !(response instanceof ServletServerHttpResponse servletResponse)
                || !(servletRequest.getServletRequest().getAttribute(BoardIdInterceptor.READ_ATTRIBUTE)
                        instanceof UUID readBefore)) {
            return body;
        }
        try {
            if (!readBefore.equals(boardIds.current())) {
                // 返事を作っている間に番号が変わった：データがどちらの DB のものか分からないので、どの本物の番号とも
                // 一致しない値を付ける（setHeader は、先に付けた番号を 1 つに置き換える）
                servletResponse.getServletResponse().setHeader(BoardIdInterceptor.HEADER, UUID.randomUUID().toString());
            }
        } catch (DataAccessException ex) {
            // 読み直せなかったときは、番号が変わっていないとは言えないので、同じく一致しない値を付ける
            LOG.warn("返事のボードの番号を読み直せませんでした", ex);
            servletResponse.getServletResponse().setHeader(BoardIdInterceptor.HEADER, UUID.randomUUID().toString());
        }
        return body;
    }
}
