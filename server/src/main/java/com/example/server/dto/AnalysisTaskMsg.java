package com.example.server.dto;

import java.io.Serializable;

public class AnalysisTaskMsg implements Serializable {

    public static final String START_ANALYSIS = "START_ANALYSIS";
    public static final String REVISE_ANALYSIS = "REVISE_ANALYSIS";

    private Long mediaId;
    private String action;
    private String contentHash;
    private String userGoal;
    private String runId;
    private long submittedAtEpochMs;
    private long deadlineAtEpochMs;

    public AnalysisTaskMsg() {}

    public AnalysisTaskMsg(Long mediaId, String action, String contentHash, String userGoal) {
        this(mediaId, action, contentHash, userGoal, null, 0, 0);
    }

    public AnalysisTaskMsg(Long mediaId,
                           String action,
                           String contentHash,
                           String userGoal,
                           String runId,
                           long submittedAtEpochMs,
                           long deadlineAtEpochMs) {
        this.mediaId = mediaId;
        this.action = action;
        this.contentHash = contentHash;
        this.userGoal = userGoal;
        this.runId = runId;
        this.submittedAtEpochMs = submittedAtEpochMs;
        this.deadlineAtEpochMs = deadlineAtEpochMs;
    }

    public Long getMediaId() { return mediaId; }
    public void setMediaId(Long mediaId) { this.mediaId = mediaId; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }
    public String getUserGoal() { return userGoal; }
    public void setUserGoal(String userGoal) { this.userGoal = userGoal; }
    public String getRunId() { return runId; }
    public void setRunId(String runId) { this.runId = runId; }
    public long getSubmittedAtEpochMs() { return submittedAtEpochMs; }
    public void setSubmittedAtEpochMs(long submittedAtEpochMs) {
        this.submittedAtEpochMs = submittedAtEpochMs;
    }
    public long getDeadlineAtEpochMs() { return deadlineAtEpochMs; }
    public void setDeadlineAtEpochMs(long deadlineAtEpochMs) {
        this.deadlineAtEpochMs = deadlineAtEpochMs;
    }

    public boolean isRevision() {
        return REVISE_ANALYSIS.equals(action);
    }

    public boolean hasSupportedAction() {
        return START_ANALYSIS.equals(action) || REVISE_ANALYSIS.equals(action);
    }
}
