package com.example.mergedapp.upload

import java.io.File
interface UploadListener {
    fun onUploadStarted(file: File, metadata: UploadMetadata) {}
    fun onUploadCompleted(file: File, s3Key: String, metadata: UploadMetadata) {}
    fun onUploadFailed(file: File, error: String, metadata: UploadMetadata) {}
}

