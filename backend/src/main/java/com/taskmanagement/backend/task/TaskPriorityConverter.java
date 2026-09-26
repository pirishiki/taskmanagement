package com.taskmanagement.backend.task;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

// TaskPriority と DB の文字の変換係。DB には今までどおり "high" のような小文字で保存する（TaskStatusConverter と同じ作り）
@Converter
public class TaskPriorityConverter implements AttributeConverter<TaskPriority, String> {

    // Java → DB：TaskPriority.HIGH を "high" にして保存する
    @Override
    public String convertToDatabaseColumn(TaskPriority priority) {
        return priority == null ? null : priority.getValue();
    }

    // DB → Java：DB の "high" を TaskPriority.HIGH にして読み込む
    @Override
    public TaskPriority convertToEntityAttribute(String value) {
        return value == null ? null : TaskPriority.fromValue(value);
    }
}
