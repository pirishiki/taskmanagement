package com.taskmanagement.backend.task;

import java.util.List;

public record ReorderRequest(String status, List<Long> orderedIds) {
}
