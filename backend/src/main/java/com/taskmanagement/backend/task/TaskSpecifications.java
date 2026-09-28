package com.taskmanagement.backend.task;

import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

// タスクを絞り込むときの「条件の部品」を作る
// Specification は、SQL の WHERE に入る条件を1つ表す。Specification.allOf でいくつかをまとめると、
// 「全部を満たす（AND）」条件になる
// 条件を付けないときは、Specification.unrestricted()（「条件なし」の部品）を返す
public final class TaskSpecifications {

    // 期限の「7日以内」は、今日から何日後までか（今日＋6日で、今日を含めて7日間）
    private static final int WEEK_DAYS_AFTER_TODAY = 6;

    // 部品を作るメソッドだけを持つクラスなので、new で作れないようにする
    private TaskSpecifications() {
    }

    // タスク名に keyword を含む（大文字・小文字は区別しない）
    public static Specification<Task> textContains(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return Specification.unrestricted();
        }
        // LIKE では % と _ が「何でもよい文字」という意味になるので、前に \ を付けて、ただの文字として探す
        String escaped = keyword.toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return (root, query, cb) -> cb.like(cb.lower(root.get("text")), "%" + escaped + "%", '\\');
    }

    // 優先度が priorities のどれか
    public static Specification<Task> priorityIn(List<TaskPriority> priorities) {
        if (priorities == null || priorities.isEmpty()) {
            return Specification.unrestricted();
        }
        return (root, query, cb) -> root.get("priority").in(priorities);
    }

    // 期限日が due の種類に当てはまる。today は「今日」の日付
    public static Specification<Task> dueMatches(DueFilter due, LocalDate today) {
        if (due == null) {
            return Specification.unrestricted();
        }
        return switch (due) {
            // 期限切れ：期限日 < 今日
            case OVERDUE -> (root, query, cb) -> cb.lessThan(root.get("dueDate"), today);
            // 7日以内：今日 ≦ 期限日 ≦ 今日＋6日
            case WEEK -> (root, query, cb) ->
                    cb.between(root.get("dueDate"), today, today.plusDays(WEEK_DAYS_AFTER_TODAY));
            // 期限なし：期限日が入っていない
            case NONE -> (root, query, cb) -> cb.isNull(root.get("dueDate"));
        };
    }
}
