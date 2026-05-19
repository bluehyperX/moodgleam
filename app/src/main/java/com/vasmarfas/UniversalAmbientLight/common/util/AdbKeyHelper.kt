package com.vasmarfas.UniversalAmbientLight.common.util

import android.content.Context
import dadb.AdbKeyPair
import java.io.File

object AdbKeyHelper {

    // Synchronized to prevent two encoders racing on first-time RSA-2048 generation
    // (which would corrupt the key files and break ADB auth until app restart).
    @Synchronized
    fun getKeyPair(context: Context): AdbKeyPair {
        val privateKeyFile = File(context.filesDir, "adbkey")
        val publicKeyFile = File(context.filesDir, "adbkey.pub")

        if (!privateKeyFile.exists() || !publicKeyFile.exists()) {
            AdbKeyPair.generate(privateKeyFile, publicKeyFile)
        }

        return AdbKeyPair.read(privateKeyFile, publicKeyFile)
    }
}
