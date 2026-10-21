package kinuvo.ui.design

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch

/** [indication] that also taps the Device's haptics on every completed press. */
internal class HapticIndication(private val indication: IndicationNodeFactory) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = HapticNode(interactionSource, indication.create(interactionSource))
    override fun equals(other: Any?) = other is HapticIndication && other.indication == indication
    override fun hashCode() = indication.hashCode()
}

private class HapticNode(private val source: InteractionSource, indication: DelegatableNode) : DelegatingNode(), CompositionLocalConsumerModifierNode {
    init { delegate(indication) }

    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collect { if (it is PressInteraction.Release) currentValueOf(LocalHapticFeedback).performHapticFeedback(HapticFeedbackType.VirtualKey) }
        }
    }
}
