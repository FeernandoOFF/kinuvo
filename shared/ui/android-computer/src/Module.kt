package kinuvo.ui

import kinuvo.app.computer.ComputerDriver
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidComputerModule = module {
    single<ComputerDriver> { AndroidComputer(androidContext()) }
}
