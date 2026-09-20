const STORAGE_KEY = "tasks";
const PRIORITY_LABELS = { high: "高", medium: "中", low: "低" };
const PRIORITY_ORDER = { high: 0, medium: 1, low: 2 };

// tasks: [{ id, text, status, priority, dueDate }]
let tasks = loadTasks();
let editingId = null;

function loadTasks() {
  const saved = localStorage.getItem(STORAGE_KEY);
  return saved ? JSON.parse(saved) : [];
}

function saveTasks() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(tasks));
}

function render() {
  document.querySelectorAll(".card-list").forEach((list) => {
    list.innerHTML = "";
  });

  tasks.forEach((task) => {
    const list = document.getElementById(`${task.status}-list`);
    if (!list) return;

    const priority = task.priority || "medium";

    const card = document.createElement("div");
    card.className = "card";
    card.draggable = true;
    card.dataset.id = task.id;

    const titleRow = document.createElement("div");
    titleRow.className = "card-title-row";

    if (task.id === editingId) {
      const input = document.createElement("input");
      input.type = "text";
      input.value = task.text;
      input.className = "edit-input";

      const commit = () => {
        const value = input.value.trim();
        if (value) {
          task.text = value;
          saveTasks();
        }
        editingId = null;
        render();
      };

      input.addEventListener("keydown", (event) => {
        if (event.key === "Enter") {
          commit();
        } else if (event.key === "Escape") {
          editingId = null;
          render();
        }
      });
      input.addEventListener("blur", commit);

      titleRow.appendChild(input);
      requestAnimationFrame(() => input.focus());
    } else {
      const text = document.createElement("span");
      text.className = "card-text";
      text.textContent = task.text;

      const editButton = document.createElement("button");
      editButton.textContent = "✎";
      editButton.title = "編集";
      editButton.addEventListener("click", () => {
        editingId = task.id;
        render();
      });

      const deleteButton = document.createElement("button");
      deleteButton.textContent = "×";
      deleteButton.title = "削除";
      deleteButton.addEventListener("click", () => {
        tasks = tasks.filter((t) => t.id !== task.id);
        saveTasks();
        render();
      });

      titleRow.appendChild(text);
      titleRow.appendChild(editButton);
      titleRow.appendChild(deleteButton);
    }

    const metaRow = document.createElement("div");
    metaRow.className = "card-meta-row";

    const priorityBadge = document.createElement("span");
    priorityBadge.className = "priority-badge";
    priorityBadge.dataset.priority = priority;

    const prioritySelect = document.createElement("select");
    ["high", "medium", "low"].forEach((value) => {
      const option = document.createElement("option");
      option.value = value;
      option.textContent = PRIORITY_LABELS[value];
      if (value === priority) option.selected = true;
      prioritySelect.appendChild(option);
    });
    prioritySelect.addEventListener("change", () => {
      task.priority = prioritySelect.value;
      saveTasks();
      render();
    });

    priorityBadge.appendChild(prioritySelect);

    const dueDateInput = document.createElement("input");
    dueDateInput.type = "date";
    dueDateInput.className = "due-date-input";
    dueDateInput.value = task.dueDate || "";
    dueDateInput.addEventListener("change", () => {
      task.dueDate = dueDateInput.value;
      saveTasks();
      render();
    });

    metaRow.appendChild(priorityBadge);
    metaRow.appendChild(dueDateInput);

    card.addEventListener("dragstart", () => {
      card.classList.add("dragging");
    });
    card.addEventListener("dragend", () => {
      card.classList.remove("dragging");
    });

    card.appendChild(titleRow);
    card.appendChild(metaRow);
    list.appendChild(card);
  });
}

function setupAddForms() {
  document.querySelectorAll(".add-form").forEach((form) => {
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      const input = form.querySelector(".add-text");
      const prioritySelect = form.querySelector(".add-priority");
      const dueDateInput = form.querySelector(".add-due-date");
      const text = input.value.trim();
      if (!text) return;

      tasks.push({
        id: Date.now().toString(),
        text,
        status: form.dataset.status,
        priority: prioritySelect.value,
        dueDate: dueDateInput.value,
      });
      saveTasks();
      render();

      input.value = "";
      prioritySelect.value = "medium";
      dueDateInput.value = "";
    });
  });
}

function getDragAfterElement(list, y) {
  const cards = [...list.querySelectorAll(".card:not(.dragging)")];

  return cards.reduce(
    (closest, card) => {
      const box = card.getBoundingClientRect();
      const offset = y - box.top - box.height / 2;
      if (offset < 0 && offset > closest.offset) {
        return { offset, element: card };
      }
      return closest;
    },
    { offset: Number.NEGATIVE_INFINITY, element: null }
  ).element;
}

function setupDragAndDrop() {
  document.querySelectorAll(".card-list").forEach((list) => {
    list.addEventListener("dragover", (event) => {
      event.preventDefault();
      list.classList.add("drag-over");
    });

    list.addEventListener("dragleave", () => {
      list.classList.remove("drag-over");
    });

    list.addEventListener("drop", (event) => {
      event.preventDefault();
      list.classList.remove("drag-over");

      const draggingCard = document.querySelector(".dragging");
      if (!draggingCard) return;

      const taskId = draggingCard.dataset.id;
      const newStatus = list.closest(".column").dataset.status;

      const draggedIndex = tasks.findIndex((t) => t.id === taskId);
      if (draggedIndex === -1) return;

      const [draggedTask] = tasks.splice(draggedIndex, 1);
      draggedTask.status = newStatus;

      const afterElement = getDragAfterElement(list, event.clientY);
      if (afterElement) {
        const insertBeforeIndex = tasks.findIndex((t) => t.id === afterElement.dataset.id);
        tasks.splice(insertBeforeIndex, 0, draggedTask);
      } else {
        let lastIndexOfStatus = -1;
        tasks.forEach((t, i) => {
          if (t.status === newStatus) lastIndexOfStatus = i;
        });
        tasks.splice(lastIndexOfStatus + 1, 0, draggedTask);
      }

      saveTasks();
      render();
    });
  });
}

function sortColumn(status, criterion) {
  const indices = [];
  const subset = [];
  tasks.forEach((task, index) => {
    if (task.status === status) {
      indices.push(index);
      subset.push(task);
    }
  });

  const comparator =
    criterion === "priority"
      ? (a, b) => PRIORITY_ORDER[a.priority || "medium"] - PRIORITY_ORDER[b.priority || "medium"]
      : (a, b) => {
          if (!a.dueDate && !b.dueDate) return 0;
          if (!a.dueDate) return 1;
          if (!b.dueDate) return -1;
          return a.dueDate.localeCompare(b.dueDate);
        };

  subset.sort(comparator);
  indices.forEach((taskIndex, i) => {
    tasks[taskIndex] = subset[i];
  });

  saveTasks();
  render();
}

function setupSortControls() {
  document.querySelectorAll(".sort-select").forEach((select) => {
    select.addEventListener("change", () => {
      const criterion = select.value;
      if (!criterion) return;

      const status = select.closest(".column").dataset.status;
      sortColumn(status, criterion);
      select.value = "";
    });
  });
}

setupAddForms();
setupDragAndDrop();
setupSortControls();
render();
