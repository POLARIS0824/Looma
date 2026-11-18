package com.example.looma.data.note

import com.example.looma.domain.markdown.ImageSource
import java.io.File

internal fun resolveCover(root: File, noteFile: File, references: List<String>): String? =
    references.firstNotNullOfOrNull { reference ->
        (resolveImageSource(root.absolutePath, noteFile.absolutePath, reference) as? ImageSource.Local)?.path
    }

internal fun resolveCover(root: File, noteFile: File, reference: String?): String? =
    resolveCover(root, noteFile, listOfNotNull(reference))
