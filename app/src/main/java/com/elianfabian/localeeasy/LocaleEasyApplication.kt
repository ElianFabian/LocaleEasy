package com.elianfabian.localeeasy

import android.app.Application
import com.elianfabian.locale_easy.LocaleEasy

class LocaleEasyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleEasy.install(this)
    }
}
