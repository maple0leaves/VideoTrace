package com.example.server.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskEventTest {

    @Test
    void cancelledEventIsTerminal() {
        TaskEvent event = TaskEvent.of(
                TaskStatus.of(TaskStatus.State.CANCELLED, "cancelled"),
                TaskStage.CANCELLED);

        assertThat(event.terminal()).isTrue();
    }
}
