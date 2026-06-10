package io.github.moacyrricardo.docusign.anchor;

/**
 * Why a text run was flagged as a candidate anchor (spec 004 §3). A candidate may carry both
 * reasons; its {@code reason} set is never empty.
 */
public enum CandidateReason {
    /** Effective on-page font size below the configured threshold (§3.1). */
    TINY,
    /** Near-white fill colour above the per-channel threshold (§3.2). */
    NEAR_WHITE
}
