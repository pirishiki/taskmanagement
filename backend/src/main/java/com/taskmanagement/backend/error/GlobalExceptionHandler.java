package com.taskmanagement.backend.error;

import com.taskmanagement.backend.task.TaskNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

// グローバル例外ハンドラー：アプリのどこで起きたエラー（例外）も、ここで1か所にまとめて受け止める
// 返事はすべて ProblemDetail（Spring 標準のエラーの形。Content-Type は application/problem+json）にそろえる
// ResponseEntityExceptionHandler（Spring が用意した親クラス）を受け継ぐと、Spring の決まった例外
// （存在しない URL、id が数字でない など）は、親が ProblemDetail にしてくれる
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // 入力チェック（@Valid）の違反：どの項目がなぜだめかを errors に入れて返す（400）
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            // 1つの項目に違反が2つあるときは、最初の1つだけを返す
            errors.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
        }

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, "入力内容に誤りがあります");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    // JSON が読めない：形が崩れている、status・priority に決まっていない値が入っている など（400）
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                "送られてきた内容を読み取れませんでした。status は todo・doing・done、priority は high・medium・low のどれかを送ってください");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    // タスクが見つからない（404）
    @ExceptionHandler(TaskNotFoundException.class)
    public ProblemDetail handleTaskNotFound(TaskNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    // 上のどれにも当てはまらない、思いがけないエラー（500）
    // 例外の中身（どこで何が起きたか）は、外に見せると危ないので返事には書かず、サーバーのログにだけ出す
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex) {
        LOG.error("思いがけないエラーが起きました", ex);
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "サーバーでエラーが起きました");
    }
}
