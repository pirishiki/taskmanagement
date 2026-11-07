package com.taskmanagement.backend.task;

import com.taskmanagement.backend.column.BoardColumn;
import com.taskmanagement.backend.column.BoardColumnRepository;
import com.taskmanagement.backend.idempotency.RequestTokens;
import com.taskmanagement.backend.version.VersionConflictException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

// @Transactional：メソッドの中の DB 操作を「ひとまとまり」にする。途中で失敗したら、そのメソッドでした変更を全部取り消す
// クラスに付けると、すべての public メソッドに効く。読み取りだけのメソッドは readOnly = true にして、書き込まないことを伝える
// 書き込みのメソッド（readOnly でないもの）は、始めるときに書き込みの鍵をとり、ほかの書き込みと 1 つずつ順番に動く
// （WriteLockTransactionManager。読み取りだけのメソッドに readOnly を付け忘れると、書き込みと同じに待たされる）
@Service
@Transactional
public class TaskService {

    // 古い版（version 1）のファイルの status と、読み込むときに入れる列の名前。この順に左から並べる
    // 3つとも基本の列にし、最後の列（終わったこと）を完了の列にする
    private static final List<String> V1_STATUSES = List.of("todo", "doing", "done");
    private static final List<String> V1_COLUMN_NAMES = List.of("やるべきこと", "進行中", "終わったこと");

    // 基本の列（やるべきこと・進行中・終わったこと）の数。読み込むファイルにも、ちょうどこの数だけ入っている必要がある
    private static final int FIXED_COLUMN_COUNT = 3;

    // 読み込むファイルに入れられるタスクの数（全部の列の合計）
    private static final int MAX_IMPORT_TASKS = 10_000;

    private final TaskRepository taskRepository;

    // タスクの置き場所（列）が本当にあるかを確かめたり、読み込みで列を作り直したりするために使う
    private final BoardColumnRepository columnRepository;

    // 「今日」や「今」を決める壁の時計（日本時間。ClockConfig）。サーバーの時計（LocalDate.now()）は使わない
    private final Clock clock;

    // タスクの追加の番号札（Idempotency-Key）を記録・確かめる係
    private final RequestTokens requestTokens;

    public TaskService(TaskRepository taskRepository, BoardColumnRepository columnRepository, Clock clock,
            RequestTokens requestTokens) {
        this.taskRepository = taskRepository;
        this.columnRepository = columnRepository;
        this.clock = clock;
        this.requestTokens = requestTokens;
    }

    // タスクを取ってくる。keyword・priorities・due は、どれも省略できる（null なら、その条件では絞らない）
    // 条件が2つ以上あるときは、全部を満たすタスクだけを返す（AND）
    @Transactional(readOnly = true)
    public List<Task> findTasks(String keyword, List<TaskPriority> priorities, DueFilter due) {
        Specification<Task> conditions = Specification.allOf(
                TaskSpecifications.textContains(keyword),
                TaskSpecifications.priorityIn(priorities),
                TaskSpecifications.dueMatches(due, LocalDate.now(clock)));
        // 並び順は、列（columnId）ごとにまとめ、その中は sortOrder の小さい順
        // sortOrder が同じタスクどうしは id の小さい順（DB の都合で並びが毎回変わらないように）
        return taskRepository.findAll(conditions, Sort.by("columnId", "sortOrder", "id"));
    }

    @Transactional(readOnly = true)
    public Optional<Task> findTask(Long id) {
        return taskRepository.findById(id);
    }

    // 書き出し用：全部の列と、その中のタスク（絞り込みには関係なく）を、ファイルの形（TaskFile、今の版）にして返す
    // 列は左から、タスクは上から、画面と同じ順に並べる
    // REPEATABLE_READ：最初に DB に聞いたときの状態を、このメソッドの最後まで見続ける（docs/二重送信の設計書.md の 1 節 #7）
    // 書き出しは「列の一覧 → 列ごとのタスク」と何回も DB に聞く。ふつう（READ_COMMITTED）は 1 回聞くごとに最新の状態を見るので、
    // 途中で import が終わると「前の列 ＋ 消えたあとのタスク（空）」のような、どの時点にもなかったファイルになってしまう
    // 読み取りだけ（readOnly）なので、書き込みの鍵はとらない（import を待たせず、import にも待たされない）
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public TaskFile exportTasks() {
        List<TaskFileColumn> columns = columnRepository.findAllByOrderBySortOrderAsc().stream()
                .map(column -> new TaskFileColumn(
                        column.getName(),
                        column.isFixed(),
                        column.isDone(),
                        taskRepository.findByColumnIdOrderBySortOrderAsc(column.getId()).stream()
                                .map(TaskFileItem::from)
                                .toList()))
                .toList();
        return new TaskFile(TaskFile.CURRENT_VERSION, LocalDateTime.now(clock), columns, null);
    }

    // 読み込み用：今の列とタスクを全部消して、ファイル（TaskFile）の列とタスクに置き換える
    // クラスに @Transactional が付いているので、途中で失敗したら、消したことも含めて全部取り消される（全部か、何もしないか）
    // 中身のチェック（タスク名が空など）は、ここに来る前に Controller の @Valid が済ませている
    public void importTasks(TaskFile file) {
        // 1. 版を見て、今の形（列の一覧）にそろえる。読み込めない版や、おかしな中身なら、何も消さずに断る（400）
        List<TaskFileColumn> columns = switch (file.version()) {
            case TaskFile.CURRENT_VERSION -> file.columns();
            case TaskFile.VERSION_1 -> columnsFromV1(file.tasks());
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "この版（version: " + file.version()
                    + "）のファイルは読み込めません。読み込めるのは version が " + TaskFile.VERSION_1 + " か "
                    + TaskFile.CURRENT_VERSION + " のファイルです");
        };
        checkImportColumns(columns);

        // 2. 今のタスクと列を全部消す（deleteAllInBatch は、1件ずつではなく、1回の命令でまとめて消す）
        // タスクが列を指しているので（外部キー）、タスクを先に消す
        taskRepository.deleteAllInBatch();
        columnRepository.deleteAllInBatch();

        // 3. 列を左から順に作り、その中のタスクを上から順に作る。並び順は 0, 1, 2… と振り直す
        // id は DB が新しく付ける。列を先に保存すると列の id が決まるので、それをタスクに書く
        List<Task> tasks = new ArrayList<>();
        for (int columnPosition = 0; columnPosition < columns.size(); columnPosition++) {
            TaskFileColumn fileColumn = columns.get(columnPosition);
            BoardColumn column = columnRepository.save(
                    new BoardColumn(fileColumn.name().trim(), (double) columnPosition, fileColumn.fixed(),
                            fileColumn.done()));

            List<TaskFileItem> items = fileColumn.tasks();
            for (int position = 0; position < items.size(); position++) {
                TaskFileItem item = items.get(position);
                tasks.add(new Task(item.text().trim(), column.getId(), item.priority(), item.dueDate(),
                        (double) position));
            }
        }

        // 4. タスクをまとめて保存する
        taskRepository.saveAll(tasks);
    }

    // 読み込む列の一覧が、ボードとして使えるかを確かめる。だめなら 400
    private static void checkImportColumns(List<TaskFileColumn> columns) {
        if (columns == null || columns.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "列の一覧（columns）に、列を1つ以上入れてください");
        }
        long fixedCount = columns.stream().filter(TaskFileColumn::fixed).count();
        if (fixedCount != FIXED_COLUMN_COUNT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "基本の列（fixed が true の列）は、ちょうど"
                    + FIXED_COLUMN_COUNT + "つにしてください（今は " + fixedCount + " 個）");
        }
        long doneCount = columns.stream().filter(TaskFileColumn::done).count();
        if (doneCount != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "完了の列（done が true の列）は、ちょうど1つにしてください（今は " + doneCount + " 個）");
        }
        // 完了の列は消せない列でないと、消されて ○ ボタンの移し先がなくなるため
        boolean doneIsFixed = columns.stream().anyMatch(column -> column.done() && column.fixed());
        if (!doneIsFixed) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "完了の列は、基本の列（fixed が true の列）にしてください");
        }
        int taskCount = columns.stream().mapToInt(column -> column.tasks().size()).sum();
        if (taskCount > MAX_IMPORT_TASKS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "タスクは全部で" + MAX_IMPORT_TASKS + "件までにしてください");
        }
    }

    // 古い版（version 1）のタスクの一覧を、今の形（3つの列の一覧）に直す
    // status ごとに「やるべきこと／進行中／終わったこと」に分け、ファイルの並び順（sortOrder）の小さい順に並べる
    // sorted は、同じ番号どうしの順番を変えない（ファイルに書かれている順のまま）
    private static List<TaskFileColumn> columnsFromV1(List<TaskFileV1Item> items) {
        if (items == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "タスクの一覧（tasks）がありません");
        }
        for (TaskFileV1Item item : items) {
            if (!V1_STATUSES.contains(item.status())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "status は todo・doing・done のどれかにしてください: " + item.status());
            }
        }

        Map<String, List<TaskFileV1Item>> itemsByStatus = items.stream()
                .collect(Collectors.groupingBy(TaskFileV1Item::status));
        List<TaskFileColumn> columns = new ArrayList<>();
        for (int i = 0; i < V1_STATUSES.size(); i++) {
            List<TaskFileItem> tasks = itemsByStatus.getOrDefault(V1_STATUSES.get(i), List.of()).stream()
                    .sorted(Comparator.comparing(TaskFileV1Item::sortOrder))
                    .map(item -> new TaskFileItem(item.text(), item.priority(), item.dueDate()))
                    .toList();
            boolean done = i == V1_STATUSES.size() - 1;
            columns.add(new TaskFileColumn(V1_COLUMN_NAMES.get(i), true, done, tasks));
        }
        return columns;
    }

    // タスクを作る。idempotencyKey は番号札（ヘッダー Idempotency-Key）。null なら札なし（今までどおり毎回作る）
    // 札つきで、同じ札がもう使われていたら、新しく作らず、1 回目に作ったタスクを返す（docs/二重送信の設計書.md の 2 節）
    // ・中身がちがう → 409（RequestTokens が投げる）
    // ・1 回目に作ったタスクが、そのあと消されている → 404（作り直さない。消した人の意思を戻さない）
    public Task createTask(TaskRequest request, UUID idempotencyKey) {
        // 追加のたびに、24 時間をこえた古い札を消す（札なしの追加でも）
        requestTokens.deleteExpired();

        // 省略されたときの値：列は一番左の列、priority は「中」
        TaskPriority priority = request.priority() != null ? request.priority() : TaskPriority.MEDIUM;
        String text = request.text().trim();

        if (idempotencyKey != null) {
            // 指紋は「送られたとおりの要求」から作る（設計書 2 節）。送り直しは同じ要求なので、同じ指紋になる
            // ・列の番号は、省略なら省略（null）のまま入れる。「一番左の列」は、列の並べ替えで変わるので入れない
            //   （入れると、並べ替えのあとの送り直しが、中身がちがうと見なされて 409 になってしまう）
            // ・優先度の省略は medium として入れる（medium はいつも同じ値なので、省略と medium の明示を同じ中身にできる）
            String fingerprint = RequestTokens.fingerprint(text, priority.getValue(), request.dueDate(),
                    request.columnId());
            Optional<Long> createdBefore = requestTokens.claim(idempotencyKey, RequestTokens.Kind.TASK, fingerprint);
            if (createdBefore.isPresent()) {
                Long id = createdBefore.get();
                return taskRepository.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "この番号札で作ったタスク（id が " + id + "）は、すでに消されています"));
            }
        }

        // 列を決めて、あるかを確かめるのは、札を確かめたあと（1 回目のタスクを返すだけのときは、列がなくなっていても返せるように）
        // ここで 400 になったときは、上で入れた札も一緒に取り消される（同じトランザクションのため）
        Long columnId = request.columnId() != null ? requireColumn(request.columnId()) : firstColumnId();
        Task task = new Task(text, columnId, priority, request.dueDate(), nextOrderIn(columnId));
        Task created = taskRepository.save(task);
        if (idempotencyKey != null) {
            requestTokens.complete(idempotencyKey, created.getId());
        }
        return created;
    }

    // PUT 用：タスクの中身を丸ごと書き換える。タスクがなければ空の Optional を返す
    // 確かめる順番（docs/同時編集の設計書.md の 4.3 節）：ある → 版 → そのほか（ない列の columnId は 400）
    // 版を先に見るのは、古い画面からの頼みは古い状態をもとにしているので、「入力がおかしい」より「ほかで変わった」と知らせるため
    public Optional<Task> updateTask(Long id, TaskRequest request) {
        return taskRepository.findById(id).map(task -> {
            checkVersion(request.version(), task);
            // columnId と priority は省略されたら今の値のまま（null を入れない）
            Long columnId = request.columnId() != null ? requireColumn(request.columnId()) : task.getColumnId();
            TaskPriority priority = request.priority() != null ? request.priority() : task.getPriority();

            // 別の列に移るときは、移動先の列の一番下に置く
            if (!columnId.equals(task.getColumnId())) {
                task.setSortOrder(nextOrderIn(columnId));
            }

            task.setText(request.text().trim());
            task.setColumnId(columnId);
            task.setPriority(priority);
            task.setDueDate(request.dueDate());
            return taskRepository.save(task);
        });
    }

    // PATCH 用：送られてきた項目（null でないもの）だけを書き換える。タスクがなければ空の Optional を返す
    // 確かめる順番は PUT と同じ（ある → 版 → ない列の columnId は 400）
    public Optional<Task> patchTask(Long id, TaskPatchRequest request) {
        return taskRepository.findById(id).map(task -> {
            checkVersion(request.version(), task);
            // 別の列に移るときは、移動先の列の一番下に置く（完了ボタンなら、完了の列の一番下）
            if (request.columnId() != null && !request.columnId().equals(task.getColumnId())) {
                Long columnId = requireColumn(request.columnId());
                task.setSortOrder(nextOrderIn(columnId));
                task.setColumnId(columnId);
            }
            if (request.text() != null) {
                task.setText(request.text().trim());
            }
            if (request.priority() != null) {
                task.setPriority(request.priority());
            }
            if (request.dueDate() != null) {
                task.setDueDate(request.dueDate());
            }
            return taskRepository.save(task);
        });
    }

    // タスクを削除する。もうない番号なら、何もしない（Controller は、どちらでも 204 を返す）
    // 削除の目的は「そのタスクがなくなっていること」なので、2 回目の削除・別のタブで先に消した削除も成功にする
    // （docs/二重送信の設計書.md の 3 節）。もうないときは、版は見ない
    // あるのに版（version。null なら確かめない）がちがえば 409：ほかの画面で直した内容を、古い確認のまま消さない
    public void deleteTask(Long id, Long version) {
        taskRepository.findById(id).ifPresent(task -> {
            checkVersion(version, task);
            taskRepository.delete(task);
        });
    }

    // 画面が見ていた版（expected。null なら省略＝確かめない）が、タスクの今の版とちがえば 409（current に今の状態）
    // 書き込みの鍵をとったあとに呼ぶので、比べてから書くまでの間に、ほかの書き込みが割り込まない
    private static void checkVersion(Long expected, Task task) {
        VersionConflictException.check(expected, task.getVersion(), VersionConflictException.TASK,
                () -> TaskResponse.from(task));
    }

    // ドラッグ＆ドロップ用：カード（id）を、移動先の列（columnId）の、prevId のカードのすぐ下に入れる
    // 番号を変えるのは、動かしたカードだけ。ほかのカードには触らないので、画面に見えていないカードの順番もずれない
    // 動かすカードや prevId のカードがなければ 404、移動先の列がない・prevId が移動先の列にないなどのおかしな頼みなら 400
    // 確かめる順番（docs/同時編集の設計書.md の 4.3 節）：動かすカードがある（404）→ 動かすカードの版（409）→ 列・prevId（400・404）
    // prevId のカードの版は比べない（「Y のすぐ下に入れて」は、Y の位置が変わっていても意味が通るため。4.4 節）
    public Task move(Long id, MoveRequest request) {
        Task task = taskRepository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
        checkVersion(request.version(), task);
        Long columnId = requireColumn(request.columnId());
        Task prev = findPrev(id, request.prevId(), columnId); // null なら、列の一番上に入れる

        // 番号のすき間がなくなっていたら、列を 0, 1, 2… と振り直してから、もう一度決める
        Optional<Double> sortOrder = orderBelow(prev, columnId, id);
        if (sortOrder.isEmpty()) {
            // 振り直しは、版に触らない一括の UPDATE で行う（docs/同時編集の設計書.md の 3.2 節）
            // 見た目の順番は変わらないので、ほかのカードの版は上げない。上げると、この画面の次の操作がまちがって止まる
            // 一括の UPDATE は、手元に読んであるタスク（task・prev）を素通りして DB を直接書き換えるので、読み直す
            // （すき間がないのは prev のすぐ下を探したときだけなので、ここに来るとき prev は null ではない）
            taskRepository.renumber(columnId);
            task = taskRepository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
            Long prevId = Objects.requireNonNull(prev).getId();
            prev = taskRepository.findById(prevId).orElseThrow(() -> new TaskNotFoundException(prevId));
            sortOrder = orderBelow(prev, columnId, id);
        }

        // 動かしたカードは、列か番号が変わるので、保存するときに版が 1 上がる
        task.setColumnId(columnId);
        task.setSortOrder(sortOrder.orElseThrow());
        return taskRepository.save(task);
    }

    // 上のカード（prevId）を取ってくる。prevId が null なら null（列の一番上に入れる）
    // 動かすカード自身なら 400、ないなら 404、移動先の列になければ 400（どれも今までどおり）
    private Task findPrev(Long id, Long prevId, Long columnId) {
        if (prevId == null) {
            return null;
        }
        if (prevId.equals(id)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "動かすカード自身を、上のカード（prevId）にはできません");
        }
        Task prev = taskRepository.findById(prevId).orElseThrow(() -> new TaskNotFoundException(prevId));
        if (!prev.getColumnId().equals(columnId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上のカード（prevId）が、移動先の列にありません");
        }
        return prev;
    }

    // prev のすぐ下に入れるときの番号を決める（prev が null なら列の一番上）。すき間がなければ空の Optional
    // 動かしているカード自身（movingId）は、まだ元の位置にあるので、隣を探すときに数えない
    private Optional<Double> orderBelow(Task prev, Long columnId, Long movingId) {
        if (prev == null) {
            // 一番上のカードより 1 小さくする（列が空なら 0）
            return Optional.of(taskRepository.findFirstByColumnIdAndIdNotOrderBySortOrderAscIdAsc(columnId, movingId)
                    .map(first -> first.getSortOrder() - 1)
                    .orElse(0.0));
        }

        // DB で本当に prev のすぐ下にあるカード（画面に見えていなくても）を探す
        Optional<Task> next = taskRepository
                .findFirstByColumnIdAndSortOrderGreaterThanAndIdNotOrderBySortOrderAscIdAsc(
                        columnId, prev.getSortOrder(), movingId);
        if (next.isEmpty()) {
            return Optional.of(prev.getSortOrder() + 1); // prev が一番下なら、その下に入れる
        }

        // prev とすぐ下のカードの真ん中。すき間が狭すぎると、真ん中がどちらかと同じ数になってしまう
        double middle = (prev.getSortOrder() + next.get().getSortOrder()) / 2;
        if (middle == prev.getSortOrder() || middle == next.get().getSortOrder()) {
            return Optional.empty();
        }
        return Optional.of(middle);
    }

    // 自動並び替え（優先度順・期限が近い順）用：1つの列（columnId）の中で、送られてきたカードの順番を並べ替える
    // 「席の入れ替え」で行う：送られてきたカードが今持っている番号（席）を小さい順に並べ、送られてきた順番に配り直す
    // 送られてこなかったカード（絞り込みで画面に見えていないカード）の番号には触らないので、その順番はずれない
    // 列を移すことはしない（それは move の仕事）。別の列のカードが混じっていたら 400
    // 版（versions。orderedIds と同じ順。null なら確かめない）が 1 枚でもちがえば、どのカードも動かさずに 409
    // （一部だけ並べ替わると、並びが中途半端になるため。docs/同時編集の設計書.md の 4.4 節）
    // 返事は、並べ直したタスク（もう消えて飛ばしたものを除く）を、orderedIds の順に並べたもの（画面が新しい版を知るため）
    public List<Task> reorder(ReorderRequest request) {
        // 取ってきたタスクを「ID → タスク」の表（Map）にしておくと、ID からすぐに引ける
        Map<Long, Task> tasksById = taskRepository.findAllById(request.orderedIds()).stream()
                .collect(Collectors.toMap(Task::getId, Function.identity()));

        // 版は「別の列のカードが混じっている（400）」より先に見る（4.3 節）
        // ほかの画面で別の列へ動かされたカードは版が上がっているので、400 ではなく 409（ほかで変わった）と知らせられる
        // もう消えたカード（表にない id）は、今までどおり飛ばし、版も見ない。同じ id が 2 回あるときは、両方の版を比べる
        if (request.versions() != null) {
            for (int i = 0; i < request.orderedIds().size(); i++) {
                Task task = tasksById.get(request.orderedIds().get(i));
                if (task != null) {
                    VersionConflictException.check(request.versions().get(i), task.getVersion(),
                            VersionConflictException.TASK_REORDER, () -> null);
                }
            }
        }

        for (Task task : tasksById.values()) {
            if (!task.getColumnId().equals(request.columnId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "id が " + task.getId() + " のタスクは、並べ替える列（columnId）にありません");
            }
        }

        // 送られてきたカードが今座っている席（番号）を、小さい順に並べる
        List<Double> seats = tasksById.values().stream()
                .map(Task::getSortOrder)
                .sorted()
                .toList();

        // 送られてきた順番に、上の席から座ってもらう
        // 送られてきた ID のタスクがもう消えていたら飛ばす。同じ ID が2回あったら、2回目は飛ばす（distinct）
        List<Task> ordered = request.orderedIds().stream()
                .distinct()
                .map(tasksById::get)
                .filter(Objects::nonNull)
                .toList();
        // 席が変わったカードだけ、保存するときに版が 1 上がる（同じ席に座り直したカードは、中身が変わらないので上がらない）
        for (int seat = 0; seat < ordered.size(); seat++) {
            ordered.get(seat).setSortOrder(seats.get(seat));
        }

        return taskRepository.saveAll(ordered);
    }

    // 同じ列で一番大きい並び順より1つ大きくすると、列の一番下に入る（列が空なら 0）
    // 件数ではなく最大値を使うのは、削除で並び順に隙間ができても、ほかのタスクと同じ番号にならないようにするため
    private double nextOrderIn(Long columnId) {
        return taskRepository.findTopByColumnIdOrderBySortOrderDescIdDesc(columnId)
                .map(last -> last.getSortOrder() + 1)
                .orElse(0.0);
    }

    // その番号の列が本当にあるかを確かめて、そのまま返す。なければ 400
    // （確かめずに保存すると、DB の外部キーに止められて、わかりにくい 500 エラーになるため）
    private Long requireColumn(Long columnId) {
        if (!columnRepository.existsById(columnId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "id が " + columnId + " の列はありません");
        }
        return columnId;
    }

    // 一番左の列の番号。列が1つもないときは 400（完了の列は消せないので、ふだんは起きない）
    private Long firstColumnId() {
        return columnRepository.findTopByOrderBySortOrderAscIdAsc()
                .map(BoardColumn::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "タスクを入れる列がありません"));
    }
}
