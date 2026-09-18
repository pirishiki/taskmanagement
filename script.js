const STORAGE_KEY = "tasks";

// tasks: [{ id, text, status }]
let tasks = loadTasks();

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

    const card = document.createElement("div");
    card.className = "card";
    card.draggable = true;
    card.dataset.id = task.id;

    const text = document.createElement("span");
    text.textContent = task.text;

    const deleteButton = document.createElement("button");
    deleteButton.textContent = "×";
    deleteButton.addEventListener("click", () => {
      tasks = tasks.filter((t) => t.id !== task.id);
      saveTasks();
      render();
    });

    card.addEventListener("dragstart", () => {
      card.classList.add("dragging");
    });
    card.addEventListener("dragend", () => {
      card.classList.remove("dragging");
    });

    card.appendChild(text);
    card.appendChild(deleteButton);
    list.appendChild(card);
  });
}

function setupAddForms() {
  document.querySelectorAll(".add-form").forEach((form) => {
    form.addEventListener("submit", (event) => {
      event.preventDefault();
      const input = form.querySelector("input");
      const text = input.value.trim();
      if (!text) return;

      tasks.push({
        id: Date.now().toString(),
        text,
        status: form.dataset.status,
      });
      saveTasks();
      render();

      input.value = "";
    });
  });
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

      const task = tasks.find((t) => t.id === taskId);
      if (task) {
        task.status = newStatus;
        saveTasks();
        render();
      }
    });
  });
}

setupAddForms();
setupDragAndDrop();
render();
