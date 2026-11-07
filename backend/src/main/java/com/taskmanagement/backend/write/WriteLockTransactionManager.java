package com.taskmanagement.backend.write;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.hibernate.Session;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.vendor.HibernateJpaDialect;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;

// 書き込みを 1 つずつ順番に処理するための、トランザクションの係（docs/二重送信の設計書.md の 4 節）
// Spring 標準の JpaTransactionManager と同じ働きに、1 つだけ足している：
// 書き込みのトランザクション（readOnly でないもの）を始めるとき、いちばん最初に DB の鍵（pg_advisory_xact_lock）をとる
//
// たとえ：お店のレジが 1 つだけの状態。書き込みの人は、レジの列に並んで 1 人ずつ会計する（読み取りだけの人は並ばない）
// - 鍵はアプリ全体で 1 つだけ（LOCK_KEY）。取る順番というものがないので、2 つの鍵を取り合うデッドロックは起きない
// - 鍵は、トランザクションの終わり（コミットでも、失敗の取り消しでも）に、DB が自動で外す。外し忘れがない
// - 鍵をとってから読むので、「読む → 書く」の間に、ほかの書き込みが割り込まない
//   （例：import 中に列が消える、同じ並び順の番号が 2 枚に付く、同じ番号札で 2 件できる、などを防ぐ）
// - 待つのは上限（lockTimeout。application.properties の app.write-lock.timeout）まで。こえたら待つのをやめて、
//   WriteLockTimeoutException（503）にする。ずっと待ち続けて、ほかの書き込みや DB の接続まで止まらないようにするため
//
// 各 Service の書き込みメソッドに「鍵をとる」1 行を書く代わりに、ここで @Transactional の書き込み全部に通す
// こうすると、新しい書き込みメソッドを足しても、呼び忘れが起きない
// 読み取りだけのメソッドは @Transactional(readOnly = true) にしておくと、鍵をとらない（待たされない）
//
// 注意：書き込みのトランザクションの中から、REQUIRES_NEW（別のトランザクション）で書き込みを始めてはいけない
// 外側が持っている鍵を、内側が上限まで待って 503 になってしまうため（今のコードでは使っていない）
public class WriteLockTransactionManager extends JpaTransactionManager {

    private static final long serialVersionUID = 1L;

    // アプリ全体で 1 つの鍵の番号。数字そのものに意味はない（この DB はこのアプリ専用なので、ほかと重ならない）
    static final long LOCK_KEY = 69_001L;

    // PostgreSQL の「鍵を待つ時間の上限をこえた」の印（SQLSTATE 55P03、lock_not_available）
    private static final String LOCK_NOT_AVAILABLE = "55P03";

    // 鍵を待つ時間の上限
    private final Duration lockTimeout;

    public WriteLockTransactionManager(Duration lockTimeout) {
        super();
        this.lockTimeout = lockTimeout;
    }

    // 親（JpaTransactionManager）は、ここで EntityManagerFactory から Hibernate 用の係（JpaDialect）を受け取る
    // そのあとで、鍵をとる係（WriteLockJpaDialect）に差し替える
    @Override
    public void afterPropertiesSet() {
        super.afterPropertiesSet();
        setJpaDialect(new WriteLockJpaDialect(lockTimeout.toMillis()));
    }

    // 鍵の時間切れは、親が「トランザクションを始められなかった」（CannotCreateTransactionException）に包んで投げる
    // 親が、始めかけたトランザクションを取り消して片づけたあとなので、中の WriteLockTimeoutException を取り出して投げ直す
    // （GlobalExceptionHandler が 503 にできるように。ほかの理由で始められなかったときは、そのまま投げる＝500）
    // PMD の PreserveStackTrace を止める理由：はがすのは Spring が包んだ外側だけ。時間切れのもとの例外（SQL の 55P03）は、
    // 投げ直す WriteLockTimeoutException の中にそのまま入っているので、何が起きたかはサーバーのログでたどれる
    @SuppressWarnings("PMD.PreserveStackTrace")
    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
        try {
            super.doBegin(transaction, definition);
        } catch (CannotCreateTransactionException ex) {
            if (ex.getCause() instanceof WriteLockTimeoutException timeout) {
                throw timeout;
            }
            throw ex;
        }
    }

    // トランザクションを始める係（Hibernate 用の標準の係に、鍵をとる処理を足したもの）
    // ここで失敗したときは、親の JpaTransactionManager が、始めかけたトランザクションを取り消して片づける
    private static final class WriteLockJpaDialect extends HibernateJpaDialect {

        private static final long serialVersionUID = 1L;

        private final long lockTimeoutMillis;

        WriteLockJpaDialect(long lockTimeoutMillis) {
            super();
            this.lockTimeoutMillis = lockTimeoutMillis;
        }

        @Override
        public Object beginTransaction(EntityManager entityManager, TransactionDefinition definition)
                throws PersistenceException, SQLException, TransactionException {
            Object transactionData = super.beginTransaction(entityManager, definition);
            if (!definition.isReadOnly()) {
                // トランザクションと同じ DB の接続で鍵をとる。ほかの書き込みが鍵を持っていれば、外れるまで（上限まで）ここで待つ
                entityManager.unwrap(Session.class).doWork(this::lock);
            }
            return transactionData;
        }

        private void lock(Connection connection) throws SQLException {
            // 1. このトランザクションだけ、鍵を待つ時間の上限を決める
            // set_config の 3 つ目の true は「このトランザクションの中だけ」（SET LOCAL と同じ）。終われば元の設定に戻る
            // （SET LOCAL lock_timeout = … は、値を ? で渡せないので、同じ働きの set_config を使う）
            String setTimeout = "SELECT set_config('lock_timeout', ?, true)";
            try (PreparedStatement statement = connection.prepareStatement(setTimeout)) {
                statement.setString(1, lockTimeoutMillis + "ms");
                statement.execute();
            }
            // 2. 鍵をとる。上限をこえたら、DB が 55P03 のエラーを返す → WriteLockTimeoutException（503）にする
            try (PreparedStatement statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(?)")) {
                statement.setLong(1, LOCK_KEY);
                statement.execute();
            } catch (SQLException ex) {
                if (LOCK_NOT_AVAILABLE.equals(ex.getSQLState())) {
                    throw new WriteLockTimeoutException(ex);
                }
                throw ex;
            }
        }
    }
}
