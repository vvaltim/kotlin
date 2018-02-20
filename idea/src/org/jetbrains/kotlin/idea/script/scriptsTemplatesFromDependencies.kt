/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.script

import com.intellij.ProjectTopics
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.vfs.JarFileSystem
import org.jetbrains.kotlin.idea.core.script.ScriptDefinitionContributor
import org.jetbrains.kotlin.idea.core.script.ScriptDefinitionsManager
import org.jetbrains.kotlin.idea.core.script.loadDefinitionsFromTemplates
import org.jetbrains.kotlin.idea.util.projectStructure.allModules
import org.jetbrains.kotlin.script.KotlinScriptDefinition
import java.io.File
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class ScriptTemplatesFromDependenciesProvider(private val project: Project) : ScriptDefinitionContributor {

    data class TemplatesWithCp(
        val templates: List<String>,
        val classpath: List<File>
    )

    private var templates: TemplatesWithCp? = null
    private val lock = ReentrantReadWriteLock()

    init {
        val connection = project.messageBus.connect()
        connection.subscribe(ProjectTopics.PROJECT_ROOTS, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent?) {
                var templatesChanged = false
                lock.read {
                    val newTemplates = getScriptTemplates()
                    if (newTemplates != templates) lock.write {
                        templates = newTemplates
                        templatesChanged = true
                    }
                }
                if (templatesChanged) {
                    ScriptDefinitionsManager.getInstance(project).reloadDefinitionsBy(this@ScriptTemplatesFromDependenciesProvider)
                }
            }
        })
    }

    private fun getScriptTemplates(): TemplatesWithCp {
        val templates = LinkedHashSet<String>()
        val classpath = LinkedHashSet<File>()
        val templatesPath = "META-INF/kotlin/script/templates/"
        project.allModules().forEach { module ->
            var templatesFound = false
            OrderEnumerator.orderEntries(module).classesRoots.forEach { vfile ->
                val root = JarFileSystem.getInstance().getJarRootForLocalFile(vfile) ?: vfile
                val dir = root.findFileByRelativePath(templatesPath)
                if (dir?.isDirectory == true) {
                    dir.children.forEach {
                        if (it.isValid && !it.isDirectory) {
                            templates.add(it.name)
                            templatesFound = true
                        }
                    }
                }
            }
            if (templatesFound) {
                classpath.addAll(OrderEnumerator.orderEntries(module).classesRoots.mapNotNull {
                    it.canonicalPath?.let { File(it.removeSuffix("!/")) }
                })
            }
        }
        return TemplatesWithCp(templates.toList(), classpath.toList())
    }

    override val id = "ScriptTemplatesFromDependenciesProvider"

    override fun getDefinitions(): List<KotlinScriptDefinition> {
        lock.read {
            if (templates == null) {
                val newTemplates = getScriptTemplates()
                lock.write {
                    templates = newTemplates
                }
            }
        }
        return loadDefinitionsFromTemplates(
            templateClassNames = templates!!.templates,
            templateClasspath = templates!!.classpath,
            environment = mapOf(
                "projectRoot" to (project.basePath ?: project.baseDir.canonicalPath)?.let(::File))
        )
    }
}

