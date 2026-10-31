// audio pass's header picture: the bottom of a phone with a usb-c plug that slides into it while a dac is connected

package com.mochyy.mochymusic.ui.screens.audiolab

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

private val PhoneFill = Color(0xFF1B1B1E)
private val PhoneEdge = Color(0xFF3A3A3F)
private val PlugFill = Color(0xFF2C2C30)
private val PortFill = Color(0xFF0B0B0C)

@Composable
fun BoxScope.AudioPassPicture(plugged: Boolean, wireless: Boolean) {
    // 0 is all the way in, 1 is hanging loose under the phone
    val loose by animateFloatAsState(if (plugged) 0f else 1f, spring(dampingRatio = 0.7f, stiffness = 260f), label = "plug")
    val faded by animateFloatAsState(if (wireless) 0.35f else 1f, tween(300), label = "plugAlpha")
    Canvas(Modifier.fillMaxSize()) {
        val phoneLeft = size.width * 0.1f
        val phoneRight = size.width * 0.9f
        val phoneBottom = size.height * 0.42f
        val centerX = (phoneLeft + phoneRight) / 2f
        val drop = loose * 38.dp.toPx()

        // the plug goes behind the phone, so the part that's inside the port disappears into it
        val plugWidth = 30.dp.toPx()
        val plugTop = phoneBottom + 4.dp.toPx() + drop
        val plugHeight = 64.dp.toPx()
        val connector = Size(20.dp.toPx(), 14.dp.toPx())
        drawRoundRect(PhoneEdge.copy(alpha = faded), Offset(centerX - connector.width / 2f, plugTop - connector.height), connector, CornerRadius(3.dp.toPx()))
        drawRect(PlugFill.copy(alpha = faded), Offset(centerX - 5.dp.toPx(), plugTop + plugHeight - 4.dp.toPx()), Size(10.dp.toPx(), size.height))
        drawRoundRect(PlugFill.copy(alpha = faded), Offset(centerX - plugWidth / 2f, plugTop), Size(plugWidth, plugHeight), CornerRadius(8.dp.toPx()))
        drawRoundRect(
            PhoneEdge.copy(alpha = faded), Offset(centerX - plugWidth / 2f, plugTop), Size(plugWidth, plugHeight),
            CornerRadius(8.dp.toPx()), style = Stroke(1.5.dp.toPx()),
        )

        // the phone runs off the top, only its bottom corners and the port show
        val corner = CornerRadius(34.dp.toPx())
        val top = -corner.x * 2f
        val body = Size(phoneRight - phoneLeft, phoneBottom - top)
        drawRoundRect(PhoneFill, Offset(phoneLeft, top), body, corner)
        drawRoundRect(PhoneEdge, Offset(phoneLeft, top), body, corner, style = Stroke(2.dp.toPx()))
        val inset = 7.dp.toPx()
        drawRoundRect(
            Color(0xFF26262A), Offset(phoneLeft + inset, top + inset), Size(body.width - inset * 2f, body.height - inset * 2f),
            CornerRadius(corner.x - inset), style = Stroke(1.dp.toPx()),
        )
        val port = Size(26.dp.toPx(), 7.dp.toPx())
        drawRoundRect(PortFill, Offset(centerX - port.width / 2f, phoneBottom - port.height - 2.dp.toPx()), port, CornerRadius(port.height / 2f))
    }
}
