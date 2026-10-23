package com.localshare.tv

/** Same kind names the phone server uses, worked out from the file name (for files sent to the TV). */
fun kindOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v", "ts", "mpg", "mpeg" -> "video"
    "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus" -> "audio"
    "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> "image"
    else -> "other"
}
