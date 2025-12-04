package com.github.palmersoftwareconsulting.kogitointellijplugin.editor

import com.intellij.openapi.vfs.VirtualFile

/**
 * Enum representing the types of Kogito editors available.
 *
 * This enum centralizes file extension handling for BPMN and DMN editors,
 * providing a single source of truth for supported file types.
 *
 * @property typeName The type name used in URLs and server communication
 * @property displayName The human-readable display name for the editor
 * @property extensions The file extensions handled by this editor type (lowercase, without dots)
 *
 * @since 0.0.1
 */
enum class EditorType(
    val typeName: String,
    val displayName: String,
    val extensions: Set<String>
) {
    /** Business Process Model and Notation editor. */
    BPMN("bpmn", "BPMN Editor", setOf("bpmn", "bpmn2")),

    /** Decision Model and Notation editor. */
    DMN("dmn", "DMN Editor", setOf("dmn"));

    companion object {
        /**
         * All file extensions supported by any Kogito editor.
         *
         * This is a lazily computed set of all extensions from all editor types,
         * useful for quickly checking if a file is handled by any Kogito editor.
         */
        val ALL_EXTENSIONS: Set<String> by lazy {
            entries.flatMap { it.extensions }.toSet()
        }

        /**
         * Determines the appropriate editor type for a given file.
         *
         * @param file The virtual file to check
         * @return The matching EditorType, or null if the file is not supported
         */
        fun fromFile(file: VirtualFile): EditorType? {
            val extension = file.extension?.lowercase() ?: return null
            return entries.find { extension in it.extensions }
        }

        /**
         * Checks if a file is supported by any Kogito editor.
         *
         * @param file The virtual file to check
         * @return true if the file has a supported extension, false otherwise
         */
        fun isSupported(file: VirtualFile): Boolean {
            return file.extension?.lowercase() in ALL_EXTENSIONS
        }
    }
}
