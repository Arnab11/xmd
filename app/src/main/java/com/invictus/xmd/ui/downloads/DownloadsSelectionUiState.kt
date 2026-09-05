package com.invictus.xmd.ui.downloads

/**
 * State and actions for multi-selection mode in Downloads, consumed by the
 * floating bottom selection pill that replaces the navigation bar.
 */
data class DownloadsSelectionUiState(
    val selectedCount: Int,
    val totalCount: Int = 0,
    val canPause: Boolean,
    val canStart: Boolean,
    val canRetry: Boolean,
    val canCopyLink: Boolean,
    val canShare: Boolean,
    val canDelete: Boolean,
    val onPause: () -> Unit,
    val onStart: () -> Unit,
    val onRetry: () -> Unit,
    val onCopyLink: () -> Unit,
    val onShare: () -> Unit,
    val onDelete: () -> Unit,
    val onClose: () -> Unit,
    val onSelectAll: () -> Unit = {},
    val onInvertSelection: () -> Unit = {},
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DownloadsSelectionUiState) return false
        return selectedCount == other.selectedCount &&
            totalCount == other.totalCount &&
            canPause == other.canPause &&
            canStart == other.canStart &&
            canRetry == other.canRetry &&
            canCopyLink == other.canCopyLink &&
            canShare == other.canShare &&
            canDelete == other.canDelete
    }

    override fun hashCode(): Int {
        var result = selectedCount
        result = 31 * result + totalCount
        result = 31 * result + canPause.hashCode()
        result = 31 * result + canStart.hashCode()
        result = 31 * result + canRetry.hashCode()
        result = 31 * result + canCopyLink.hashCode()
        result = 31 * result + canShare.hashCode()
        result = 31 * result + canDelete.hashCode()
        return result
    }
}
