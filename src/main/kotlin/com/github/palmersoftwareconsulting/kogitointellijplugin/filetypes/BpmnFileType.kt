package com.github.palmersoftwareconsulting.kogitointellijplugin.filetypes

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import javax.swing.Icon

/**
 * File type definition for BPMN (Business Process Model and Notation) files.
 *
 * Registers .bpmn and .bpmn2 file extensions with IntelliJ, enabling:
 * - File recognition and icon display in the project tree
 * - Association with the KogitoEditorProvider
 * - Proper file type handling throughout the IDE
 *
 * Uses PlainTextLanguage as the underlying language since BPMN is XML-based
 * but edited visually through the Kogito editor.
 *
 * @see DmnFileType
 */
object BpmnFileType : LanguageFileType(PlainTextLanguage.INSTANCE) {
    override fun getName(): String = "BPMN"

    override fun getDescription(): String = "Business Process Model and Notation file"

    override fun getDefaultExtension(): String = "bpmn"

    override fun getDisplayName(): String = "BPMN"

    override fun getIcon(): Icon? = BpmnIcons.FILE
}

/**
 * Icon resources for BPMN file types.
 */
object BpmnIcons {
    /**
     * File icon for BPMN files displayed in the project tree and editor tabs.
     *
     * Currently null (uses IntelliJ's default file icon).
     *
     * TODO: Add custom BPMN icon to resources/icons/ directory:
     * - Recommended format: SVG (scalable) or PNG with @2x variant for Retina displays
     * - Recommended sizes: 16x16 (regular) and 32x32 (Retina @2x)
     * - Icon should follow IntelliJ's icon design guidelines for file types
     * - Use IconLoader.getIcon("/icons/bpmn-file.svg", BpmnIcons::class.java) to load
     * - Consider using a process flow or workflow symbol to represent BPMN
     */
    val FILE: Icon? = null
}