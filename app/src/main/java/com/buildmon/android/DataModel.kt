package com.buildmon.android

import kotlinx.serialization.Serializable

@Serializable
data class BuildJob(
    val project: String,
    val tool: String,
    val status: String,
    val progress: Float,
    val pid: Int,
    val duration_seconds: Int = 0
)

@Serializable
data class BuildUpdate(
    val type: String,
    val timestamp: Long = 0,
    val builds: List<BuildJob> = emptyList(),
    val cpu: Float = 0f,
    val active_count: Int = 0,
    // For finished/failed events
    val project: String = "",
    val tool: String = "",
    val duration_seconds: Int = 0,
    val success: Boolean = true,
    val error_line: String = ""
)
