package com.taskmanagement.backend.column;

import com.taskmanagement.backend.idempotency.RequestTokens;
import com.taskmanagement.backend.task.TaskRepository;
import com.taskmanagement.backend.version.VersionConflictException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// 列のルールをまとめる係（作る・名前を変える・並べ替える・消す）
// 完了の列は「終わったこと」に決まっていて、付け替えはしない
// @Transactional：メソッドの中の DB 操作を「ひとまとまり」にする。途中で失敗したら、そのメソッドでした変更を全部取り消す
// 書き込みのメソッド（readOnly でないもの）は、始めるときに書き込みの鍵をとり、ほかの書き込みと 1 つずつ順番に動く
// （WriteLockTransactionManager。読み取りだけのメソッドには readOnly = true を付ける）
@Service
@Transactional
public class BoardColumnService {

    private final BoardColumnRepository columnRepository;

    // 列を消す前に、中にタスクがいるかを確かめるために使う
    private final TaskRepository taskRepository;

    // 列の追加の番号札（Idempotency-Key）を記録・確かめる係
    private final RequestTokens requestTokens;

    public BoardColumnService(BoardColumnRepository columnRepository, TaskRepository taskRepository,
            RequestTokens requestTokens) {
        this.columnRepository = columnRepository;
        this.taskRepository = taskRepository;
        this.requestTokens = requestTokens;
    }

    // 全部の列を、左から順に返す
    @Transactional(readOnly = true)
    public List<BoardColumn> findColumns() {
        return columnRepository.findAllByOrderBySortOrderAsc();
    }

    // 新しい列を、右端に足す。新しい列には「基本の列」「完了の列」の印をつけない（あとから消せる列になる）
    // idempotencyKey は番号札（ヘッダー Idempotency-Key）。null なら札なし（今までどおり毎回作る）
    // 札つきで、同じ札がもう使われていたら、新しく作らず、1 回目に作った列を返す（docs/二重送信の設計書.md の 2 節）
    // ・名前がちがう → 409（RequestTokens が投げる）
    // ・1 回目に作った列が、そのあと消されている → 404（作り直さない）
    public BoardColumn createColumn(String name, UUID idempotencyKey) {
        // 追加のたびに、24 時間をこえた古い札を消す（札なしの追加でも）
        requestTokens.deleteExpired();

        String trimmed = name.trim();
        if (idempotencyKey != null) {
            Optional<Long> createdBefore = requestTokens.claim(idempotencyKey, RequestTokens.Kind.COLUMN,
                    RequestTokens.fingerprint(trimmed));
            if (createdBefore.isPresent()) {
                Long id = createdBefore.get();
                return columnRepository.findById(id).orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "この番号札で作った列（id が " + id + "）は、すでに消されています"));
            }
        }

        double sortOrder = columnRepository.findTopByOrderBySortOrderDescIdDesc()
                .map(last -> last.getSortOrder() + 1)
                .orElse(0.0);
        BoardColumn created = columnRepository.save(new BoardColumn(trimmed, sortOrder, false, false));
        if (idempotencyKey != null) {
            requestTokens.complete(idempotencyKey, created.getId());
        }
        return created;
    }

    // 列の名前を変える。列がなければ 404
    // 版（version。null なら確かめない）が今の版とちがえば 409（current に列の今の状態）。確かめる順番は、ある（404）→ 版
    public BoardColumn renameColumn(Long id, String name, Long version) {
        BoardColumn column = findOrThrow(id);
        checkVersion(version, column);
        column.setName(name.trim());
        return columnRepository.save(column);
    }

    // 列を並べ替える。orderedIds は、全部の列の ID を、左から並べたい順に並べたもの
    // 列は多くても数個なので、全部の列に 0, 1, 2… と番号を振り直す（番号が変わった列だけ、保存のときに版が 1 上がる）
    // 足りない列・知らない列・同じ列が2回ある、のどれかなら 400
    // versions（orderedIds と同じ順の版。null なら確かめない）が 1 つでもちがえば、どの列も動かさずに 409（current なし）
    // 列の並べ替えだけは、ID がそろっているか（400）を版より先に見る（docs/同時編集の設計書.md の 4.4 節）
    // ID がそろっていないと、どの版をどの列と比べるかが決められないため
    public List<BoardColumn> reorderColumns(List<Long> orderedIds, List<Long> versions) {
        // 取ってきた列を「ID → 列」の表（Map）にしておくと、ID からすぐに引ける
        Map<Long, BoardColumn> columnsById = columnRepository.findAll().stream()
                .collect(Collectors.toMap(BoardColumn::getId, Function.identity()));

        boolean sameColumns = orderedIds.size() == columnsById.size()
                && new HashSet<>(orderedIds).equals(columnsById.keySet());
        if (!sameColumns) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "全部の列の ID を、1回ずつ並べて送ってください");
        }

        List<BoardColumn> ordered = orderedIds.stream().map(columnsById::get).toList();
        if (versions != null) {
            for (int i = 0; i < ordered.size(); i++) {
                VersionConflictException.check(versions.get(i), ordered.get(i).getVersion(),
                        VersionConflictException.COLUMN_ORDER, () -> null);
            }
        }
        for (int position = 0; position < ordered.size(); position++) {
            ordered.get(position).setSortOrder((double) position);
        }
        return columnRepository.saveAll(ordered);
    }

    // 列を消す。もうない番号なら、何もしない（Controller は 204 を返す。2 回目の削除も成功にする。設計書の 3 節）
    // 列があるとき、次の列は消せない（409：今のボードの状態と合わないので、できない）
    // ・基本の列（やるべきこと・進行中・終わったこと）：カンバンの基本の流れなので残す。完了の列（終わったこと）もこれに入る
    // ・中にタスクがいる列：うっかりタスクを失わないように。先にタスクをほかの列へ移すか、消してもらう
    // （基本の列は消せないので、列が1つもなくなることも、完了ボタン（○）の移し先がなくなることもない）
    // version（見ていた版。null なら確かめない）は、列があるときだけ、上の 2 つより先に比べる（ちがえば 409・VERSION_CONFLICT）
    // ほかの画面で名前を変えたばかりの列を、古い名前の確認のまま消さないため（docs/同時編集の設計書.md の 4.3・4.4 節）
    public void deleteColumn(Long id, Long version) {
        Optional<BoardColumn> found = columnRepository.findById(id);
        if (found.isEmpty()) {
            return;
        }
        BoardColumn column = found.get();
        checkVersion(version, column);
        if (column.isFixed()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "基本の列（やるべきこと・進行中・終わったこと）は消せません");
        }
        if (taskRepository.existsByColumnId(id)) {
            throw columnHasTasks(null);
        }

        // 確かめてから消すまでの一瞬のすき間に、ほかの端末がこの列にタスクを足すと、DB の外部キーが消すのを止める
        // （DataIntegrityViolationException）。そのときも、分かりにくい 500 ではなく、上と同じ 409 にする
        // （今は書き込みの鍵（WriteLockTransactionManager）で書き込みが 1 つずつなので、ふだんは起きない。念のための守り）
        // flush：消す命令を、今すぐ DB に送る。ふだんはメソッドの最後にまとめて送るので、ここで送らないと、
        // 止められたことに、この try の中で気づけない
        try {
            columnRepository.delete(column);
            columnRepository.flush();
        } catch (DataIntegrityViolationException ex) {
            throw columnHasTasks(ex);
        }
    }

    // 「タスクが入っている列は消せない」の 409。cause には、もとになった例外（なければ null）を入れる
    // （もとの例外をつないでおくと、サーバーのログで、本当は何が起きたかをたどれる）
    private static ResponseStatusException columnHasTasks(Throwable cause) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "タスクが入っている列は消せません。先にタスクをほかの列へ移すか、消してください", cause);
    }

    // 列を取ってくる。なければ 404
    private BoardColumn findOrThrow(Long id) {
        return columnRepository.findById(id)
                .orElseThrow(() -> new ColumnNotFoundException(id));
    }

    // 画面が見ていた版（expected。null なら省略＝確かめない）が、列の今の版とちがえば 409（current に列の今の状態）
    // 書き込みの鍵をとったあとに呼ぶので、比べてから書くまでの間に、ほかの書き込みが割り込まない
    private static void checkVersion(Long expected, BoardColumn column) {
        VersionConflictException.check(expected, column.getVersion(), VersionConflictException.COLUMN,
                () -> BoardColumnResponse.from(column));
    }
}
