package dev.jamjet.spring.advisor;

/**
 * Thrown when a human-in-the-loop approval is rejected or times out
 * with a default decision of "rejected".
 */
public class ApprovalRejectedException extends RuntimeException {

    private final String executionId;

    public ApprovalRejectedException(String executionId, String reason) {
        super("Approval rejected for execution %s: %s".formatted(executionId,
                reason != null ? reason : "no reason provided"));
        this.executionId = executionId;
    }

    public String getExecutionId() {
        return executionId;
    }
}
