package com.mbientlab.metawear.app

import android.app.Application

/** Process entry point owning the app-wide [AppContainer]. */
class MetaWearApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
