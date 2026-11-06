package com.changliang.screenon

import android.app.Application

class ChangLiangApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
