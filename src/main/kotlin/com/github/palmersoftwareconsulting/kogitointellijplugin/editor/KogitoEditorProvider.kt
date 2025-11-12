package com.github.palmersoftwareconsulting.kogitointellijplugin.editor

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Provider that registers the Kogito editor for BPMN and DMN files.
 *
 * This provider integrates with IntelliJ's file editor system by:
 * - Detecting BPMN (.bpmn, .bpmn2) and DMN (.dmn) files
 * - Creating KogitoEditor instances for these files
 * - Hiding the default text editor (since Kogito provides visual editing)
 *
 * Implements DumbAware to work during indexing and dumb mode.
 *
 * @see KogitoEditor
 * @see EditorType
 */
class KogitoEditorProvider : FileEditorProvider, DumbAware {

    companion object {
        private val logger = Logger.getInstance(KogitoEditorProvider::class.java)

        /** Unique identifier for the Kogito editor type. */
        const val EDITOR_TYPE_ID = "kogito-editor"

        /** Set of file extensions this provider handles. */
        private val SUPPORTED_EXTENSIONS = setOf("bpmn", "bpmn2", "dmn")
    }

    /**
     * Determines whether this provider can handle the given file.
     *
     * @param project The project containing the file
     * @param file The virtual file to check
     * @return true if the file has a supported extension (.bpmn, .bpmn2, .dmn), false otherwise
     */
    override fun accept(project: Project, file: VirtualFile): Boolean {
        val accepted = file.extension?.lowercase() in SUPPORTED_EXTENSIONS
        logger.warn("KogitoEditorProvider.accept() called for file: ${file.path}, extension: ${file.extension}, accepted: $accepted")

        // Show notification for debugging
        if (accepted) {
            com.intellij.notification.NotificationGroupManager.getInstance()
                .getNotificationGroup("Kogito Editor")
                .createNotification(
                    "Kogito Editor",
                    "Opening ${file.name} with Kogito editor",
                    com.intellij.notification.NotificationType.INFORMATION
                )
                .notify(project)
        }

        return accepted
    }

    /**
     * Creates a new KogitoEditor instance for the specified file.
     *
     * Determines the editor type based on the file extension and instantiates
     * the appropriate editor.
     *
     * @param project The project containing the file
     * @param file The virtual file to open
     * @return A new KogitoEditor instance
     * @throws IllegalArgumentException if the file extension is not supported (should never happen if accept() is correct)
     */
    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        logger.info("KogitoEditorProvider.createEditor() called for file: ${file.path}")
        val editorType = when (file.extension?.lowercase()) {
            "dmn" -> EditorType.DMN
            "bpmn", "bpmn2" -> EditorType.BPMN
            else -> throw IllegalArgumentException("Unsupported file type: ${file.extension}")
        }

        logger.info("Creating KogitoEditor with type: $editorType")
        return KogitoEditor(project, file, editorType)
    }

    /**
     * Returns the unique identifier for this editor type.
     *
     * @return The editor type ID "kogito-editor"
     */
    override fun getEditorTypeId(): String = EDITOR_TYPE_ID

    /**
     * Returns the policy for how this editor should interact with default editors.
     *
     * HIDE_DEFAULT_EDITOR ensures that only the Kogito editor is shown, not the text editor.
     *
     * @return FileEditorPolicy.HIDE_DEFAULT_EDITOR
     */
    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * Enum representing the types of Kogito editors available.
 *
 * @property typeName The type name used in URLs and server communication
 * @property displayName The human-readable display name for the editor
 */
enum class EditorType(val typeName: String, val displayName: String) {
    /** Business Process Model and Notation editor. */
    BPMN("bpmn", "BPMN Editor"),

    /** Decision Model and Notation editor. */
    DMN("dmn", "DMN Editor")
}