package com.example.looma.data.assistant.firebase

import android.content.Context
import com.google.firebase.Firebase
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.appCheck
import com.google.firebase.initialize

internal object FirebaseBootstrap {
    fun initialize(context: Context): Boolean {
        val appContext = context.applicationContext
        val app = FirebaseApp.getApps(appContext).firstOrNull() ?: Firebase.initialize(appContext)
        if (app == null) return false
        Firebase.appCheck.installAppCheckProviderFactory(createAppCheckProviderFactory())
        return true
    }
}
