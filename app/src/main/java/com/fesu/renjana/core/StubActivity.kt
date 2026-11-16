package com.fesu.renjana.core

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.WindowManager
import com.fesu.renjana.hooks.CoreHooks
import com.fesu.renjana.hooks.PineHookManager
import com.fesu.renjana.models.Instance
import com.fesu.renjana.utils.RenjanaLog
import com.fesu.renjana.virtual.VirtualContext
import kotlinx.coroutines.launch
import java.lang.reflect.Method

/**
 * StubActivity - Transparent proxy Activity that delegates to a guest Activity.
 *
 * The Android OS manages this as a real Activity, but internally it:
 * 1. Reads guest activity class info from the launching Intent
 * 2. Loads the guest Activity via VirtualClassLoader
 * 3. Delegates ALL lifecycle, input, and callback methods to the guest
 *
 * 10 instances (StubActivity_0..StubActivity_9) are registered in the manifest,
 * allowing the container to run multiple guest Activities simultaneously.
 * Each instance is managed by [ActivityStubManager].
 */
abstract class StubActivity : Activity() {

    companion object {
        private const val TAG = "StubActivity"

        /** Intent extra: fully-qualified guest Activity class name */
        const val EXTRA_GUEST_ACTIVITY_CLASS = "stub_guest_class"

        /** Intent extra: container instance ID this stub belongs to */
        const val EXTRA_INSTANCE_ID = "stub_instance_id"

        /** Intent extra: APK path for the guest app */
        const val EXTRA_APK_PATH = "stub_apk_path"

        /** Intent extra: isolated data directory for this instance */
        const val EXTRA_DATA_PATH = "stub_data_path"

        /** Intent extra: guest package name */
        const val EXTRA_PACKAGE_NAME = "stub_package_name"

        /** Intent extra: guest app display name */
        const val EXTRA_APP_NAME = "stub_app_name"

        /** Intent extra: Google account ID assigned to this instance */
        const val EXTRA_ACCOUNT_ID = "stub_account_id"

        /** Intent extra: enable GMS virtualization flag */
        const val EXTRA_ENABLE_GMS = "stub_enable_gms"

        /** Intent extra: enable fingerprint spoofing flag */
        const val EXTRA_ENABLE_FINGERPRINT = "stub_enable_fingerprint"

        /** Intent extra: enable signature spoofing flag */
        const val EXTRA_SPOOF_SIGNATURE = "stub_spoof_signature"

        /** Intent extra: enable anti-detection flag */
        const val EXTRA_ENABLE_ANTI_DETECTION = "stub_enable_anti_detection"

        /** Intent extra: original Intent the guest wanted to receive */
        const val EXTRA_GUEST_ORIGINAL_INTENT = "stub_guest_intent"

        /** Intent extra: launch mode override (standard=0, singleTop=1, singleTask=2, singleInstance=3) */
        const val EXTRA_LAUNCH_MODE = "stub_launch_mode"

        /** Intent extra: request code for startActivityForResult forwarding */
        const val EXTRA_REQUEST_CODE = "stub_request_code"

        /** Intent extra: stub index (0-9) */
        const val EXTRA_STUB_INDEX = "stub_index"

        /**
         * Action asking an already-running stub to finish (per-app Close / Stop).
         * The stub finishes and its process self-destructs via onDestroy.
         */
        const val ACTION_FINISH_GUEST = "com.fesu.renjana.action.FINISH_GUEST"
    }

    /** Each concrete stub must return its index (0-9) */
    abstract fun getStubIndex(): Int

    private var guestActivity: Activity? = null
    private var virtualClassLoader: VirtualClassLoader? = null
    private var guestClassName: String = ""
    private var instanceId: String = ""
    private var guestPackageName: String = ""

    /** The guest's own Application, constructed and run by this stub process. */
    private var guestApplication: Application? = null

    /** The guest's real ActivityInfo, parsed from its manifest (theme, launchMode, ...). */
    private var guestActivityInfo: android.content.pm.ActivityInfo? = null

    /** Guest manifest theme, resolved only against guestResources. */
    private var effectiveGuestTheme: Int = 0

    /** The stub's window must always use the host's Resources and Theme. */
    private var hostResources: Resources? = null
    private var hostTheme: Resources.Theme? = null
    private var hostThemeResource: Int = 0

    /** Storage-isolated context handed to the guest Application/providers/activity. */
    private var virtualContext: VirtualContext? = null

    // Cached reflection references for guest lifecycle methods
    private var onCreateMethod: Method? = null
    private var onStartMethod: Method? = null
    private var onResumeMethod: Method? = null
    private var onPauseMethod: Method? = null
    private var onStopMethod: Method? = null
    private var onDestroyMethod: Method? = null
    private var onRestartMethod: Method? = null
    private var onNewIntentMethod: Method? = null
    private var onActivityResultMethod: Method? = null
    private var onSaveInstanceStateMethod: Method? = null
    private var onRestoreInstanceStateMethod: Method? = null
    private var onBackPressedMethod: Method? = null
    private var onKeyDownMethod: Method? = null
    private var onKeyUpMethod: Method? = null
    private var onTouchEventMethod: Method? = null
    private var onCreateOptionsMenuMethod: Method? = null
    private var onOptionsItemSelectedMethod: Method? = null
    private var onRequestPermissionsResultMethod: Method? = null
    private var onConfigurationChangedMethod: Method? = null
    private var onWindowFocusChangedMethod: Method? = null

    // ──────────────────────────────────────────────
    // Lifecycle: attachBaseContext
    // ──────────────────────────────────────────────

    override fun attachBaseContext(newBase: Context?) {
        try {
            androidx.appcompat.app.AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        } catch (_: Throwable) {}

        // Capture the framework-provided host context before any guest Resources are
        // installed. Android inflates the StubActivity window using this context.
        if (newBase != null) {
            hostResources = newBase.resources
            hostTheme = newBase.theme
        }
        super.attachBaseContext(newBase)
    }

    // ──────────────────────────────────────────────
    // Lifecycle: onCreate
    // ──────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Keep the host's already-created window/theme separate from guest resources.
        // The framework calls this Activity's getTheme() while constructing screen_simple;
        // returning a guest Theme there makes platform resource IDs resolve in the guest
        // AssetManager and triggers Resources$NotFoundException for host IDs.
        hostResources = super.getResources()
        hostTheme = super.getTheme()
        hostThemeResource = readThemeResource(this)

        // Per-app Close/Stop: this stub was asked to shut down (delivered either
        // as a fresh launch of a dead stub or a re-delivery to a live one).
        if (intent?.action == ACTION_FINISH_GUEST) {
            RenjanaLog.i(TAG, "StubActivity[${getStubIndex()}] received FINISH_GUEST — finishing")
            finish()
            return
        }

        try {
            androidx.appcompat.app.AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        } catch (_: Throwable) {}

        instanceId = intent.getStringExtra(EXTRA_INSTANCE_ID).orEmpty()
        guestPackageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()
        guestClassName = intent.getStringExtra(EXTRA_GUEST_ACTIVITY_CLASS).orEmpty()
        val apkPath = intent.getStringExtra(EXTRA_APK_PATH).orEmpty()
        val dataPath = intent.getStringExtra(EXTRA_DATA_PATH).orEmpty().ifEmpty {
            ActivityStubManager.getDataPathForInstance(instanceId).orEmpty()
        }
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty().ifEmpty {
            ActivityStubManager.getCachedInstance(instanceId)?.packageName.orEmpty()
        }
        val appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty()
        val accountId = intent.getStringExtra(EXTRA_ACCOUNT_ID)
        val enableGms = intent.getBooleanExtra(EXTRA_ENABLE_GMS, false)
        val enableFingerprint = intent.getBooleanExtra(EXTRA_ENABLE_FINGERPRINT, true)
        val spoofSignature = intent.getBooleanExtra(EXTRA_SPOOF_SIGNATURE, true)
        val enableAntiDetection = intent.getBooleanExtra(EXTRA_ENABLE_ANTI_DETECTION, true)

        if (instanceId.isEmpty() || guestClassName.isEmpty() || apkPath.isEmpty()) {
            RenjanaLog.e(TAG, "Missing required extras: instanceId=$instanceId, guest=$guestClassName, apk=$apkPath")
            finish()
            return
        }

        RenjanaLog.i(
            TAG,
            "StubActivity[${getStubIndex()}] onCreate (pid=${android.os.Process.myPid()}) → " +
                "launching guest $guestClassName for instance $instanceId; " +
                "runtime sdk=${android.os.Build.VERSION.SDK_INT}, release=${android.os.Build.VERSION.RELEASE}, " +
                "build=${android.os.Build.DISPLAY}, device=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL}"
        )

        ActivityStubManager.onStubOccupied(instanceId, getStubIndex(), guestClassName)

        try {
            CoreHooks.currentInstanceId.set(instanceId)

            val baseDataPath = dataPath.ifEmpty {
                "${filesDir.parent}/instances/$instanceId"
            }
            val effectiveDataPath = if (packageName.isNotBlank()) {
                "$baseDataPath/packages/$packageName"
            } else {
                baseDataPath
            }

            val resolvedPackageName = packageName.ifEmpty { null }
            virtualContext = VirtualContext(this, effectiveDataPath, resolvedPackageName)

            val optimizedDir = java.io.File(effectiveDataPath, "dex_opt").apply { mkdirs() }
            val guestNativeLibDir = resolveGuestNativeLibDir(apkPath, packageName, effectiveDataPath)
            virtualClassLoader = VirtualClassLoader(
                apkPath = apkPath,
                instanceId = instanceId,
                optimizedDir = optimizedDir,
                guestPackageName = packageName,
                nativeLibDir = guestNativeLibDir,
                parent = classLoader
            )

            // Install guest Resources only on the guest context. Never replace the
            // StubActivity's host Resources: framework window/theme inflation uses them.
            val guestResources = virtualClassLoader!!.getResources(this)
            virtualContext?.guestResources = guestResources

            val launchInfo = ApkLoader(this).getGuestLaunchInfo(apkPath, guestClassName)
            guestActivityInfo = launchInfo.activityInfo
            effectiveGuestTheme = launchInfo.activityInfo?.theme?.takeIf { it != 0 }
                ?: launchInfo.applicationTheme.takeIf { it != 0 }
                ?: 0

            val baseInstance = ActivityStubManager.getCachedInstance(instanceId)
            val effectivePackage = packageName.ifEmpty { launchInfo.packageName ?: baseInstance?.packageName ?: "com.example.guest" }
            val effectiveAppName = appName.ifEmpty { baseInstance?.appName ?: "Guest App" }
            val instance = baseInstance?.copy(
                packageName = effectivePackage,
                appName = effectiveAppName,
                dataPath = effectiveDataPath
            ) ?: Instance(
                id = instanceId,
                packageName = effectivePackage,
                appName = effectiveAppName,
                versionName = "1.0",
                versionCode = 1,
                apkPath = apkPath,
                iconPath = null,
                accountId = accountId,
                dataPath = effectiveDataPath,
                createdAt = System.currentTimeMillis(),
                lastUsed = System.currentTimeMillis(),
                isActive = true,
                config = com.fesu.renjana.models.InstanceConfig(
                    enableGms = enableGms,
                    enableFingerprint = enableFingerprint,
                    spoofSignature = spoofSignature,
                    enableAntiDetection = enableAntiDetection
                )
            )

            if (PineHookManager.isAvailable()) {
                PineHookManager.installGuestHooks(
                    effectivePackage,
                    virtualClassLoader!!,
                    instanceId,
                    effectiveDataPath,
                    apkPath,
                    instance = instance
                )
            }

            val assignedAccountId = accountId
            if (instance.config.enableGms && assignedAccountId != null) {
                try {
                    com.fesu.renjana.RenjanaApplication.get().applicationScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        com.fesu.renjana.RenjanaApplication.get().googleSignInVirtualizer
                            .assignAccountToInstance(instanceId, assignedAccountId)
                    }
                } catch (e: Throwable) {
                    RenjanaLog.w(TAG, "GMS account assignment failed: ${e.message}")
                }
            }

            if (instance.config.enableFingerprint) {
                com.fesu.renjana.hooks.DeviceFingerprint.applyDeviceSpoofToProcess(instance.config, instanceId)
            }

            bypassVectorDrawableCheck()

            guestApplication = createAndAttachGuestApplication(launchInfo.applicationClass)
            virtualContext?.guestApplication = guestApplication
            runGuestProviders(launchInfo.providers)
            guestApplication?.let { app ->
                try {
                    app.onCreate()
                    RenjanaLog.i(TAG, "Guest Application started: ${launchInfo.applicationClass}")
                } catch (e: Throwable) {
                    RenjanaLog.w(TAG, "Guest Application.onCreate failed (non-fatal): ${e.message}")
                }
            }

            val guestClass = virtualClassLoader!!.loadGuestClass(guestClassName)
            guestActivity = guestClass.getDeclaredConstructor().newInstance() as Activity

            cacheLifecycleMethods(guestClass)
            attachGuestToHost()

            guestActivity?.let { guest ->
                try {
                    com.fesu.renjana.hooks.ActivityStarterHook.cacheInstanceInfo(
                        instanceId, apkPath,
                        packageName.ifEmpty { launchInfo.packageName ?: "com.example.guest" }
                    )
                    com.fesu.renjana.hooks.ActivityStarterHook.installForActivity(this, guest, instanceId)
                } catch (e: Throwable) {
                    RenjanaLog.w(TAG, "ActivityStarterHook install failed (non-fatal): ${e.message}")
                }
            }

            val guestIntent = intent.getParcelableExtra<Intent>(EXTRA_GUEST_ORIGINAL_INTENT)
            if (guestIntent != null) setGuestIntent(guestIntent)

            reportStateToService(InstanceNotificationManager.ACTION_OPEN_INSTANCE)
            if (onCreateMethod != null) {
                onCreateMethod!!.invoke(guestActivity, savedInstanceState)
            } else {
                RenjanaLog.w(TAG, "Guest $guestClassName has no onCreate method")
            }
        } catch (e: Exception) {
            RenjanaLog.e(TAG, "Failed to launch guest $guestClassName: ${e.message}", e)
            finish()
        }
    }

    private fun readThemeResource(context: Context): Int = try {
        val field = android.view.ContextThemeWrapper::class.java.getDeclaredField("mThemeResource")
        field.isAccessible = true
        field.getInt(context)
    } catch (_: Throwable) {
        0
    }

    /**
     * Resolve a usable native library directory for the guest:
     * 1. the installed guest's extracted `nativeLibraryDir` — used when non-empty;
     * 2. otherwise extract the guest's ".so" files under "lib/<abi>/" from every
     *    guest APK (base + splits) into `<dataPath>/lib` and use that.
     *
     * Returns null when neither yields anything (guest has no native libs).
     */
    private fun resolveGuestNativeLibDir(apkPath: String, packageName: String, dataPath: String): String? {
        try {
            if (packageName.isNotBlank()) {
                val installed = packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir
                if (!installed.isNullOrBlank()) {
                    val dir = java.io.File(installed)
                    if (dir.isDirectory && dir.listFiles()?.isNotEmpty() == true) {
                        return installed
                    }
                }
            }
        } catch (_: Throwable) {}

        val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
        val libDir = java.io.File(dataPath, "lib").apply { mkdirs() }
        var sawAny = false
        for (apk in VirtualClassLoader.resolveAllApkPaths(apkPath)) {
            try {
                java.util.zip.ZipFile(apk).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        if (entry.isDirectory) continue
                        if (!entry.name.startsWith("lib/$abi/") || !entry.name.endsWith(".so")) continue
                        sawAny = true
                        val out = java.io.File(libDir, java.io.File(entry.name).name)
                        if (out.exists() && out.length() == entry.size) continue
                        zip.getInputStream(entry).use { input ->
                            java.io.FileOutputStream(out).use { output -> input.copyTo(output) }
                        }
                    }
                }
            } catch (_: Throwable) {}
        }
        RenjanaLog.i(TAG, if (sawAny) "Guest native lib dir: ${libDir.absolutePath}" else "No guest native libs found for abi=$abi")
        return if (sawAny) libDir.absolutePath else null
    }

    /** Construct and attach (but do not yet start) the guest's Application. */
    private fun createAndAttachGuestApplication(applicationClass: String?): Application? {
        val className = applicationClass?.takeIf { it.isNotBlank() } ?: return null
        return try {
            val clazz = virtualClassLoader!!.loadGuestClass(className)
            val app = clazz.getDeclaredConstructor().newInstance() as Application
            val attachMethod = Application::class.java.declaredMethods.firstOrNull {
                it.name == "attach" && it.parameterCount == 1 &&
                    Context::class.java.isAssignableFrom(it.parameterTypes[0])
            } ?: throw NoSuchMethodException("Application.attach(Context) not found")
            attachMethod.isAccessible = true
            attachMethod.invoke(app, virtualContext)
            RenjanaLog.i(TAG, "Guest Application attached: $className")
            app
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Guest Application could not start (continuing): ${e.message}", e)
            null
        }
    }

    /** Run the guest's manifest ContentProviders. */
    private fun runGuestProviders(providers: List<android.content.pm.ProviderInfo>) {
        val context = virtualContext ?: return
        for (providerInfo in providers) {
            try {
                val clazz = virtualClassLoader!!.loadGuestClass(providerInfo.name)
                val provider = clazz.getDeclaredConstructor().newInstance() as android.content.ContentProvider
                provider.attachInfo(context, providerInfo)
                RenjanaLog.d(TAG, "Guest provider started: ${providerInfo.name}")
            } catch (e: Throwable) {
                RenjanaLog.w(TAG, "Guest provider failed (non-fatal): ${providerInfo.name}: ${e.message}", e)
            }
        }
    }

    /** Set up guest context and attach window from host so layout and views render directly. */
    private fun attachGuestToHost() {
        val guest = guestActivity ?: return
        try {
            attachGuestToHost(guest, guestClassName)
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest attach failed (non-fatal): ${e.message}")
            finish()
        }
    }

    /** Attach the host StubActivity context, window, and framework state to the guest Activity. */
    private fun attachGuestToHost(guest: Activity, guestClassName: String) {
        try {
            val guestRes = virtualClassLoader!!.getResources(this)
            val resolvedInfo = guestActivityInfo ?: android.content.pm.ActivityInfo().apply {
                packageName = guestPackageName.ifEmpty { this@StubActivity.packageName }
                name = guestClassName
            }
            val guestTheme = guestRes.newTheme().apply {
                if (effectiveGuestTheme != 0) {
                    applyStyle(effectiveGuestTheme, true)
                } else {
                    applyStyle(com.fesu.renjana.R.style.Theme_Renjana, true)
                }
            }

            val attachMethod = Activity::class.java.declaredMethods.firstOrNull { it.name == "attach" }
            if (attachMethod != null) {
                attachMethod.isAccessible = true
                val args = arrayOfNulls<Any>(attachMethod.parameterCount)
                for (i in attachMethod.parameterTypes.indices) {
                    val type = attachMethod.parameterTypes[i]
                    args[i] = when {
                        type == Application::class.java || type.name.contains("Application") -> guestApplication ?: application
                        type.name.contains("ActivityThread") -> getActivityField("mMainThread")
                        type.name.contains("Instrumentation") -> getActivityField("mInstrumentation")
                        type == android.os.IBinder::class.java -> getActivityField("mToken")
                        type == Int::class.javaPrimitiveType -> 0
                        type == Long::class.javaPrimitiveType -> 0L
                        type == Boolean::class.javaPrimitiveType -> false
                        type == Intent::class.java -> intent
                        type == android.content.pm.ActivityInfo::class.java -> resolvedInfo
                        type == Activity::class.java -> null
                        type == CharSequence::class.java -> title
                        type == Configuration::class.java -> guestRes.configuration
                        type == android.view.Window::class.java -> null
                        Context::class.java.isAssignableFrom(type) -> virtualContext ?: this
                        else -> null
                    }
                }
                attachMethod.invoke(guest, *args)
            }

            val effectiveApp = guestApplication ?: application
            var currentClass: Class<*>? = guest.javaClass
            while (currentClass != null && currentClass != Any::class.java) {
                setField(currentClass, guest, "mBase", virtualContext ?: this)
                setField(currentClass, guest, "mActivityInfo", resolvedInfo)
                setField(currentClass, guest, "mApplication", effectiveApp)
                setField(currentClass, guest, "mIntent", intent)
                setField(currentClass, guest, "mWindowManager", windowManager)
                setField(currentClass, guest, "mWindow", window)
                setField(currentClass, guest, "mUiThread", Thread.currentThread())
                currentClass = currentClass.superclass
            }

            setField(Activity::class.java, guest, "mComponent", componentName)
            setField(android.view.ContextThemeWrapper::class.java, guest, "mResources", guestRes)
            setField(android.view.ContextThemeWrapper::class.java, guest, "mTheme", guestTheme)
            setField(android.view.ContextThemeWrapper::class.java, guest, "mThemeResource", effectiveGuestTheme)

            if (PineHookManager.isAvailable()) {
                installGuestApplicationAndActivityInfoHooks(guest, resolvedInfo, guestClassName, effectiveApp)
            }
            RenjanaLog.i(TAG, "Attached guest $guestClassName with guest resource table; host window remains host-themed")
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest context injection partial failure: ${e.message}", e)
        }
    }

    private fun getActivityField(name: String): Any? = try {
        val field = Activity::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.get(this)
    } catch (_: Throwable) {
        null
    }

    private fun setField(clazz: Class<*>, target: Any, name: String, value: Any?) {
        try {
            val field = clazz.getDeclaredField(name)
            field.isAccessible = true
            when (field.type) {
                Integer.TYPE -> field.setInt(target, value as? Int ?: 0)
                else -> field.set(target, value)
            }
        } catch (_: Throwable) {}
    }

    private fun installGuestApplicationAndActivityInfoHooks(
        guest: Activity,
        guestInfo: android.content.pm.ActivityInfo,
        targetGuestClass: String,
        effectiveApp: Application
    ) {
        try {
            val getAppMethod = Activity::class.java.getDeclaredMethod("getApplication")
            top.canyie.pine.Pine.hook(getAppMethod, object : top.canyie.pine.callback.MethodReplacement() {
                override fun replaceCall(callFrame: top.canyie.pine.Pine.CallFrame?): Any? = effectiveApp
            })
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to Pine hook getApplication: ${e.message}")
        }

        try {
            for (method in packageManager.javaClass.declaredMethods.filter { it.name == "getActivityInfo" }) {
                try {
                    top.canyie.pine.Pine.hook(method, object : top.canyie.pine.callback.MethodHook() {
                        override fun beforeCall(callFrame: top.canyie.pine.Pine.CallFrame?) {
                            val comp = callFrame?.args?.getOrNull(0) as? android.content.ComponentName
                            if (comp != null && (comp.className == targetGuestClass || comp.className.contains(targetGuestClass))) {
                                callFrame.result = guestInfo
                            }
                        }
                    })
                } catch (_: Throwable) {}
            }
        } catch (e: Throwable) {
            RenjanaLog.w(TAG, "Failed to Pine hook getActivityInfo: ${e.message}")
        }
    }

    /**
     * Prevent VectorDrawableCompat verification failure in androidx.appcompat.widget.ResourceManagerInternal.
     */
    private fun bypassVectorDrawableCheck() {
        try {
            androidx.appcompat.app.AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        } catch (_: Throwable) {}

        virtualClassLoader?.let { vcl ->
            try {
                val guestAppCompatDelegate = vcl.loadGuestClass("androidx.appcompat.app.AppCompatDelegate")
                val setMethod = guestAppCompatDelegate.getMethod("setCompatVectorFromResourcesEnabled", java.lang.Boolean.TYPE)
                setMethod.invoke(null, true)
            } catch (_: Throwable) {}
        }

        listOfNotNull(
            runCatching { Class.forName("androidx.appcompat.widget.ResourceManagerInternal") }.getOrNull(),
            runCatching { virtualClassLoader?.loadGuestClass("androidx.appcompat.widget.ResourceManagerInternal") }.getOrNull()
        ).forEach { rmiClass ->
            try {
                val rmiInstance = try {
                    val getMethod = rmiClass.getDeclaredMethod("get")
                    getMethod.isAccessible = true
                    getMethod.invoke(null)
                } catch (_: Throwable) {
                    rmiClass.declaredMethods.firstOrNull {
                        java.lang.reflect.Modifier.isStatic(it.modifiers) && it.returnType == rmiClass && it.parameterCount == 0
                    }?.let { method ->
                        method.isAccessible = true
                        method.invoke(null)
                    }
                }

                if (rmiInstance != null) {
                    rmiClass.declaredFields.filter { it.type == java.lang.Boolean.TYPE }.forEach { field ->
                        try {
                            field.isAccessible = true
                            field.setBoolean(rmiInstance, true)
                            RenjanaLog.i(TAG, "Bypassed vector setup field ${field.name} in $rmiClass")
                        } catch (_: Throwable) {}
                    }
                }

                if (PineHookManager.isAvailable()) {
                    rmiClass.declaredMethods.filter {
                        it.parameterCount == 1 &&
                            Context::class.java.isAssignableFrom(it.parameterTypes[0]) &&
                            (it.returnType == java.lang.Void.TYPE || it.returnType == Void.TYPE)
                    }.forEach { method ->
                        try {
                            top.canyie.pine.Pine.hook(method, object : top.canyie.pine.callback.MethodReplacement() {
                                override fun replaceCall(callFrame: top.canyie.pine.Pine.CallFrame?): Any? = null
                            })
                        } catch (e: Throwable) {
                            RenjanaLog.w(TAG, "Pine hook failed on ${method.name}: ${e.message}")
                        }
                    }
                }
            } catch (e: Throwable) {
                RenjanaLog.w(TAG, "Failed on rmiClass $rmiClass: ${e.message}")
            }
        }
    }

    /** Cache all lifecycle Method references, searching the complete superclass chain. */
    private fun cacheLifecycleMethods(guestClass: Class<*>) {
        onCreateMethod = safeMethod(guestClass, "onCreate", Bundle::class.java)
        onStartMethod = safeMethod(guestClass, "onStart")
        onResumeMethod = safeMethod(guestClass, "onResume")
        onPauseMethod = safeMethod(guestClass, "onPause")
        onStopMethod = safeMethod(guestClass, "onStop")
        onDestroyMethod = safeMethod(guestClass, "onDestroy")
        onRestartMethod = safeMethod(guestClass, "onRestart")
        onNewIntentMethod = safeMethod(guestClass, "onNewIntent", Intent::class.java)
        onActivityResultMethod = safeMethod(
            guestClass, "onActivityResult",
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, Intent::class.java
        )
        onSaveInstanceStateMethod = safeMethod(guestClass, "onSaveInstanceState", Bundle::class.java)
        onRestoreInstanceStateMethod = safeMethod(guestClass, "onRestoreInstanceState", Bundle::class.java)
        onBackPressedMethod = safeMethod(guestClass, "onBackPressed")
        onKeyDownMethod = safeMethod(guestClass, "onKeyDown", Int::class.javaPrimitiveType!!, KeyEvent::class.java)
        onKeyUpMethod = safeMethod(guestClass, "onKeyUp", Int::class.javaPrimitiveType!!, KeyEvent::class.java)
        onTouchEventMethod = safeMethod(guestClass, "onTouchEvent", MotionEvent::class.java)
        onCreateOptionsMenuMethod = safeMethod(guestClass, "onCreateOptionsMenu", Menu::class.java)
        onOptionsItemSelectedMethod = safeMethod(guestClass, "onOptionsItemSelected", MenuItem::class.java)
        onRequestPermissionsResultMethod = safeMethod(
            guestClass, "onRequestPermissionsResult",
            Int::class.javaPrimitiveType!!, Array<String>::class.java, IntArray::class.java
        )
        onConfigurationChangedMethod = safeMethod(guestClass, "onConfigurationChanged", Configuration::class.java)
        onWindowFocusChangedMethod = safeMethod(guestClass, "onWindowFocusChanged", Boolean::class.javaPrimitiveType!!)

        if (onCreateMethod == null) {
            try {
                val hierarchy = generateSequence<Class<*>>(guestClass) { it.superclass }
                    .take(4).joinToString(" → ") { it.name }
                val onCreateLikes = guestClass.declaredMethods
                    .filter { it.name.contains("onCreate") }
                    .joinToString(", ") { "${it.name}(${it.parameterTypes.joinToString { p -> p.simpleName }})" }
                RenjanaLog.w(TAG, "onCreate(Bundle) not found on guest. hierarchy=$hierarchy declared=$onCreateLikes")
            } catch (_: Throwable) {}
        }
    }

    /** Find [name] with [params] anywhere in the class hierarchy (most-derived first). */
    private fun safeMethod(clazz: Class<*>, name: String, vararg params: Class<*>): Method? {
        var current: Class<*>? = clazz
        while (current != null) {
            try {
                val method = current.getDeclaredMethod(name, *params)
                method.isAccessible = true
                return method
            } catch (_: NoSuchMethodException) {
                current = current.superclass
            }
        }
        return null
    }

    private fun setGuestIntent(guestIntent: Intent) {
        try {
            val intentField = Activity::class.java.getDeclaredField("mIntent")
            intentField.isAccessible = true
            intentField.set(guestActivity, guestIntent)
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Failed to set guest intent: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Lifecycle delegation
    // ──────────────────────────────────────────────

    override fun onStart() {
        super.onStart()
        try { onStartMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onStart failed: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        ActivityStubManager.onStubResumed(instanceId, getStubIndex())
        reportStateToService(InstanceNotificationManager.ACTION_OPEN_INSTANCE)
        try { onResumeMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onResume failed: ${e.message}")
        }
    }

    override fun onPause() {
        try { onPauseMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onPause failed: ${e.message}")
        }
        super.onPause()
    }

    override fun onStop() {
        try { onStopMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onStop failed: ${e.message}")
        }
        super.onStop()
    }

    override fun onRestart() {
        super.onRestart()
        try { onRestartMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onRestart failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        try { onDestroyMethod?.invoke(guestActivity) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onDestroy failed: ${e.message}")
        }
        ActivityStubManager.onStubReleased(instanceId, getStubIndex(), guestClassName)
        reportStateToService(InstanceNotificationManager.ACTION_STOP_INSTANCE)
        guestActivity = null
        virtualClassLoader = null
        super.onDestroy()

        if (isFinishing) {
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    RenjanaLog.i(TAG, "Cleanly terminating subprocess :p${getStubIndex()} (pid=${android.os.Process.myPid()})")
                    android.os.Process.killProcess(android.os.Process.myPid())
                } catch (_: Throwable) {}
            }, 300)
        }
    }

    private fun reportStateToService(action: String) {
        if (instanceId.isEmpty()) return
        try {
            val serviceIntent = Intent(this, InstanceLifecycleService::class.java).apply {
                this.action = action
                putExtra(InstanceNotificationManager.EXTRA_INSTANCE_ID, instanceId)
                if (guestPackageName.isNotBlank()) putExtra(EXTRA_PACKAGE_NAME, guestPackageName)
                putExtra(EXTRA_STUB_INDEX, getStubIndex())
            }
            startService(serviceIntent)
        } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == ACTION_FINISH_GUEST) {
            RenjanaLog.i(TAG, "StubActivity[${getStubIndex()}] FINISH_GUEST (onNewIntent) — finishing")
            finish()
            return
        }
        val guestIntent = intent.getParcelableExtra<Intent>(EXTRA_GUEST_ORIGINAL_INTENT) ?: intent
        try { onNewIntentMethod?.invoke(guestActivity, guestIntent) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onNewIntent failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Activity result forwarding
    // ──────────────────────────────────────────────

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        try {
            val originalRequestCode = ActivityStubManager.resolveRequestCode(
                instanceId, getStubIndex(), requestCode
            )
            onActivityResultMethod?.invoke(guestActivity, originalRequestCode, resultCode, data)
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onActivityResult failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // State save/restore
    // ──────────────────────────────────────────────

    override fun onSaveInstanceState(outState: Bundle) {
        try { onSaveInstanceStateMethod?.invoke(guestActivity, outState) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onSaveInstanceState failed: ${e.message}")
        }
        super.onSaveInstanceState(outState)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        try { onRestoreInstanceStateMethod?.invoke(guestActivity, savedInstanceState) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onRestoreInstanceState failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Input event delegation
    // ──────────────────────────────────────────────

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (onBackPressedMethod != null) {
            try {
                onBackPressedMethod!!.invoke(guestActivity)
            } catch (e: Exception) {
                RenjanaLog.w(TAG, "Guest onBackPressed failed: ${e.message}")
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return try {
            onKeyDownMethod?.invoke(guestActivity, keyCode, event) as? Boolean
                ?: super.onKeyDown(keyCode, event)
        } catch (_: Exception) {
            super.onKeyDown(keyCode, event)
        }
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        return try {
            onKeyUpMethod?.invoke(guestActivity, keyCode, event) as? Boolean
                ?: super.onKeyUp(keyCode, event)
        } catch (_: Exception) {
            super.onKeyUp(keyCode, event)
        }
    }

    override fun onTouchEvent(event: MotionEvent?): Boolean {
        return try {
            onTouchEventMethod?.invoke(guestActivity, event) as? Boolean
                ?: super.onTouchEvent(event)
        } catch (_: Exception) {
            super.onTouchEvent(event)
        }
    }

    // ──────────────────────────────────────────────
    // Menu delegation
    // ──────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        return try {
            onCreateOptionsMenuMethod?.invoke(guestActivity, menu) as? Boolean
                ?: super.onCreateOptionsMenu(menu)
        } catch (_: Exception) {
            super.onCreateOptionsMenu(menu)
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return try {
            onOptionsItemSelectedMethod?.invoke(guestActivity, item) as? Boolean
                ?: super.onOptionsItemSelected(item)
        } catch (_: Exception) {
            super.onOptionsItemSelected(item)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        try {
            onRequestPermissionsResultMethod?.invoke(guestActivity, requestCode, permissions, grantResults)
        } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onRequestPermissionsResult failed: ${e.message}")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        try { onConfigurationChangedMethod?.invoke(guestActivity, newConfig) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onConfigurationChanged failed: ${e.message}")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        try { onWindowFocusChangedMethod?.invoke(guestActivity, hasFocus) } catch (e: Exception) {
            RenjanaLog.w(TAG, "Guest onWindowFocusChanged failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Resource overrides (delegate to guest's ClassLoader)
    // ──────────────────────────────────────────────

    private var guestTheme: Resources.Theme? = null

    private val stubThemeResource: Int
        get() = hostThemeResource

    override fun getTheme(): Resources.Theme {
        // Keep framework/window theme tied to Renjana resources at all times.
        // Guest code receives a guest-theme ContextThemeWrapper via guestActivity.
        return hostTheme ?: super.getTheme()
    }

    override fun setTheme(resid: Int) {
        super.setTheme(resid)
        hostTheme = super.getTheme()
        hostThemeResource = resid
    }

    override fun getResources(): Resources = hostResources ?: super.getResources()

    override fun getAssets(): AssetManager = (hostResources ?: super.getResources()).assets

    override fun getClassLoader(): ClassLoader = virtualClassLoader ?: super.getClassLoader()

    override fun getLayoutInflater(): android.view.LayoutInflater =
        super.getLayoutInflater().cloneInContext(this)

    // ──────────────────────────────────────────────
    // startActivityForResult interception
    // ──────────────────────────────────────────────

    /**
     * When the guest calls startActivityForResult, the ActivityStarterHook redirects
     * through the stub system. The result comes back to this stub's onActivityResult,
     * which then forwards to the guest via [onActivityResult].
     *
     * This method is called by [ActivityStarterHook] to initiate a result-bearing launch.
     */
    fun startGuestActivityForResult(guestIntent: Intent, stubIntent: Intent, requestCode: Int) {
        ActivityStubManager.registerRequestCode(instanceId, getStubIndex(), requestCode, guestIntent)
        startActivityForResult(stubIntent, requestCode)
    }
}

// Concrete stub Activity classes continue below, unchanged.
