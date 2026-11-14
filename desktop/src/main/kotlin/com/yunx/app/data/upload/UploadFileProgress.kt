package com.yunx.app.data.upload

enum class UploadFileStatus { WAITING, PREPARING, UPLOADING, FINISHING, COMPLETED, FAILED, CANCELLED }

data class UploadFileProgress(
    val candidate: UploadCandidate,
    val status: UploadFileStatus = UploadFileStatus.WAITING,
    val sent: Long = 0L,
    val bytesPerSecond: Double? = null,
    val error: String? = null,
    val stalled: Boolean = false
) {
    val isActive: Boolean get() = status == UploadFileStatus.PREPARING || status == UploadFileStatus.UPLOADING || status == UploadFileStatus.FINISHING
}
