package com.example.appcore

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.telephony.SmsMessage
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class CoreHook : IXposedHookLoadPackage {

    companion object {
        private const val TARGET_PKG = "com.android.phone"
        private const val HOOK_CLASS = "com.android.internal.telephony.InboundSmsHandler"
        private const val TAG = "AppCore"
        
        private val VALID_ACTIONS = setOf(
            "android.provider.Telephony.SMS_DELIVER",
            "android.provider.Telephony.SMS_RECEIVED",
            "android.provider.Telephony.WAP_PUSH_DELIVER"
        )
        private val WAP_CLEAN_REGEX = Regex("[^\\x20-\\x7E\\u4e00-\\u9fa5\\u3000-\\u303F\\uFF00-\\uFFEF\\n\\r]")

        private val pendingCallbacks = ConcurrentHashMap<Long, IAnalysisCb>()

        private fun logD(msg: String) { if (BuildConfig.DEBUG) Log.d(TAG, msg) }
        private fun logE(msg: String, t: Throwable? = null) { Log.e(TAG, msg, t) }
    }

    @Volatile private var analysisSvc: IAnalysisSvc? = null
    private val bindLatchRef = AtomicReference<CountDownLatch?>(null)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            analysisSvc = IAnalysisSvc.Stub.asInterface(service)
            bindLatchRef.getAndSet(null)?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            analysisSvc = null
        }
    }

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != TARGET_PKG) return

        runCatching {
            val targetClass = XposedHelpers.findClassIfExists(HOOK_CLASS, lpparam.classLoader) ?: return
            XposedBridge.hookAllMethods(targetClass, "dispatchIntent", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    runCatching {
                        val intentIndex = param.args.indexOfFirst { it is Intent }
                        if (intentIndex == -1) return
                        val intent = param.args[intentIndex] as Intent

                        if (intent.action !in VALID_ACTIONS) return

                        if (intent.getBooleanExtra("ai_checked", false)) return

                        val ctx = XposedHelpers.getObjectField(param.thisObject, "mContext") as? Context ?: return

                        val rawText = extractText(intent)
                        if (rawText.isBlank()) return

                        val svc = getOrBindService(ctx)
                        
                        if (svc != null) {
                            val method = param.method
                            val thisObject = param.thisObject
                            val argsClone = param.args.clone()
                            
                            val intentClone = intent.clone() as Intent
                            intentClone.putExtra("ai_checked", true)
                            argsClone[intentIndex] = intentClone

                            intent.action = "com.void.TELEPHONY_SPAM_DROP"
                            intent.component = null
                            intent.setPackage(null)
                            
                            Log.w(TAG, "Message intercepted. Initiating async AI check.")

                            val callbackId = System.nanoTime()
                            val callback = object : IAnalysisCb.Stub() {
                                override fun onResult(isSpam: Boolean) {
                                    pendingCallbacks.remove(callbackId)
                                    if (isSpam) {
                                        Log.w(TAG, "AI Spam Detected Async. Intent successfully dropped.")
                                    } else {
                                        Log.i(TAG, "AI Clean Async. Reinjecting original intent to StateMachine.")
                                        runCatching {
                                            XposedBridge.invokeOriginalMethod(method, thisObject, argsClone)
                                        }.onFailure { logE("Failed to reinject original intent", it) }
                                    }
                                }
                            }
                            pendingCallbacks[callbackId] = callback

                            runCatching {
                                svc.processDataAsync(rawText, callback)
                            }.onFailure {
                                pendingCallbacks.remove(callbackId)
                                logE("Failed to send IPC async request. Safely reinjecting intent bypass.", it)
                                runCatching { XposedBridge.invokeOriginalMethod(method, thisObject, argsClone) }
                            }
                        }
                    }.onFailure { logE("Error inside beforeHookedMethod", it) }
                }
            })
        }.onFailure { logE("handleLoadPackage initialization crashed", it) }
    }

    private fun getOrBindService(ctx: Context): IAnalysisSvc? {
        val currentSvc = analysisSvc
        if (currentSvc != null && currentSvc.asBinder().isBinderAlive) {
            return currentSvc
        } else {
            analysisSvc = null
        }

        var latch = CountDownLatch(1)
        if (bindLatchRef.compareAndSet(null, latch)) {
            runCatching {
                val intent = Intent().setComponent(ComponentName("com.example.appcore", "com.example.appcore.AnalysisSvc"))
                val flags = Context.BIND_AUTO_CREATE or 64 
                if (!ctx.bindService(intent, serviceConnection, flags)) {
                    bindLatchRef.compareAndSet(latch, null)
                    latch.countDown()
                }
            }.onFailure {
                bindLatchRef.compareAndSet(latch, null)
                latch.countDown()
            }
        } else {
            latch = bindLatchRef.get() ?: return analysisSvc
        }
        
        runCatching { 
            if (!latch.await(5, TimeUnit.SECONDS)) {
                bindLatchRef.compareAndSet(latch, null)
            }
        }
        return analysisSvc
    }

    private fun extractText(intent: Intent): String = runCatching {
        if (intent.action == "android.provider.Telephony.WAP_PUSH_DELIVER") {
            val data = intent.getByteArrayExtra("data") ?: return ""
            return String(data, Charsets.UTF_8).replace(WAP_CLEAN_REGEX, "")
        }

        val pdus = intent.extras?.get("pdus") as? Array<*> ?: return ""
        val format = intent.getStringExtra("format")

        pdus.mapNotNull { it as? ByteArray }.joinToString("") { pdu ->
            val msg = format?.let { SmsMessage.createFromPdu(pdu, it) } ?: @Suppress("DEPRECATION") SmsMessage.createFromPdu(pdu)
            msg.displayMessageBody ?: ""
        }
    }.onFailure { logE("Text extraction failed", it) }.getOrDefault("")
}
