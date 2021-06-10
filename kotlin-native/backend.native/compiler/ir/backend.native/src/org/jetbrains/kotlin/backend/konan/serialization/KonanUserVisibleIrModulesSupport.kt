/*
 * Copyright 2010-2021 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.konan.serialization

import org.jetbrains.kotlin.backend.common.serialization.linkerissues.DefaultUserVisibleIrModulesSupport
import org.jetbrains.kotlin.backend.common.serialization.linkerissues.UserVisibleIrModulesSupport
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.konan.DeserializedKlibModuleOrigin
import org.jetbrains.kotlin.descriptors.konan.KlibModuleOrigin
import org.jetbrains.kotlin.konan.file.File
import org.jetbrains.kotlin.konan.library.KONAN_PLATFORM_LIBS_NAME_PREFIX
import org.jetbrains.kotlin.konan.library.KONAN_STDLIB_NAME
import org.jetbrains.kotlin.library.KotlinLibrary
import org.jetbrains.kotlin.library.RequiredUnresolvedLibrary
import org.jetbrains.kotlin.library.resolver.KotlinLibraryResolveResult
import org.jetbrains.kotlin.library.resolver.KotlinResolvedLibrary
import org.jetbrains.kotlin.library.uniqueName
import org.jetbrains.kotlin.library.unresolvedDependencies
import org.jetbrains.kotlin.utils.ResolvedDependenciesSupport
import org.jetbrains.kotlin.utils.ResolvedDependency
import org.jetbrains.kotlin.utils.ResolvedDependencyId

class KonanUserVisibleIrModulesSupport(
        private val resolvedLibraries: KotlinLibraryResolveResult,
        private val konanKlibDir: File,
        private val externalDependenciesFile: File?,
        private val onMalformedExternalDependencies: (String) -> Unit
) : UserVisibleIrModulesSupport() {
    override fun getUserVisibleModuleId(descriptor: ModuleDescriptor): ResolvedDependencyId =
            (descriptor.getCapability(KlibModuleOrigin.CAPABILITY) as? DeserializedKlibModuleOrigin)?.library?.moduleId
                    ?: DefaultUserVisibleIrModulesSupport.getUserVisibleModuleId(descriptor)

    override fun getUserVisibleModules(
            // All the necessary information is retrieved from [externalDependenciesFile] and [resolvedLibraries].
            @Suppress("UNUSED_PARAMETER") descriptors: Collection<ModuleDescriptor>
    ): Map<ResolvedDependencyId, ResolvedDependency> = compressedModules

    override val ResolvedDependencyId.isKotlinLibrary: Boolean
        get() = uniqueNames.any { uniqueName -> uniqueName == KONAN_STDLIB_NAME || uniqueName.startsWith(KOTLIN_LIBRARY_PREFIX) }

    /**
     * Load external [ResolvedDependency]s provided by the build system. These dependencies:
     * - all have [ResolvedDependency.selectedVersion] specified
     * - keep the information about which modules are roots (i.e. the source code module depends directly on them) and
     *   indirect modules (transitive dependencies)
     * - miss modules provided by Kotlin/Native distribution (stdlib, endorsed and platform libraries), as they are
     *   not visible to the build system
     */
    private fun externalDependencies(): MutableMap<ResolvedDependencyId, ResolvedDependency> {
        val externalDependencies = mutableMapOf<ResolvedDependencyId, ResolvedDependency>()

        if (externalDependenciesFile?.exists == true) {
            // Deserialize external dependencies from the [externalDependenciesFile].
            val externalDependenciesText = String(externalDependenciesFile.readBytes())
            val deserialized = ResolvedDependenciesSupport.deserialize(externalDependenciesText) { lineNo, line ->
                onMalformedExternalDependencies("Malformed external dependencies at $externalDependenciesFile:$lineNo: $line")
            }
            deserialized.associateByTo(externalDependencies) { it.id }
        }

        return externalDependencies
    }

    /**
     * Load [ResolvedDependency]s that represent all libraries participating in the compilation. Includes external dependencies,
     * but without version and hierarchy information. Also includes the libraries that are not visible to the build system
     * (and therefore are missing in [externalDependencies]) but are provided by the Kotlin/Native compiler:
     * stdlib, endorsed and platform libraries.
     */
    private fun modulesFromResolvedLibraries(): Map<ResolvedDependencyId, ResolvedDependency> {
        // Transform resolved libraries to [ModuleWithUninitializedDependencies]s.
        val modules: Map<ResolvedDependencyId, ModuleWithUninitializedDependencies> = resolvedLibraries.getFullResolvedList().associate { resolvedLibrary: KotlinResolvedLibrary ->
            val library = resolvedLibrary.library

            val moduleId = library.moduleId
            val module = ResolvedDependency(
                    id = moduleId,
                    selectedVersion = library.effectiveLibraryVersion,
                    requestedVersionsByIncomingDependencies = mutableMapOf(), // To be initialized in a separate pass below.
                    artifactPaths = mutableSetOf(library.libraryFile.absolutePath)
            )

            val outgoingDependencyIds = library.unresolvedDependencies.map { it.moduleId }

            moduleId to ModuleWithUninitializedDependencies(module, outgoingDependencyIds)
        }

        // Stamp dependencies.
        return modules.stampDependenciesWithRequestedVersionEqualToSelectedVersion()
    }

    /**
     * The result of the merge of [externalDependencies] with [modulesFromResolvedLibraries].
     */
    private fun mergedModules(): MutableMap<ResolvedDependencyId, ResolvedDependency> {
        // First, load external dependencies.
        val mergedModules: MutableMap<ResolvedDependencyId, ResolvedDependency> = externalDependencies()

        // The build system may express a group of modules where one module is a library KLIB and one or more modules
        // are just C-interop KLIBs as a single module with multiple artifacts. We need to expand them so that every particular
        // module/artifact will be represented as an individual [ResolvedDependency] object.
        val artifactPathsToOriginModules: MutableMap<String, ResolvedDependency> = mutableMapOf()
        val originModuleIds: MutableSet<ResolvedDependencyId> = mutableSetOf()
        mergedModules.values.forEach { originModule ->
            val artifactPaths: Set<String> = originModule.artifactPaths.takeIf { it.size > 1 } ?: return@forEach
            artifactPaths.forEach { artifactPath -> artifactPathsToOriginModules[artifactPath] = originModule }
            originModuleIds += originModule.id
        }

        // Next, merge external dependencies with dependencies from resolved libraries.
        modulesFromResolvedLibraries().forEach { (moduleId, module) ->
            val externalDependencyModule = mergedModules[moduleId]
            if (externalDependencyModule != null) {
                // Just add missing dependencies to the same module in [mergedModules].
                module.requestedVersionsByIncomingDependencies.forEach { (incomingDependencyId, requestedVersion) ->
                    if (incomingDependencyId !in externalDependencyModule.requestedVersionsByIncomingDependencies) {
                        val nonEmptyRequestedVersion = requestedVersion.ifEmpty {
                            // Fallback for the case when adding a dependency with empty requested version: There is no information
                            // about requested version for the dependency [incomingDependencyId] -> [externalDependencyModule] at all,
                            // so let's use the best effort which is the selected version from [externalDependencyModule].
                            externalDependencyModule.selectedVersion
                        }

                        externalDependencyModule.requestedVersionsByIncomingDependencies[incomingDependencyId] = nonEmptyRequestedVersion
                    }
                }
            } else {
                val originModule = module.artifactPaths.firstNotNullOfOrNull { artifactPathsToOriginModules[it] }
                if (originModule != null) {
                    // Handle artifacts that needs to be represented as individual [ResolvedDependency] objects.
                    module.selectedVersion = originModule.selectedVersion

                    val incomingDependencyIdsToStampRequestedVersion = module.requestedVersionsByIncomingDependencies.mapNotNull { (incomingDependencyId, requestedVersion) ->
                        if (requestedVersion.isEmpty()) incomingDependencyId else null
                    }
                    incomingDependencyIdsToStampRequestedVersion.forEach { incomingDependencyId ->
                        module.requestedVersionsByIncomingDependencies[incomingDependencyId] = originModule.selectedVersion
                    }

                    mergedModules[moduleId] = module
                } else {
                    // Just copy the module to [mergedModules]. If it has no incoming dependencies, then treat it as the root module
                    // (i.e. depended by the source code module).
                    if (module.requestedVersionsByIncomingDependencies.isEmpty()) {
                        module.requestedVersionsByIncomingDependencies[ResolvedDependencyId.ROOT] = module.selectedVersion
                    }
                    mergedModules[moduleId] = module
                }
            }
        }

        return mergedModules
    }

    /**
     * This is an optimization to avoid displaying 100+ Kotlin/Native platform libraries to the user.
     * Instead, lets compress them into a single row and avoid excessive output.
     */
    private val compressedModules: Map<ResolvedDependencyId, ResolvedDependency> by lazy {
        val compressedModules: MutableMap<ResolvedDependencyId, ResolvedDependency> = mergedModules()

        var platformLibrariesVersion: String? = null // Must be the same version to succeed.
        val platformLibraries: MutableList<ResolvedDependency> = mutableListOf() // All platform libraries to be patched.
        val outgoingDependencyIds: MutableSet<ResolvedDependencyId> = mutableSetOf() // All outgoing dependencies from platform libraries.

        for ((moduleId, module) in compressedModules) {
            if (moduleId.isKonanPlatformLibrary) {
                if (ResolvedDependencyId.ROOT !in module.requestedVersionsByIncomingDependencies) {
                    continue
                }

                platformLibrariesVersion = when (platformLibrariesVersion) {
                    null, module.selectedVersion -> module.selectedVersion
                    else -> {
                        // Multiple versions of platform libs. Give up.
                        return@lazy compressedModules
                    }
                }

                platformLibraries += module
            } else {
                module.requestedVersionsByIncomingDependencies.keys.forEach { incomingDependencyId ->
                    if (incomingDependencyId.isKonanPlatformLibrary) {
                        outgoingDependencyIds += moduleId
                    }
                }
            }
        }

        if (platformLibraries.isNotEmpty()) {
            platformLibraries.forEach { it.visibleAsFirstLevelDependency = false }

            val compressedModuleId = ResolvedDependencyId("$KONAN_PLATFORM_LIBS_NAME_PREFIX* (${platformLibraries.size} libraries)")
            val compressedModule = ResolvedDependency(
                    id = compressedModuleId,
                    selectedVersion = platformLibrariesVersion!!,
                    requestedVersionsByIncomingDependencies = mutableMapOf(ResolvedDependencyId.ROOT to platformLibrariesVersion),
                    artifactPaths = mutableSetOf()
            )

            outgoingDependencyIds.forEach { outgoingDependencyId ->
                val outgoingDependency = compressedModules.getValue(outgoingDependencyId)
                outgoingDependency.requestedVersionsByIncomingDependencies[compressedModuleId] = compressedModule.selectedVersion
            }

            compressedModules[compressedModuleId] = compressedModule
        }

        compressedModules
    }

    // For default libraries the version is the same as the version of the compiler. Note: Empty string means missing (unknown) version.
    private val KotlinLibrary.effectiveLibraryVersion: String
        get() {
            // This is much safer check then KotlinLibrary.isDefault, which may return false even for "stdlib" when
            // Kotlin/Native compiler is running with "-nostdlib", "-no-endorsed-libs", "-no-default-libs" arguments.
            val isDefault = libraryFile.startsWith(konanKlibDir)
            return (if (isDefault) versions.compilerVersion else null).orEmpty()
        }

    companion object {
        private val KotlinLibrary.moduleId: ResolvedDependencyId
            get() = ResolvedDependencyId(uniqueName)

        private val RequiredUnresolvedLibrary.moduleId: ResolvedDependencyId
            get() = ResolvedDependencyId(path) // Yep, it's named "path" but in fact keeps unique name of the library.

        private val ResolvedDependencyId.isKonanPlatformLibrary: Boolean
            get() = uniqueNames.any { it.startsWith(KONAN_PLATFORM_LIBS_NAME_PREFIX) }
    }
}
