package com.pharmacy.pos.contracts;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Standardized Desktop POS API Response Envelope complying with ADR-0047.
 * Encapsulates success pagination metadata, standardized machine codes, distributed
 * correlation IDs, and localized bilingual status feedback.
 */
public class PosApiResponseEnvelope<T> {

    public enum UiState {
        LOADING,
        SUCCESS,
        EMPTY,
        ERROR,
        OFFLINE,
        PERMISSION_DENIED
    }

    public record PaginationMeta(
            int page,
            int pageSize,
            int totalCount,
            int totalPages,
            boolean hasNext,
            boolean hasPrevious,
            Integer nextPage,
            Integer previousPage
    ) {
        public PaginationMeta {
            if (page < 1) {
                throw new IllegalArgumentException("page must be >= 1");
            }
            if (pageSize < 1) {
                throw new IllegalArgumentException("pageSize must be >= 1");
            }
            if (totalCount < 0) {
                throw new IllegalArgumentException("totalCount must be >= 0");
            }
            if (totalPages < 0) {
                throw new IllegalArgumentException("totalPages must be >= 0");
            }
        }
    }

    private final boolean success;
    private final String code;
    private final String errorCode;
    private final String requestId;
    private final String timestamp;
    private final T data;
    private final PaginationMeta pagination;
    private final Map<String, String> filters;
    private final String messageEn;
    private final String messageBn;
    private final int httpStatus;
    private final boolean isRetryable;
    private final String recoveryAction;
    private final Map<String, List<String>> fieldErrors;

    private PosApiResponseEnvelope(Builder<T> builder) {
        this.success = builder.success;
        this.code = builder.code;
        this.errorCode = builder.errorCode;
        this.requestId = builder.requestId;
        this.timestamp = builder.timestamp != null ? builder.timestamp : Instant.now().toString();
        this.data = builder.data;
        this.pagination = builder.pagination;
        this.filters = Collections.unmodifiableMap(new HashMap<>(builder.filters));
        this.messageEn = builder.messageEn;
        this.messageBn = builder.messageBn;
        this.httpStatus = builder.httpStatus;
        this.isRetryable = builder.isRetryable;
        this.recoveryAction = builder.recoveryAction;
        this.fieldErrors = Collections.unmodifiableMap(new HashMap<>(builder.fieldErrors));
    }

    public boolean isSuccess() {
        return success;
    }

    public String getCode() {
        return code;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getRequestId() {
        return requestId;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public T getData() {
        return data;
    }

    public PaginationMeta getPagination() {
        return pagination;
    }

    public Map<String, String> getFilters() {
        return filters;
    }

    public String getMessageEn() {
        return messageEn;
    }

    public String getMessageBn() {
        return messageBn;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    public boolean isRetryable() {
        return isRetryable;
    }

    public String getRecoveryAction() {
        return recoveryAction;
    }

    public Map<String, List<String>> getFieldErrors() {
        return fieldErrors;
    }

    public String getLocalizedMessage(String languageCode) {
        if ("bn".equalsIgnoreCase(languageCode) && messageBn != null && !messageBn.isBlank()) {
            return messageBn;
        }
        return messageEn != null ? messageEn : "";
    }

    public UiState deriveUiState(boolean isNetworkOffline) {
        if (isNetworkOffline) {
            return UiState.OFFLINE;
        }
        if (!success) {
            if (httpStatus == 403 || "ERR_PERMISSION_DENIED".equalsIgnoreCase(errorCode)) {
                return UiState.PERMISSION_DENIED;
            }
            return UiState.ERROR;
        }
        if (pagination != null && pagination.totalCount() == 0) {
            return UiState.EMPTY;
        }
        if (data instanceof List<?> list && list.isEmpty()) {
            return UiState.EMPTY;
        }
        return UiState.SUCCESS;
    }

    public static <T> Builder<T> success(T data, String code) {
        return new Builder<T>(true)
                .code(code)
                .data(data)
                .httpStatus(200);
    }

    public static <T> Builder<T> error(String errorCode, int httpStatus) {
        return new Builder<T>(false)
                .errorCode(errorCode)
                .code(errorCode)
                .httpStatus(httpStatus);
    }

    public static class Builder<T> {
        private final boolean success;
        private String code = "OK";
        private String errorCode;
        private String requestId = "req-pos-local";
        private String timestamp;
        private T data;
        private PaginationMeta pagination;
        private Map<String, String> filters = new HashMap<>();
        private String messageEn = "Operation completed successfully.";
        private String messageBn = "অপারেশনটি সফলভাবে সম্পন্ন হয়েছে।";
        private int httpStatus = 200;
        private boolean isRetryable = false;
        private String recoveryAction = "RETRY_AFTER_BACKOFF";
        private Map<String, List<String>> fieldErrors = new HashMap<>();

        public Builder(boolean success) {
            this.success = success;
        }

        public Builder<T> code(String code) {
            this.code = Objects.requireNonNull(code);
            return this;
        }

        public Builder<T> errorCode(String errorCode) {
            this.errorCode = errorCode;
            return this;
        }

        public Builder<T> requestId(String requestId) {
            this.requestId = Objects.requireNonNull(requestId);
            return this;
        }

        public Builder<T> timestamp(String timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder<T> data(T data) {
            this.data = data;
            return this;
        }

        public Builder<T> pagination(PaginationMeta pagination) {
            this.pagination = pagination;
            return this;
        }

        public Builder<T> filters(Map<String, String> filters) {
            if (filters != null) {
                this.filters = new HashMap<>(filters);
            }
            return this;
        }

        public Builder<T> messageEn(String messageEn) {
            this.messageEn = Objects.requireNonNull(messageEn);
            return this;
        }

        public Builder<T> messageBn(String messageBn) {
            this.messageBn = Objects.requireNonNull(messageBn);
            return this;
        }

        public Builder<T> httpStatus(int httpStatus) {
            this.httpStatus = httpStatus;
            return this;
        }

        public Builder<T> retryable(boolean retryable) {
            this.isRetryable = retryable;
            return this;
        }

        public Builder<T> recoveryAction(String recoveryAction) {
            this.recoveryAction = recoveryAction;
            return this;
        }

        public Builder<T> fieldErrors(Map<String, List<String>> fieldErrors) {
            if (fieldErrors != null) {
                this.fieldErrors = new HashMap<>(fieldErrors);
            }
            return this;
        }

        public PosApiResponseEnvelope<T> build() {
            return new PosApiResponseEnvelope<>(this);
        }
    }
}
