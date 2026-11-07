package com.taskmanagement.backend.version;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

// 並べ替え（タスクの reorder・列の order）の「版の配列」の数を確かめる部品（docs/同時編集の設計書.md の 4.1 節）
// 版の配列（versions）は、並べる順番（orderedIds）と同じ数・同じ順でないと、どの版がどのカード・列のものか決められない
public final class VersionCounts {

    private VersionCounts() {
        // 部品を集めただけのクラスなので、new しない
    }

    // versions があるのに、orderedIds と数がちがえば 400。versions が null（省略）なら何もしない
    // 書き込みの鍵をとる前（Controller）で呼ぶ（入力チェックの 1 つ。設計書 4.3 節の 2）
    public static void requireSameCount(List<Long> orderedIds, List<Long> versions) {
        if (versions != null && versions.size() != orderedIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "並べる順番（orderedIds）と版（versions）の数をそろえてください");
        }
    }
}
