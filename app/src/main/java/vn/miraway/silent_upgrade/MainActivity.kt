package vn.miraway.silent_upgrade

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Bundle
import android.os.CountDownTimer
import android.os.PowerManager
import android.util.Log
import android.util.Xml
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import vn.miraway.silent_upgrade.ui.theme.AutoUpdateTheme
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val packageInfo = packageManager.getPackageInfo(packageName, 0)

        setContent {
            AutoUpdateTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column {
                        Greeting(
                            name = "Miraway - app version: ${packageInfo.versionName}",
                            modifier = Modifier.padding(innerPadding)
                        )

                        Button(onClick = { makeOwnerDevice() }) {
                            Text(text = "Make device owner")
                        }
                        
                        Button(onClick = { silentUpdate(this@MainActivity) }) {
                            Text(text = "Update")
                        }
                    }
                }
            }
        }
    }

    private fun lock() {
        (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).let { dpm ->
            ComponentName(this, AdminReceiver::class.java).let { ar ->
                if (dpm.isAdminActive(ar)) {
                    try {
                        dpm.setLockTaskPackages(ar, arrayOf(packageName))
                        startLockTask()
                    } catch (ex: Exception) {
                        Log.e("main","lock task error: $ex")
                    }
                }
            }
        }
    }

    private fun unLock() {
        stopLockTask()
    }

    private fun silentUpdate(context: Context){
        context.assets.open("app-release.apk").use { inputStream ->
            //silent install
            // PackageManager provides an instance of PackageInstaller
            val packageInstaller = context.packageManager.packageInstaller

            // Prepare params for installing one APK file with MODE_FULL_INSTALL
            // We could use MODE_INHERIT_EXISTING to install multiple split APKs
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(context.packageName)

            // Get a PackageInstaller.Session for performing the actual update
            val sessionId = packageInstaller.createSession(params)
            val session = packageInstaller.openSession(sessionId)

            // Copy APK file bytes into OutputStream provided by install Session
            val out = session.openWrite(context.packageName, 0, -1)

            inputStream.copyTo(out)
            session.fsync(out)
            out.close()

            // The app gets killed after installation session commit
            session.commit(PendingIntent.getBroadcast(context, sessionId, Intent("android.intent.action.MAIN"),
                PendingIntent.FLAG_IMMUTABLE).intentSender)
        }
    }

    private fun makeOwnerDevice() {
        (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).let { dpm ->
            ComponentName(this, AdminReceiver::class.java).let { ar ->
                if (!dpm.isAdminActive(ar)) {
                    try {
                        createDataSystemDevicePolicesXML(packageName)
                        AlertDialog.Builder(this).apply {
                            setTitle(R.string.dialog_confirm_restart)
                            setMessage(R.string.dialog_request_restart_message)
                            setNegativeButton(R.string.dialog_confirm_no) { _, _ -> }
                            setPositiveButton(R.string.dialog_confirm_yes) { _, _ -> }
                            create()
                            show().apply {
                                val countDownTimer = object : CountDownTimer(30000, 1000) {
                                    override fun onFinish() {
                                        reboot()
                                    }

                                    override fun onTick(millisUntilFinished: Long) {
                                        setMessage(
                                            "${getString(R.string.dialog_request_restart_message)}:  ${
                                                TimeUnit.MILLISECONDS.toSeconds(
                                                millisUntilFinished
                                            )}"
                                        )
                                    }
                                }.start()
                                getButton(DialogInterface.BUTTON_NEGATIVE).apply {
                                    setOnClickListener {
                                        countDownTimer.cancel()
                                        dismiss()
                                    }
                                }
                                getButton(DialogInterface.BUTTON_POSITIVE).apply {
                                    requestFocus()
                                    setOnClickListener {
                                        countDownTimer.cancel()
                                        reboot()
                                    }
                                }
                            }
                        }
                    } catch (ex: Exception) {
                        Log.e("owner device","owner device via file with privileged processes error: $ex")
                    }
                }
            }
        }
    }

    private fun reboot(){
        Runtime.getRuntime().exec(arrayOf("/system/bin/reboot", "-p")).waitFor()
    }


    private fun createDataSystemDevicePolicesXML(packageName: String){
        //create xml content
        Xml.newSerializer().let { xmlSerializer ->
            xmlSerializer.setOutput(FileOutputStream("/data/system/device_policies.xml"), "utf-8")
            xmlSerializer.startDocument(null, true)
            xmlSerializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
            xmlSerializer.startTag("", "policies")
            xmlSerializer.attribute("", "setup-complete", "true")
            xmlSerializer.startTag("", "admin")
            xmlSerializer.attribute("", "name", "$packageName/vn.miraway.silent_upgrade.AdminReceiver")
            xmlSerializer.startTag("", "policies")
            xmlSerializer.attribute("", "flags", "0")
            xmlSerializer.endTag("", "policies")
            xmlSerializer.startTag("", "strong-auth-unlock-timeout")
            xmlSerializer.attribute("", "value", "0")
            xmlSerializer.endTag("", "strong-auth-unlock-timeout")
            xmlSerializer.startTag("", "user-restrictions")
            xmlSerializer.attribute("", "no_add_managed_profile", "true")
            xmlSerializer.endTag("", "user-restrictions")
            xmlSerializer.endTag("", "admin")
            xmlSerializer.endTag("", "policies")
            xmlSerializer.endDocument()
            xmlSerializer.flush()
        }

        Xml.newSerializer().let { xmlSerializer ->
            xmlSerializer.setOutput(FileOutputStream("/data/system/device_owner_2.xml"), "utf-8")
            xmlSerializer.startDocument(null, true)
            xmlSerializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true)
            xmlSerializer.startTag("", "root")
            xmlSerializer.startTag("", "device-owner")
            xmlSerializer.attribute("", "package", packageName)
            xmlSerializer.attribute("", "name", "")
            xmlSerializer.attribute("", "component", "$packageName/vn.miraway.silent_upgrade.AdminReceiver")
            xmlSerializer.attribute("", "userRestrictionsMigrated", "true")
            xmlSerializer.endTag("", "device-owner")
            xmlSerializer.startTag("", "device-owner-context")
            xmlSerializer.attribute("", "userId", "0")
            xmlSerializer.endTag("", "device-owner-context")
            xmlSerializer.endTag("", "root")
            xmlSerializer.endDocument()
            xmlSerializer.flush()
        }
    }
}


class AdminReceiver: DeviceAdminReceiver() {
    private val tag = "device admin"
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(tag,"enable")
    }
}

class PackageReplacedReceiver : BroadcastReceiver() {
    private val tag = "package replace"
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(context: Context, intent: Intent) {
        // Restart your app here
        Log.i(tag, "onReceive")
        if (!isAppRunning(context, context.packageName)){
            context.startActivity(Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        }
    }

    private fun isAppRunning(
        context: Context,
        packageName: String
    ): Boolean {
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).let { am ->
            for (processInfo in am.runningAppProcesses) {
                if (processInfo.processName == packageName) {
                    if (processInfo.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND){
                        return true
                    }
                }
            }
        }
        return false
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {
    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    AutoUpdateTheme {
        Greeting("Android")
    }
}