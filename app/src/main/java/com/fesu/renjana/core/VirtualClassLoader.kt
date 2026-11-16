package com.fesu.renjana.core

import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Resources
import android.os.Build
import dalvik.system.DexClassLoader
import java.io.File

/**
 * VirtualClassLoader - Real implementation for loading guest app DEX files
 * 
 * This class creates an isolated classloader for each app instance,
 * allowing multiple instances of the same app to run simultaneously.
 */
class VirtualClassLoader(
    private val apkPath: String,
    private val instanceId: String,
    private val optimizedDir: File,
    private val guestPackageName: String? = null,
    private val nativeLibDir: String? = null,
    parent: ClassLoader? = null
) : ClassLoader(parent ?: ClassLoader.getSystemClassLoader()) {

    private val dexClassLoader: DexClassLoader
    private var resources: Resources? = null
    private var assetManager: AssetManager? = null

    companion object {
        fun resolveAllApkPaths(apkPath: String): List<String> {
            val paths = mutableListOf<String>()
            val mainFile = File(apkPath)
            if (mainFile.exists()) {
                paths.add(mainFile.absolutePath)
                val parentDir = mainFile.parentFile
                if (parentDir != null && parentDir.exists() && parentDir.isDirectory) {
                    parentDir.listFiles { file ->
                        file.isFile && file.name.endsWith(".apk") && file.absolutePath != mainFile.absolutePath
                    }?.forEach { split ->
                        paths.add(split.absolutePath)
                    }
                }
            } else {
                paths.add(apkPath)
            }
            return paths.distinct()
        }
    }

    private val allApkPaths: List<String> = resolveAllApkPaths(apkPath)

    init {
        // Record the real runtime identity before guest fingerprint spoofing is applied.
        // This distinguishes host-device diagnostics from values visible to guest apps.
        com.fesu.renjana.utils.RenjanaLog.i(
            "VirtualClassLoader",
            "Runtime diagnostics: sdkInt=${Build.VERSION.SDK_INT}, release=${Build.VERSION.RELEASE}, " +
                "incremental=${Build.VERSION.INCREMENTAL}, fingerprint=${Build.FINGERPRINT}, " +
                "hostDevice=${Build.MANUFACTURER}/${Build.MODEL}; guest=$guestPackageName; apk=$apkPath"
        )

        // Create optimized directory for this instance
        if (!optimizedDir.exists()) {
            optimizedDir.mkdirs()
        }

        // Initialize DexClassLoader with all APK paths (base + splits).
        //
        // CRITICAL: the DexClassLoader's parent must be the BOOT classloader
        // (framework classes only), NOT the host app loader. DexClassLoader uses
        // parent-first delegation, so a host parent makes every guest reference to
        // an app-level library (gson, androidx, kotlin-stdlib) resolve to
        // RENJANA'S copy — which is R8-obfuscated in release builds, producing
        // NoSuchMethodError ("No virtual method a() GsonBuilder") inside guest
        // Application/providers. With the boot parent, guests always use their own
        // bundled copies; the host fallback below stays available only through
        // VirtualClassLoader.loadClass's explicit last resort.
        //
        // Native libs: the guest's .so files live inside the APKs
        // (split_config.arm64_v8a.apk!/lib/arm64-v8a) or in the installed
        // nativeLibraryDir. DexClassLoader does NOT search APK lib dirs on its
        // own — without an explicit library path, guest Application constructors
        // calling System.loadLibrary() die with UnsatisfiedLinkError
        // (e.g. Cloudflare's libwarp_mobile.so).
        val combinedDexPath = allApkPaths.joinToString(File.pathSeparator)
        val libSearchPath = (listOfNotNull(nativeLibDir) + allApkPaths)
            .joinToString(File.pathSeparator)
        dexClassLoader = DexClassLoader(
            combinedDexPath,
            optimizedDir.absolutePath,
            libSearchPath,
            frameworkOnlyParent()
        )
    }

    /** Framework-only parent (BootClassLoader) so guests never see host app classes. */
    private fun frameworkOnlyParent(): ClassLoader = try {
        ClassLoader.getSystemClassLoader().parent ?: ClassLoader.getSystemClassLoader()
    } catch (_: Throwable) {
        ClassLoader.getSystemClassLoader()
    }

    /**
     * Load a class from the guest APK
     */
    override fun loadClass(name: String): Class<*> {
        return loadClass(name, false)
    }

    /**
     * Load a class from the guest APK with resolve flag
     */
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // Try to load from guest APK first
        return try {
            val clazz = dexClassLoader.loadClass(name)
            if (resolve) {
                resolveClass(clazz)
            }
            clazz
        } catch (e: ClassNotFoundException) {
            // Fall back to parent (system classes)
            super.loadClass(name, resolve)
        }
    }

    /**
     * Find a class in the guest APK
     */
    override fun findClass(name: String): Class<*> {
        return dexClassLoader.loadClass(name)
    }

    /**
     * Get resources from the guest APK
     */
    fun getResources(context: android.content.Context): Resources {
        if (resources == null) {
            resources = createResources(context)
        }
        return resources!!
    }

    /**
     * Get AssetManager from the guest APK
     */
    fun getAssets(): AssetManager {
        if (assetManager == null) {
            if (resources != null) {
                assetManager = resources!!.assets
            } else {
                assetManager = createAssetManager()
            }
        }
        return assetManager!!
    }

    /**
     * Create AssetManager for the guest APK
     * Uses reflection to access hidden Android APIs
     */
    private fun createAssetManager(): AssetManager {
        try {
            // AssetManager() constructor is package-private, use reflection
            val ctor = AssetManager::class.java.getDeclaredConstructor()
            ctor.isAccessible = true
            val assetManager = ctor.newInstance()
            val addAssetPathMethod = AssetManager::class.java.getDeclaredMethod(
                "addAssetPath",
                String::class.java
            )
            addAssetPathMethod.isAccessible = true
            var successfulPaths = 0
            for (path in allApkPaths) {
                try {
                    val result = addAssetPathMethod.invoke(assetManager, path) as? Int
                    if (result != null && result != 0) successfulPaths++
                    com.fesu.renjana.utils.RenjanaLog.i("VirtualClassLoader", "Added asset path: $path (cookie=$result)")
                } catch (e: Exception) {
                    com.fesu.renjana.utils.RenjanaLog.w("VirtualClassLoader", "Failed to add asset path $path: ${e.message}")
                }
            }
            if (successfulPaths == 0) {
                throw IllegalStateException("AssetManager accepted none of the APK paths: $allApkPaths")
            }
            assetManager = assetManager
            return assetManager
        } catch (e: Exception) {
            throw RuntimeException("Failed to create AssetManager for $apkPath", e)
        }
    }

    /**
     * Create Resources from the imported APK archive first. Looking up Resources by
     * package name first can silently select a different, installed build of the same
     * package; its resource table may not contain the imported APK's IDs. That leads to
     * Resources$NotFoundException even though the imported APK itself has the resource.
     *
     * The archive ApplicationInfo is populated with base + split paths so PackageManager
     * can construct a resource table for this exact import. AssetManager is the fallback.
     */
    private fun createResources(context: android.content.Context): Resources {
        val hostResources = context.resources
        val packageManager = context.packageManager

        try {
            val packageInfo = packageManager.getPackageArchiveInfo(apkPath, 0)
            val archiveInfo = packageInfo?.applicationInfo
            if (archiveInfo != null) {
                archiveInfo.sourceDir = apkPath
                archiveInfo.publicSourceDir = apkPath
                val splitPaths = allApkPaths.filter { it != apkPath }.toTypedArray()
                if (splitPaths.isNotEmpty()) {
                    archiveInfo.splitSourceDirs = splitPaths
                    archiveInfo.splitPublicSourceDirs = splitPaths
                }
                val archiveResources = packageManager.getResourcesForApplication(archiveInfo)
                com.fesu.renjana.utils.RenjanaLog.i(
                    "VirtualClassLoader",
                    "Loaded Resources from imported APK archive: package=${packageInfo.packageName}, " +
                        "splits=${splitPaths.size}, sdkInt=${Build.VERSION.SDK_INT}"
                )
                return archiveResources
            }
            com.fesu.renjana.utils.RenjanaLog.w(
                "VirtualClassLoader",
                "PackageManager could not parse imported APK archive: $apkPath"
            )
        } catch (e: Throwable) {
            com.fesu.renjana.utils.RenjanaLog.w(
                "VirtualClassLoader",
                "Archive resource loading failed for $apkPath; trying APK AssetManager fallback: ${e.message}",
                e
            )
        }

        try {
            val assets = getAssets()
            @Suppress("DEPRECATION")
            val fallbackResources = Resources(assets, hostResources.displayMetrics, hostResources.configuration)
            com.fesu.renjana.utils.RenjanaLog.i(
                "VirtualClassLoader",
                "Loaded Resources through imported APK AssetManager: apkPaths=$allApkPaths"
            )
            return fallbackResources
        } catch (e: Throwable) {
            com.fesu.renjana.utils.RenjanaLog.e(
                "VirtualClassLoader",
                "Unable to create guest Resources for package=$guestPackageName apk=$apkPath; " +
                    "runtime sdkInt=${Build.VERSION.SDK_INT}, release=${Build.VERSION.RELEASE}",
                e
            )
            throw e
        }
    }

    /**
     * Set native library path for loading .so files
     */
    fun setLibraryPath(libPath: String) {
        // Use reflection to set library path in DexClassLoader
        try {
            val pathListField = DexClassLoader::class.java.superclass
                .getDeclaredField("pathList")
            pathListField.isAccessible = true
            val pathList = pathListField.get(dexClassLoader)

            val nativeLibraryDirectoriesField = pathList.javaClass
                .getDeclaredField("nativeLibraryDirectories")
            nativeLibraryDirectoriesField.isAccessible = true

            val dirs = nativeLibraryDirectoriesField.get(pathList) as List<File>
            val newDirs = ArrayList(dirs)
            newDirs.add(File(libPath))
            nativeLibraryDirectoriesField.set(pathList, newDirs)
        } catch (e: Exception) {
            // Failed to set library path, native libs won't load
            println("Warning: Failed to set library path: ${e.message}")
        }
    }

    /**
     * Load a specific class by name from the guest APK
     */
    fun loadGuestClass(className: String): Class<*> {
        return dexClassLoader.loadClass(className)
    }

    /**
     * Check if a class exists in the guest APK
     */
    fun hasClass(className: String): Boolean {
        return try {
            dexClassLoader.loadClass(className)
            true
        } catch (e: ClassNotFoundException) {
            false
        }
    }
}
