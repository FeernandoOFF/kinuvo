package kinuvo.ui

import kinuvo.app.appModule
import kinuvo.app.surface.UiCatalogs
import kinuvo.ui.surface.kinuvoCatalog
import org.koin.dsl.module
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.koinApplication
import org.koin.core.module.Module

/** Storage and Device details for the current platform. */
internal expect val platformModule: Module

private val uiModule = module { single { UiCatalogs(listOf(kinuvoCatalog)) } }

/** Starts DI once per process; [modules] add or override bindings, e.g. an [EmbeddedServer]. */
fun startKinuvo(vararg modules: Module, config: KoinApplication.() -> Unit = {}): KoinApplication = startKoin {
    config()
    modules(appModule, platformModule, uiModule, *modules)
}

/** An isolated [KoinApplication] with the app's bindings, for tests; [modules] add or override bindings. */
internal fun kinuvoApplication(vararg modules: Module): KoinApplication = koinApplication {
    modules(appModule, platformModule, uiModule, *modules)
}
