package com.houvven.guise.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.annotation.StringRes
import com.houvven.guise.BuildConfig
import com.houvven.guise.R
import com.houvven.guise.hook.profile.HookProfiles
import com.houvven.guise.util.MY_USER_ID
import com.houvven.guise.util.createModuleApplications
import com.houvven.guise.util.putModuleScope
import com.houvven.guise.util.removeModuleScope
import com.topjohnwu.superuser.ipc.RootService
import io.github.houvven.lservice.ILServiceBridge
import io.github.houvven.lservice.LServiceBridgeRootService
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.lsposed.lspd.ILSPManagerService
import org.lsposed.lspd.service.ILSPApplicationService

/**
 * This object is a client for the LService.
 * It provides methods to start the service, handle its connection, and interact with the service.
 */
object LServiceBridgeClient {

    private var lserviceBridge: ILServiceBridge? = null

    private var applicationService: ILSPApplicationService? = null

    private var managerService: ILSPManagerService? = null

    private val _statusFlow: MutableStateFlow<Status> = MutableStateFlow(Status.Connecting)
    val statusFlow = _statusFlow.asStateFlow()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "onServiceConnected: $name")
            lserviceBridge = ILServiceBridge.Stub.asInterface(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "onServiceDisconnected: $name")
            _statusFlow.update { Status.Disconnected }
        }

        override fun onBindingDied(name: ComponentName?) {
            super.onBindingDied(name)
            Log.i(TAG, "onBindingDied: $name")
            _statusFlow.update { Status.BindingDied }
        }
    }


    /**
     * Starts the LService and binds to it.
     * @param context The context used to start the service.
     */

    @OptIn(DelicateCoroutinesApi::class)
    fun start(context: Context) {
        val intent = Intent(context, LServiceBridgeRootService::class.java)
        intent.putExtra("apkPath", context.managerApkPath)
        try {
            Runtime.getRuntime().exec("su")
        } catch (e: Exception) {
            _statusFlow.update { Status.Error.RootRequired }
            return
        }
        RootService.bind(intent, connection)

        GlobalScope.launch { // watch the connection status
            val startTime = System.currentTimeMillis()
            while (System.currentTimeMillis() - startTime < 5000) {
                if (lserviceBridge != null) {
                    Log.i(TAG, "watch: LService bridge connected")
                    _statusFlow.update { Status.Connected }
                    break
                }
                delay(200)
            }
            setManagerService()
        }
    }

    fun setScope(profiles: HookProfiles) = profiles.run {
        if (isAvailable) addScope(packageName!!) else removeScope(packageName!!)
    }

    fun removeScope(vararg packageName: String): Boolean {
        return managerService?.removeModuleScope(BuildConfig.APPLICATION_ID) {
            createModuleApplications(MY_USER_ID, *packageName).toSet()
        } ?: false
    }

    fun addScope(vararg packageName: String): Boolean {
        return managerService?.putModuleScope(BuildConfig.APPLICATION_ID) {
            createModuleApplications(MY_USER_ID, *packageName).toSet()
        } ?: false
    }

    private suspend fun setManagerService() {
        // max 5 seconds to wait for the manager service
        val startTime = System.currentTimeMillis()
        var binder = lserviceBridge?.applicationServiceBinder
        while (binder?.isBinderAlive != true) {
            if (System.currentTimeMillis() - startTime > 5000) {
                Log.e(TAG, "Failed to get application service binder")
                return
            }
            delay(200)
        }
        applicationService = ILSPApplicationService.Stub.asInterface(binder)

        binder = lserviceBridge!!.getManagerServiceBinder(applicationService)
        val check = lserviceBridge == null || applicationService == null
        while (check || binder?.isBinderAlive != true) {
            if (System.currentTimeMillis() - startTime > 5000) {
                Log.e(TAG, "Failed to get application service binder")
                return
            }
            delay(200)
        }
        managerService = ILSPManagerService.Stub.asInterface(binder)
        with(managerService!!) {
            Log.d(TAG, "xposed api: $api")
            Log.d(TAG, "xposed version: $xposedVersionName($xposedVersionCode)")
        }
    }


    /**
     * Gets the path of the manager APK.
     * If the APK does not exist, it is created from the assets.
     */
    private val Context.managerApkPath: String
        get() {
            val relativePath = "lservice/manager.apk"
            val managerFile = cacheDir.resolve(relativePath)
            if (!managerFile.exists()) {
                managerFile.parentFile?.mkdirs()
                assets.open(relativePath).use { input ->
                    managerFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return managerFile.absolutePath
        }

    // The tag used for logging
    private const val TAG = "LService"


    /** A sealed interface representing the various states of the LService connection. */
    sealed interface Status {
        /** Represents the state when the LService is successfully connected. */
        data object Connected : Status

        /** Represents the state when the LService is disconnected. */
        data object Disconnected : Status

        /** Represents the state when the LService is in the process of connecting. */
        data object Connecting : Status

        /** Represents the state when the binding to the LService has died. */
        data object BindingDied : Status

        /** A sealed interface representing the various error states of the LService connection. */
        sealed class Error(@StringRes val messageResId: Int) : Status {
            /** Represents the error state when root access is required. */
            data object RootRequired : Error(R.string.required_permissions_root)
        }
    }
}