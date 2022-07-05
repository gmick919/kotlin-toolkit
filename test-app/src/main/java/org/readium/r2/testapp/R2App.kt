/*
 * Module: r2-testapp-kotlin
 * Developers: Aferdita Muriqi, Clément Baumann
 *
 * Copyright (c) 2018. European Digital Reading Lab. All rights reserved.
 * Licensed to the Readium Foundation under one or more contributor license agreements.
 * Use of this source code is governed by a BSD-style license which is detailed in the
 * LICENSE file present in the project repository where this source code is maintained.
 */

package org.readium.r2.testapp

import android.annotation.SuppressLint
import android.app.Application
import android.content.ContentResolver
import android.content.Context
import kotlinx.coroutines.*
import org.readium.r2.lingVisSdk.LingVisSDK
import org.readium.r2.shared.Injectable
import org.readium.r2.streamer.server.Server
import org.readium.r2.testapp.BuildConfig.DEBUG
import timber.log.Timber
import java.io.IOException
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.util.*

class R2App : Application() {

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Timber.plant(Timber.DebugTree())
        val s = ServerSocket(if (DEBUG) 8080 else 0)
        s.close()
        server = Server(s.localPort, applicationContext)
        startServer()
        R2DIRECTORY = r2Directory

        // LingVisSDK...
        // These two client ids are used only for demo, testing and debug.
        // Don't use them in production. Use the client ids assigned to your applications by Språkkraft.
        val testClientId = "367ebb09-57e9-4129-bae6-083d2d7b838e" // not secure: clientKey and onExpired arguments are ignored
        val testClientIdSecure = "40c76cda-bd9b-4b6c-aafd-137b187bedf4" // secure: clientKey is used to authorize the user, onExpired is used to automatically re-authorize the user after the previous authorization expires (after about 24 hours)
        LingVisSDK.prepare(testClientId, "1.4.x", "R2TestApp-Android", "r2 sample", server)
        //...LingVisSDK
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        lateinit var server: Server
            private set

        lateinit var R2DIRECTORY: String
            private set

        var isServerStarted = false
            private set
    }

    override fun onTerminate() {
        super.onTerminate()
        stopServer()
    }

    private fun startServer() {
        if (!server.isAlive) {
            try {
                server.start()
            } catch (e: IOException) {
                // do nothing
                if (DEBUG) Timber.e(e)
            }
            if (server.isAlive) {
//                // Add your own resources here
//                server.loadCustomResource(assets.open("scripts/test.js"), "test.js")
//                server.loadCustomResource(assets.open("styles/test.css"), "test.css")
//                server.loadCustomFont(assets.open("fonts/test.otf"), applicationContext, "test.otf")

                isServerStarted = true
            }
        }
    }

    private fun stopServer() {
        if (server.isAlive) {
            server.stop()
            isServerStarted = false
        }
    }

    private val r2Directory: String
        get() {
            val properties = Properties()
            val inputStream = applicationContext.assets.open("configs/config.properties")
            properties.load(inputStream)
            val useExternalFileDir =
                properties.getProperty("useExternalFileDir", "false")!!.toBoolean()
            return if (useExternalFileDir) {
                applicationContext.getExternalFilesDir(null)?.path + "/"
            } else {
                applicationContext.filesDir?.path + "/"
            }
        }
}

val Context.resolver: ContentResolver
    get() = applicationContext.contentResolver
