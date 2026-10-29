package com.pharmacy.pos.models;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AsyncJobStatusDTO.
 * Architecture Decision: ADR-0031.
 */
class AsyncJobStatusDTOTest {

    @Test
    @DisplayName("Should correctly represent successful async job status with bilingual messages")
    void shouldRepresentSuccessfulJob() {
        AsyncJobStatusDTO dto = new AsyncJobStatusDTO(
                "job_test_001",
                "NOTIFICATION",
                "send_sale_notification_task",
                AsyncJobStatusDTO.JobState.SUCCESS,
                "Receipt SMS delivered.",
                "রসিদ এসএমএস সফলভাবে পাঠানো হয়েছে।",
                Instant.now(),
                Instant.now()
        );

        assertEquals("job_test_001", dto.getJobId());
        assertEquals("NOTIFICATION", dto.getCategory());
        assertTrue(dto.isCompleted());
        assertTrue(dto.isSuccess());
        assertEquals("Receipt SMS delivered.", dto.getLocalizedMessage("en"));
        assertEquals("রসিদ এসএমএস সফলভাবে পাঠানো হয়েছে।", dto.getLocalizedMessage("bn"));
        assertTrue(dto.getStatusBadgeStyle().contains("#34C759"));
    }

    @Test
    @DisplayName("Should throw NullPointerException when mandatory parameters are missing")
    void shouldRejectNullParameters() {
        assertThrows(NullPointerException.class, () -> new AsyncJobStatusDTO(
                null,
                "NOTIFICATION",
                "task",
                AsyncJobStatusDTO.JobState.PENDING,
                "",
                "",
                Instant.now(),
                null
        ));
    }
}
