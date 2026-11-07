package com.taskmanagement.backend.write;

// 「書き込みの鍵を、上限の時間（app.write-lock.timeout）までに取れなかった」ことを表す例外
// ほかの書き込みが長く続いているときに起きる。GlobalExceptionHandler が受け止めて、503 の返事（ProblemDetail）にする
public class WriteLockTimeoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WriteLockTimeoutException(Throwable cause) {
        super("いま別の書き込みが続いています。少し待ってから、もう一度お試しください", cause);
    }
}
