package com.taskmanagement.backend.task;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

// TaskStatus と DB の文字の変換係。DB には今までどおり "todo" のような小文字で保存する
// （JPA の標準の @Enumerated(EnumType.STRING) だと "TODO" のような大文字で保存され、今の DB のデータと合わなくなるため）
@Converter
public class TaskStatusConverter implements AttributeConverter<TaskStatus, String> {

    // Java → DB：TaskStatus.TODO を "todo" にして保存する
    @Override
    public String convertToDatabaseColumn(TaskStatus status) {
        return status == null ? null : status.getValue();
    }

    // DB → Java：DB の "todo" を TaskStatus.TODO にして読み込む
    @Override
    public TaskStatus convertToEntityAttribute(String value) {
        return value == null ? null : TaskStatus.fromValue(value);
    }
}
