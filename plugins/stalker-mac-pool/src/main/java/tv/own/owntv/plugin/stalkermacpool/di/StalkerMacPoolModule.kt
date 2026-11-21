package tv.own.owntv.plugin.stalkermacpool.di

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import tv.own.owntv.plugin.stalkermacpool.data.StalkerMacPoolStore
import tv.own.owntv.plugin.stalkermacpool.network.StalkerSpeedTester
import tv.own.owntv.plugin.stalkermacpool.playback.StalkerMacSwitcher
import tv.own.owntv.plugin.stalkermacpool.playback.StalkerPlaybackSessionHolder

val stalkerMacPoolModule = module {
    single { StalkerMacPoolStore(androidContext()) }
    single { StalkerSpeedTester(get(), get()) }
    single { StalkerPlaybackSessionHolder() }
    single {
        StalkerMacSwitcher(
            appContext = androidContext(),
            store = get(),
            sourceDao = get(),
            channelDao = get(),
            profileDao = get(),
            sourceRepository = get(),
            settingsRepository = get(),
            stalkerAuth = get(),
            baseOkHttpClient = get(),
            hostCallbacks = get(),
        )
    }
}
