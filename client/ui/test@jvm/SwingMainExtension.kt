package kinuvo.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext

/** Nucleus Tao claims Dispatchers.Main, which only runs inside its application; UI tests keep Swing's, as before. */
class SwingMainExtension : BeforeAllCallback {
    @OptIn(ExperimentalCoroutinesApi::class)
    override fun beforeAll(context: ExtensionContext) = Dispatchers.setMain(Dispatchers.Swing)
}
