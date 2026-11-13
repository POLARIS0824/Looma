package com.example.looma.feature.note

data class TitleEdit(val renameTo: String?, val restoreFileName: Boolean)

fun titleEditFor(draft: String, fileName: String, lastCommitted: String?): TitleEdit {
    val name = draft.trim()
    if (name.isEmpty()) return TitleEdit(renameTo = null, restoreFileName = true)
    if (name == fileName || name == lastCommitted) {
        return TitleEdit(renameTo = null, restoreFileName = false)
    }
    return TitleEdit(renameTo = name, restoreFileName = false)
}
