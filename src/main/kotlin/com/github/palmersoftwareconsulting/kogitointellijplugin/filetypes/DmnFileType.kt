package com.github.palmersoftwareconsulting.kogitointellijplugin.filetypes

import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.fileTypes.PlainTextLanguage
import javax.swing.Icon

/**
 * File type definition for DMN (Decision Model and Notation) files.
 *
 * Registers .dmn file extension with IntelliJ, enabling:
 * - File recognition and icon display in the project tree
 * - Association with the KogitoEditorProvider
 * - Proper file type handling throughout the IDE
 *
 * Uses PlainTextLanguage as the underlying language since DMN is XML-based
 * but edited visually through the Kogito editor.
 *
 * @see BpmnFileType
 */
object DmnFileType : LanguageFileType(PlainTextLanguage.INSTANCE) {
    override fun getName(): String = "DMN"

    override fun getDescription(): String = "Decision Model and Notation file"

    override fun getDefaultExtension(): String = "dmn"

    override fun getDisplayName(): String = "DMN"

    override fun getIcon(): Icon? = DmnIcons.FILE
}

/**
 * Icon resources for DMN file types.
 */
object DmnIcons {
    /**
     * File icon for DMN files displayed in the project tree and editor tabs.
     *
     * Currently null (uses IntelliJ's default file icon).
     *
     * TODO: Add custom DMN icon to resources/icons/ directory:
     * - Recommended format: SVG (scalable) or PNG with @2x variant for Retina displays
     * - Recommended sizes: 16x16 (regular) and 32x32 (Retina @2x)
     * - Icon should follow IntelliJ's icon design guidelines for file types
     * - Use IconLoader.getIcon("/icons/dmn-file.svg", DmnIcons::class.java) to load
     * - Consider using a decision table or decision tree symbol to represent DMN
     */
    val FILE: Icon? = null
}