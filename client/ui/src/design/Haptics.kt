package kinuvo.ui.design

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** [indication] that also taps the Device's haptics on every completed press; on [touch] it shows presses only, never hover or focus. */
internal class HapticIndication(private val indication: IndicationNodeFactory, private val touch: Boolean) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        HapticNode(interactionSource, indication.create(if (touch) PressesOnly(interactionSource) else interactionSource))
    override fun equals(other: Any?) = other is HapticIndication && other.indication == indication && other.touch == touch
    override fun hashCode() = indication.hashCode() * 31 + touch.hashCode()
}

private class PressesOnly(source: InteractionSource) : InteractionSource {
    override val interactions: Flow<Interaction> = source.interactions.filter { it !is HoverInteraction && it !is FocusInteraction }
}

private class HapticNode(private val source: InteractionSource, indication: DelegatableNode) : DelegatingNode(), CompositionLocalConsumerModifierNode {
    init { delegate(indication) }

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { if (it is PressInteraction.Release) currentValueOf(LocalHapticFeedback).performHapticFeedback(HapticFeedbackType.VirtualKey) }
        }
    }
}
