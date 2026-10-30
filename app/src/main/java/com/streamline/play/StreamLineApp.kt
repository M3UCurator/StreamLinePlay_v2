package com.streamline.play

import android.app.Application

class StreamLineApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

val Application.streamLineContainer: AppContainer
    get() = (this as StreamLineApp).container
