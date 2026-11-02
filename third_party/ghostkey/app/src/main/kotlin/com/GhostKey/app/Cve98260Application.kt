package com.GhostKey.app

import android.app.Application
import rikka.shizuku.Shizuku

class Cve98260Application : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener { }

    private val binderDeadListener = Shizuku.OnBinderDeadListener { }

    companion object {
        lateinit var instance: Cve98260Application
            private set
    }
}
