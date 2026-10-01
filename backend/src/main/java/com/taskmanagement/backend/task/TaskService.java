package com.taskmanagement.backend.task;

import com.taskmanagement.backend.column.BoardColumn;
import com.taskmanagement.backend.column.BoardColumnRepository;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

// @Transactional：メソッドの中の DB 操作を「ひとまとまり」にする。途中で失敗したら、そのメソッドでした変更を全部取り消す
// クラスに付けると、すべての public メソッドに効く。読み取りだけのメソッドは readOnly = true にして、書き込まないことを伝える
@Service
@Transactional
public class TaskService {

    // 古い版（version 1）のファイルの status と、読み込むときに入れる列の名前。この順に左から並べ、最後の列を完了の列にする
    private static final List<String> V1_STATUSES = List.of("todo", "doing", "done");
    private static final List<String> V1_COLUMN_NAMES = List.of("やるべきこと", "進行中", "終わったこと");

    // 読み込むファイルに入れられるタスクの数（全部の列の合計）
    private static final int MAX_IMPORT_TASKS = 10_000;

    private final TaskRepository taskRepository;

    // タスクの置き場所（列）が本当にあるかを確かめたり、読み込みで列を作り直したりするために使う
    private final BoardColumnRepository columnRepository;

    public TaskService(TaskRepository taskRepository, BoardColumnRepository columnRepository) {
        this.taskRepository = taskRepository;
        this.columnRepository = columnRepository;
    }

    // タスクを取ってくる。keyword・priorities・due は、どれも省略できる（null なら、その条件では絞らない）
    // 条件が2つ以上あるときは、全部を満たすタスクだけを返す（AND）
    @Transactional(readOnly = true)
    public List<Task> findTasks(String keyword, List<TaskPriority> priorities, DueFilter due) {
        Specification<Task> conditions = Specification.allOf(
                TaskSpecifications.textContains(keyword),
                TaskSpecifications.priorityIn(priorities),
                TaskSpecifications.dueMatches(due, LocalDate.now()));
        // 並び順は、列（columnId）ごとにまとめ、その中は sortOrder の小さい順
        return taskRepository.findAll(conditions, Sort.by("columnId", "sortOrder"));
    }

    @Transactional(readOnly = true)
    public Optional<Task> findTask(Long id) {
        return taskRepository.findById(id);
    }

    // 書き出し用：全部の列と、その中のタスク（絞り込みには関係なく）を、ファイルの形（TaskFile、今の版）にして返す
    // 列は左から、タスクは上から、画面と同じ順に並べる
    @Transactional(readOnly = true)
    public TaskFile exportTasks() {
        List<TaskFileColumn> columns = columnRepository.findAllByOrderBySortOrderAsc().stream()
                .map(column -> new TaskFileColumn(
                        column.getName(),
                        column.isDone(),
                        taskRepository.findByColumnIdOrderBySortOrderAsc(column.getId()).stream()
                                .map(TaskFileItem::from)
                                .toList()))
                .toList();
        return new TaskFile(TaskFile.CURRENT_VERSION, LocalDateTime.now(), columns, null);
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
                    new BoardColumn(fileColumn.name().trim(), (double) columnPosition, fileColumn.done()));

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
        long doneCount = columns.stream().filter(TaskFileColumn::done).count();
        if (doneCount != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "完了の列（done が true の列）は、ちょうど1つにしてください（今は " + doneCount + " 個）");
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
            columns.add(new TaskFileColumn(V1_COLUMN_NAMES.get(i), done, tasks));
        }
        return columns;
    }

    public Task createTask(TaskRequest request) {
        // 省略されたときの値：列は一番左の列、priority は「中」
        Long columnId = request.columnId() != null ? requireColumn(request.columnId()) : firstColumnId();
        TaskPriority priority = request.priority() != null ? request.priority() : TaskPriority.MEDIUM;

        Task task = new Task(request.text().trim(), columnId, priority, request.dueDate(), nextOrderIn(columnId));
        return taskRepository.save(task);
    }

    // PUT 用：タスクの中身を丸ごと書き換える。タスクがなければ空の Optional を返す
    public Optional<Task> updateTask(Long id, TaskRequest request) {
        return taskRepository.findById(id).map(task -> {
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
    public Optional<Task> patchTask(Long id, TaskPatchRequest request) {
        return taskRepository.findById(id).map(task -> {
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

    // タスクを削除する。あれば消して true、なければ何もせず false を返す
    public boolean deleteTask(Long id) {
        if (!taskRepository.existsById(id)) {
            return false;
        }
        taskRepository.deleteById(id);
        return true;
    }

    // ドラッグ＆ドロップ用：カード（id）を、移動先の列（columnId）の、prevId のカードのすぐ下に入れる
    // 番号を変えるのは、動かしたカードだけ。ほかのカードには触らないので、画面に見えていないカードの順番もずれない
    // 動かすカードや prevId のカードがなければ 404、移動先の列がない・prevId が移動先の列にないなどのおかしな頼みなら 400
    public Task move(Long id, MoveRequest request) {
        Task task = taskRepository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
        Long columnId = requireColumn(request.columnId());

        Task prev = null; // null のままなら、列の一番上に入れる
        if (request.prevId() != null) {
            if (request.prevId().equals(id)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "動かすカード自身を、上のカード（prevId）にはできません");
            }
            prev = taskRepository.findById(request.prevId())
                    .orElseThrow(() -> new TaskNotFoundException(request.prevId()));
            if (!prev.getColumnId().equals(columnId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "上のカード（prevId）が、移動先の列にありません");
            }
        }

        // 番号のすき間がなくなっていたら、列を 0, 1, 2… と振り直してから、もう一度決める
        Optional<Double> sortOrder = orderBelow(prev, columnId, id);
        if (sortOrder.isEmpty()) {
            renumber(columnId);
            sortOrder = orderBelow(prev, columnId, id);
        }

        task.setColumnId(columnId);
        task.setSortOrder(sortOrder.orElseThrow());
        return taskRepository.save(task);
    }

    // prev のすぐ下に入れるときの番号を決める（prev が null なら列の一番上）。すき間がなければ空の Optional
    // 動かしているカード自身（movingId）は、まだ元の位置にあるので、隣を探すときに数えない
    private Optional<Double> orderBelow(Task prev, Long columnId, Long movingId) {
        if (prev == null) {
            // 一番上のカードより 1 小さくする（列が空なら 0）
            return Optional.of(taskRepository.findFirstByColumnIdAndIdNotOrderBySortOrderAsc(columnId, movingId)
                    .map(first -> first.getSortOrder() - 1)
                    .orElse(0.0));
        }

        // DB で本当に prev のすぐ下にあるカード（画面に見えていなくても）を探す
        Optional<Task> next = taskRepository.findFirstByColumnIdAndSortOrderGreaterThanAndIdNotOrderBySortOrderAsc(
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

    // 列の番号を、今の順番のまま上から 0, 1, 2… と振り直す（真ん中の番号を作るすき間を、また空けるため）
    private void renumber(Long columnId) {
        List<Task> column = taskRepository.findByColumnIdOrderBySortOrderAsc(columnId);
        for (int position = 0; position < column.size(); position++) {
            column.get(position).setSortOrder((double) position);
        }
        taskRepository.saveAll(column);
    }

    // 自動並び替え（優先度順・期限が近い順）用：1つの列（columnId）の中で、送られてきたカードの順番を並べ替える
    // 「席の入れ替え」で行う：送られてきたカードが今持っている番号（席）を小さい順に並べ、送られてきた順番に配り直す
    // 送られてこなかったカード（絞り込みで画面に見えていないカード）の番号には触らないので、その順番はずれない
    // 列を移すことはしない（それは move の仕事）。別の列のカードが混じっていたら 400
    public void reorder(ReorderRequest request) {
        // 取ってきたタスクを「ID → タスク」の表（Map）にしておくと、ID からすぐに引ける
        Map<Long, Task> tasksById = taskRepository.findAllById(request.orderedIds()).stream()
                .collect(Collectors.toMap(Task::getId, Function.identity()));

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
        for (int seat = 0; seat < ordered.size(); seat++) {
            ordered.get(seat).setSortOrder(seats.get(seat));
        }

        taskRepository.saveAll(ordered);
    }

    // 同じ列で一番大きい並び順より1つ大きくすると、列の一番下に入る（列が空なら 0）
    // 件数ではなく最大値を使うのは、削除で並び順に隙間ができても、ほかのタスクと同じ番号にならないようにするため
    private double nextOrderIn(Long columnId) {
        return taskRepository.findTopByColumnIdOrderBySortOrderDesc(columnId)
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
        return columnRepository.findTopByOrderBySortOrderAsc()
                .map(BoardColumn::getId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "タスクを入れる列がありません"));
    }
}
