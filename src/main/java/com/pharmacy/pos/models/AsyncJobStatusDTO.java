package com.pharmacy.pos.models;

import java.time.Instant;
import java.util.Objects;

/**
 * Data Transfer Object for asynchronous worker job monitoring and notifications.
 * Architecture Decision: ADR-0003 & ADR-0031.
 * Strict separation: Counter sales are synchronous; notifications and background reports are asynchronous.
 */
public class AsyncJobStatusDTO {

    public enum JobState {
        PENDING,
        PROCESSING,
        SUCCESS,
        FAILED
    }

    private final String jobId;
    private final String category;
    private final String taskName;
    private final JobState state;
    private final String messageEn;
    private final String messageBn;
    private final Instant scheduledAt;
    private final Instant completedAt;

    public AsyncJobStatusDTO(
            String jobId,
            String category,
            String taskName,
            JobState state,
            String messageEn,
            String messageBn,
            Instant scheduledAt,
            Instant completedAt) {
        this.jobId = Objects.requireNonNull(jobId, "jobId cannot be null");
        this.category = Objects.requireNonNull(category, "category cannot be null");
        this.taskName = Objects.requireNonNull(taskName, "taskName cannot be null");
        this.state = Objects.requireNonNull(state, "state cannot be null");
        this.messageEn = messageEn != null ? messageEn : "";
        this.messageBn = messageBn != null ? messageBn : "";
        this.scheduledAt = scheduledAt != null ? scheduledAt : Instant.now();
        this.completedAt = completedAt;
    }

    public String getJobId() {
        return jobId;
    }

    public String getCategory() {
        return category;
    }

    public String getTaskName() {
        return taskName;
    }

    public JobState getState() {
        return state;
    }

    public String getMessageEn() {
        return messageEn;
    }

    public String getMessageBn() {
        return messageBn;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public boolean isCompleted() {
        return state == JobState.SUCCESS || state == JobState.FAILED;
    }

    public boolean isSuccess() {
        return state == JobState.SUCCESS;
    }

    public String getLocalizedMessage(String languageCode) {
        if ("bn".equalsIgnoreCase(languageCode)) {
            return messageBn.isEmpty() ? messageEn : messageBn;
        }
        return messageEn;
    }

    public String getStatusBadgeStyle() {
        switch (state) {
            case SUCCESS:
                return "-fx-background-color: #34C759; -fx-text-fill: white;";
            case FAILED:
                return "-fx-background-color: #FF3B30; -fx-text-fill: white;";
            case PROCESSING:
                return "-fx-background-color: #007AFF; -fx-text-fill: white;";
            case PENDING:
            default:
                return "-fx-background-color: #FF9500; -fx-text-fill: white;";
        }
    }
}
