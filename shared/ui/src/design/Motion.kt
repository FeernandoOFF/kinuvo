package kinuvo.ui.design

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

object Motion {
    const val immediate = 90
    const val poke = 110
    const val fast = 120
    const val exit = 160
    const val enter = 200
    const val reveal = 220
    const val transition = 240
    const val item = 250
    const val standard = 300
    const val slow = 400
    const val accent = 500
    const val settle = 600
    const val avatar = 700
    const val spin = 900
    const val breath = 1100
    const val pulse = 1400
    const val bob = 3800
    const val revealDelay = 80

    val linear = LinearEasing
    val smooth = FastOutSlowInEasing

    fun <T> slideSpring() = spring<T>(0.7f, Spring.StiffnessMediumLow)
    fun <T> cardSpring() = spring<T>(0.75f, Spring.StiffnessMediumLow)
    fun <T> selectionSpring() = spring<T>(0.6f)
    fun <T> thumbSpring() = spring<T>(0.7f, 500f)
    fun <T> pokeSpring() = spring<T>(0.3f, 320f)
    fun <T> appearSpring() = spring<T>(0.8f, Spring.StiffnessMediumLow)
    fun <T> resizeSpring() = spring<T>(0.85f, Spring.StiffnessMediumLow)
    fun <T> voiceAppearSpring() = spring<T>(0.45f, Spring.StiffnessLow)
    fun <T> voicePressSpring() = spring<T>(0.4f, Spring.StiffnessMedium)
    fun <T> revealSpring() = spring<T>(1f, 400f)
}
