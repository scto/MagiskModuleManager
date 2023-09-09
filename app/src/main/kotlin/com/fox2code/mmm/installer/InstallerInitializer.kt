/*
 * Copyright (c) 2023 to present Androidacy and contributors. Names, logos, icons, and the Androidacy name are all trademarks of Androidacy and may not be used without license. See LICENSE for more information.
 */

package com.fox2code.mmm.installer

import com.fox2code.mmm.BuildConfig
import com.fox2code.mmm.Constants
import com.fox2code.mmm.MainApplication
import com.fox2code.mmm.NotificationType
import com.fox2code.mmm.utils.io.Files.Companion.existsSU
import com.topjohnwu.superuser.NoShellException
import com.topjohnwu.superuser.Shell
import timber.log.Timber
import java.io.File

@Suppress("unused")
class InstallerInitializer {
    interface Callback {
        fun onPathReceived(path: String?)
        fun onFailure(error: Int)
    }

    companion object {
        var isKsu: Boolean = false
        private val MAGISK_SBIN = File("/sbin/magisk")
        private val MAGISK_SYSTEM = File("/system/bin/magisk")
        private val MAGISK_SYSTEM_EX = File("/system/xbin/magisk")
        private val HAS_MAGISK =
            MAGISK_SBIN.exists() || MAGISK_SYSTEM.exists() || MAGISK_SYSTEM_EX.exists()
        private var mgskPth: String? = null
        private var mgskVerCode = 0
        private var hsRmdsk = false
        const val ERROR_NO_PATH = 1
        const val ERROR_NO_SU = 2
        const val ERROR_OTHER = 3
        private var tries = 0

        val errorNotification: NotificationType?
            get() {
                val hasRoot = Shell.isAppGrantedRoot()
                if (mgskPth != null && hasRoot !== java.lang.Boolean.FALSE) {
                    return null
                }
                if (!HAS_MAGISK) {
                    return NotificationType.NO_MAGISK
                } else if (hasRoot !== java.lang.Boolean.TRUE) {
                    return NotificationType.ROOT_DENIED
                }
                return NotificationType.NO_ROOT
            }

        fun peekMagiskPath(): String? {
            return mgskPth
        }

        /**
         * Note: All mirrors are read only on latest magisk
         */
        fun peekMirrorPath(): String? {
            return if (mgskPth == null) null else "$mgskPth/.magisk/mirror"
        }

        /**
         * Note: Used to detect which modules are currently loaded.
         *
         * For read/write only "/data/adb/modules" should be used
         */
        fun peekModulesPath(): String? {
            return if (mgskPth == null) null else "$mgskPth/.magisk/modules"
        }

        fun peekMagiskVersion(): Int {
            return mgskVerCode
        }

        fun peekHasRamdisk(): Boolean {
            return hsRmdsk
        }

        fun tryGetMagiskPathAsync(callback: Callback, forceCheck: Boolean = false) {
            val mgskPth = mgskPth
            val thread: Thread = object : Thread("Magisk GetPath Thread") {
                override fun run() {
                    if (mgskPth != null && !forceCheck) {
                        callback.onPathReceived(mgskPth)
                        return
                    }
                    var error: Int
                    @Suppress("NAME_SHADOWING") var mgskPth: String? = null
                    try {
                        mgskPth = tryGetMagiskPath(forceCheck)
                        error = ERROR_NO_PATH
                    } catch (e: NoShellException) {
                        error = ERROR_NO_SU
                        Timber.w(e)
                    } catch (e: Exception) {
                        error = ERROR_OTHER
                        Timber.e(e)
                    }
                    if (forceCheck) {
                        Companion.mgskPth = mgskPth
                        if (mgskPth == null) {
                            mgskVerCode = 0
                        }
                    }
                    if (mgskPth != null) {
                        MainApplication.setHasGottenRootAccess(true)
                        callback.onPathReceived(mgskPth)
                    } else {
                        MainApplication.setHasGottenRootAccess(false)
                        callback.onFailure(error)
                    }
                }
            }
            thread.start()
        }

        private fun tryGetMagiskPath(forceCheck: Boolean): String? {
            var mgskPth = mgskPth
            val mgskVerCode: Int
            var hsRmdsk = hsRmdsk
            if (mgskPth != null && !forceCheck) return mgskPth
            val output = ArrayList<String>()
            if (Shell.isAppGrantedRoot() == null || !Shell.isAppGrantedRoot()!!) {
                // if Shell.isAppGrantedRoot() == null loop until it's not null
                return if (Shell.isAppGrantedRoot() == null) {
                    Thread.sleep(100)
                    tryGetMagiskPath(forceCheck)
                } else {
                    null
                }
            }
            try {
                if (!Shell.cmd(
                        "if grep ' / ' /proc/mounts | grep -q '/dev/root' &> /dev/null; " + "then echo true; else echo false; fi",
                        "su -V",
                    ).to(output).exec().isSuccess
                ) {
                    if (BuildConfig.DEBUG) {
                        Timber.i("Failed to search for ramdisk")
                    }
                    if (output.size != 0) {
                        hsRmdsk = "false" == output[0] || "true".equals(
                            System.getProperty("ro.build.ab_update"), ignoreCase = true
                        )
                    }
                    Companion.hsRmdsk = hsRmdsk
                    return null
                }
                if (BuildConfig.DEBUG) {
                    Timber.i("Found ramdisk: %s", output[0])
                    Timber.i("Searching for Magisk path. Current path: %s", mgskPth)
                }
                // reset output
                output.clear()
                // try to use magisk --path. if that fails, check for /data/adb/ksu for kernelsu support
                if (Shell.cmd("magisk --path", "su -V").to(output).exec().isSuccess && output[0].isNotEmpty() && !output[0].contains(
                        "not found"
                    )) {
                    mgskPth = output[0]
                    if (BuildConfig.DEBUG) {
                        Timber.i("Magisk path 1: %s", mgskPth)
                    }
                } else if (Shell.cmd("if [ -f /data/adb/ksu ]; then echo true; else echo false; fi").to(
                        output
                    ).exec().isSuccess && "true" == output[0]
                ) {
                    mgskPth = "/data/adb"
                    isKsu = true
                }
                Timber.i("Magisk runtime path: %s", mgskPth)
                mgskVerCode = output[1].toInt()
                Timber.i("Magisk version code: %s", mgskVerCode)
                if (mgskPth != null) {
                    if (mgskVerCode >= Constants.MAGISK_VER_CODE_FLAT_MODULES && mgskVerCode < Constants.MAGISK_VER_CODE_PATH_SUPPORT && (mgskPth.isEmpty() || !File(
                            mgskPth
                        ).exists())
                    ) {
                        mgskPth = "/sbin"
                    }
                }
                if (mgskPth != null) {
                    if (mgskPth.isNotEmpty() && existsSU(File(mgskPth))) {
                        Companion.mgskPth = mgskPth
                    } else {
                        Timber.e("Failed to get Magisk path (Got $mgskPth)")
                        mgskPth = null
                    }
                } else {
                    Timber.e("Failed to get Magisk path (Got null or other)")
                }
                Companion.mgskVerCode = mgskVerCode
                return mgskPth
            } catch (ignored: Exception) {
                // work around edge case
                return if (tries <= 10) {
                    tries++
                    try {
                        Thread.sleep(tries * 50L)
                    } catch (e: InterruptedException) {
                        Timber.e(e)
                    }
                    tryGetMagiskPath(true)
                } else {
                    null
                }
            }
        }
    }
}
