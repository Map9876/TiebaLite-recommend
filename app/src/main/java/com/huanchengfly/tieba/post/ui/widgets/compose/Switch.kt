package com.huanchengfly.tieba.post.ui.widgets.compose

import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    colors: SwitchColors = SwitchDefaults.colors(),
) {
    val strokeWidth = with(LocalDensity.current) { TrackStrokeWidth.toPx() }
    val gap = with(LocalDensity.current) { Gap.toPx() }
    val minBound = strokeWidth + gap
    val maxBound = minBound + with(LocalDensity.current) { ThumbPathLength.toPx() }
    // 自己按帧插值，不依赖 Compose 的动画体系。
    //
    // 系统里有个「移除动画」开关，打开后动画倍率变 0，
    // tween / animateTo 都会被缩成瞬移，开关看起来就像坏了。
    // 这个开关是纯 App 行为，不该被系统开关接管，
    // 所以这里只用 withFrameNanos 自己算进度。
    // pos 只用来驱动绘制，放在 state 里即可；
    // from/to/startedAt 是动画的内部账本，放普通变量避免每次改都重组。
    val pos = remember { mutableFloatStateOf(0f) }
    val anim = remember { SwitchAnim() }

    LaunchedEffect(checked) {
        anim.from = pos.value
        anim.to = if (checked) 1f else 0f
        if (anim.from == anim.to) return@LaunchedEffect
        anim.startedAt = 0L
        while (true) {
            var finished = false
            withFrameNanos { now ->
                if (anim.startedAt == 0L) anim.startedAt = now
                val elapsed = (now - anim.startedAt) / 1_000_000f
                val t = (elapsed / SWITCH_DURATION_MS).coerceIn(0f, 1f)
                // ease-out：起步快、收尾慢，跟手的观感
                val eased = 1f - (1f - t) * (1f - t)
                pos.value = anim.from + (anim.to - anim.from) * eased
                finished = t >= 1f
            }
            if (finished) return@LaunchedEffect
        }
    }

    val swipeableState = object : SwipeableState<Float> {
        override val anchors: Map<Float, Boolean> =
            mapOf(minBound to false, maxBound to true)
        override val thresholds: (Float, Float) -> FractionalThreshold = { _, _ ->
            FractionalThreshold(0.5f)
        }
        override val direction: SwipeDirection = SwipeDirection.RightToLeft
        override val offset: Float
            get() = pos.value * (maxBound - minBound)
        override val progress: Float
            get() = pos.value
        override suspend fun snapTo(targetValue: Float) {
            pos.value = targetValue.coerceIn(0f, 1f)
        }
        override fun animateTo(targetValue: Float, animSpec: AnimationSpec<Float>) {
            // 拖动跟手，松手直接落位，不再补间（补间会和上面的帧循环打架）
            pos.value = targetValue.coerceIn(0f, 1f)
        }
        override fun requireOffset() = Unit
        override fun dispatchRawDelta(delta: Float): Float {
            val before = pos.value
            pos.value = (before + delta / (maxBound - minBound)).coerceIn(0f, 1f)
            return (pos.value - before) * (maxBound - minBound)
        }
    }
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val toggleableModifier =
        if (onCheckedChange != null) {
            Modifier.toggleable(
                value = checked,
                onValueChange = onCheckedChange,
                enabled = enabled,
                role = Role.Switch,
                interactionSource = interactionSource,
                indication = null
            )
        } else {
            Modifier
        }

    Box(
        modifier
            .then(toggleableModifier)
            .swipeable(
                state = swipeableState,
                anchors = mapOf(minBound to false, maxBound to true),
                thresholds = { _, _ -> FractionalThreshold(0.5f) },
                orientation = Orientation.Horizontal,
                enabled = enabled && onCheckedChange != null,
                reverseDirection = isRtl,
                interactionSource = interactionSource,
                resistance = null
            )
            .wrapContentSize(Alignment.Center)
            .requiredSize(SwitchWidth, SwitchHeight)
    ) {
        SwitchImpl(
            checked = checked,
            enabled = enabled,
            colors = colors,
            thumbValue = swipeableState.offset,
            interactionSource = interactionSource
        )
    }
}

@Composable
private fun BoxScope.SwitchImpl(
    checked: Boolean,
    enabled: Boolean,
    colors: SwitchColors,
    thumbValue: State<Float>,
    interactionSource: InteractionSource
) {
    val interactions = remember { mutableStateListOf<Interaction>() }

    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> interactions.add(interaction)
                is PressInteraction.Release -> interactions.remove(interaction.press)
                is PressInteraction.Cancel -> interactions.remove(interaction.press)
                is DragInteraction.Start -> interactions.add(interaction)
                is DragInteraction.Stop -> interactions.remove(interaction.start)
                is DragInteraction.Cancel -> interactions.remove(interaction.start)
            }
        }
    }

    val elevation = 0.dp
    val trackColor by colors.trackColor(enabled, checked)
    Canvas(Modifier.align(Alignment.Center).fillMaxSize()) {
        drawTrack(trackColor, TrackWidth.toPx(), TrackStrokeWidth.toPx(), checked)
    }
    val thumbColor by colors.thumbColor(enabled, checked)
    val elevationOverlay = LocalElevationOverlay.current
    val absoluteElevation = LocalAbsoluteElevation.current + elevation
    val resolvedThumbColor =
        if (thumbColor == MaterialTheme.colors.surface && elevationOverlay != null) {
            elevationOverlay.apply(thumbColor, absoluteElevation)
        } else {
            thumbColor
        }
    Spacer(
        Modifier
            .align(Alignment.CenterStart)
            .offset { IntOffset(thumbValue.value.roundToInt(), 0) }
//            .indication(
//                interactionSource = interactionSource,
//                indication = rememberRipple(bounded = false, radius = ThumbRippleRadius)
//            )
            .requiredSize(ThumbDiameter)
//            .shadow(elevation, CircleShape, clip = false)
            .background(resolvedThumbColor, CircleShape)
    )
}

private fun DrawScope.drawTrack(trackColor: Color, trackWidth: Float, strokeWidth: Float, fill: Boolean = false) {
    val strokeRadius = size.height / 2
    val topY = center.y - size.height / 2 + strokeWidth / 2
    drawRoundRect(
        color = trackColor,
        topLeft = Offset(strokeWidth / 2, topY),
        size = Size(width = trackWidth - strokeWidth, height = size.height - strokeWidth),
        cornerRadius = CornerRadius(strokeRadius),
        style = if (fill) Fill else Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )
}

internal val TrackWidth = 35.dp
internal val TrackStrokeWidth = 2.dp
internal val ThumbDiameter = 9.dp

private val SwitchWidth = TrackWidth
private val SwitchHeight = 15.dp + TrackStrokeWidth
private val Gap = (SwitchHeight - TrackStrokeWidth - ThumbDiameter) / 2
private val ThumbPathLength = TrackWidth - ThumbDiameter - TrackStrokeWidth * 2 - Gap * 2

/** 开关动画的内部账本。放这里而不是 state 里，是为了每帧改它不会触发重组。 */
private class SwitchAnim {
    var from: Float = 0f
    var to: Float = 0f
    var startedAt: Long = 0L
}

private const val SWITCH_DURATION_MS = 140f

@Stable
interface SwitchColors {
    @Composable
    fun thumbColor(enabled: Boolean, checked: Boolean): State<Color>

    @Composable
    fun trackColor(enabled: Boolean, checked: Boolean): State<Color>
}

/**
 * Contains the default values used by [Switch]
 */
object SwitchDefaults {
    /**
     * Creates a [SwitchColors] that represents the different colors used in a [Switch] in
     * different states.
     *
     * @param checkedThumbColor the color used for the thumb when enabled and checked
     * @param checkedTrackColor the color used for the track when enabled and checked
     * @param checkedTrackAlpha the alpha applied to [checkedTrackColor] and
     * [disabledCheckedTrackColor]
     * @param uncheckedThumbColor the color used for the thumb when enabled and unchecked
     * @param uncheckedTrackColor the color used for the track when enabled and unchecked
     * @param uncheckedTrackAlpha the alpha applied to [uncheckedTrackColor] and
     * [disabledUncheckedTrackColor]
     * @param disabledCheckedThumbColor the color used for the thumb when disabled and checked
     * @param disabledCheckedTrackColor the color used for the track when disabled and checked
     * @param disabledUncheckedThumbColor the color used for the thumb when disabled and unchecked
     * @param disabledUncheckedTrackColor the color used for the track when disabled and unchecked
     */
    @Composable
    fun colors(
        checkedThumbColor: Color = MaterialTheme.colors.onSecondary,
        checkedTrackColor: Color = MaterialTheme.colors.secondary,
        checkedTrackAlpha: Float = 1f,
        uncheckedThumbColor: Color = MaterialTheme.colors.onBackground,
        uncheckedTrackColor: Color = MaterialTheme.colors.onBackground,
        uncheckedTrackAlpha: Float = 1f,
        disabledCheckedThumbColor: Color = checkedThumbColor
            .copy(alpha = ContentAlpha.disabled)
            .compositeOver(MaterialTheme.colors.surface),
        disabledCheckedTrackColor: Color = checkedTrackColor
            .copy(alpha = ContentAlpha.disabled)
            .compositeOver(MaterialTheme.colors.surface),
        disabledUncheckedThumbColor: Color = uncheckedThumbColor
            .copy(alpha = ContentAlpha.disabled)
            .compositeOver(MaterialTheme.colors.surface),
        disabledUncheckedTrackColor: Color = uncheckedTrackColor
            .copy(alpha = ContentAlpha.disabled)
            .compositeOver(MaterialTheme.colors.surface)
    ): SwitchColors = DefaultSwitchColors(
        checkedThumbColor = checkedThumbColor,
        checkedTrackColor = checkedTrackColor.copy(alpha = checkedTrackAlpha),
        uncheckedThumbColor = uncheckedThumbColor,
        uncheckedTrackColor = uncheckedTrackColor.copy(alpha = uncheckedTrackAlpha),
        disabledCheckedThumbColor = disabledCheckedThumbColor,
        disabledCheckedTrackColor = disabledCheckedTrackColor.copy(alpha = checkedTrackAlpha),
        disabledUncheckedThumbColor = disabledUncheckedThumbColor,
        disabledUncheckedTrackColor = disabledUncheckedTrackColor.copy(alpha = uncheckedTrackAlpha)
    )
}

/**
 * Default [SwitchColors] implementation.
 */
@Immutable
private class DefaultSwitchColors(
    private val checkedThumbColor: Color,
    private val checkedTrackColor: Color,
    private val uncheckedThumbColor: Color,
    private val uncheckedTrackColor: Color,
    private val disabledCheckedThumbColor: Color,
    private val disabledCheckedTrackColor: Color,
    private val disabledUncheckedThumbColor: Color,
    private val disabledUncheckedTrackColor: Color
) : SwitchColors {
    @Composable
    override fun thumbColor(enabled: Boolean, checked: Boolean): State<Color> {
        return rememberUpdatedState(
            if (enabled) {
                if (checked) checkedThumbColor else uncheckedThumbColor
            } else {
                if (checked) disabledCheckedThumbColor else disabledUncheckedThumbColor
            }
        )
    }

    @Composable
    override fun trackColor(enabled: Boolean, checked: Boolean): State<Color> {
        return rememberUpdatedState(
            if (enabled) {
                if (checked) checkedTrackColor else uncheckedTrackColor
            } else {
                if (checked) disabledCheckedTrackColor else disabledUncheckedTrackColor
            }
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as DefaultSwitchColors

        if (checkedThumbColor != other.checkedThumbColor) return false
        if (checkedTrackColor != other.checkedTrackColor) return false
        if (uncheckedThumbColor != other.uncheckedThumbColor) return false
        if (uncheckedTrackColor != other.uncheckedTrackColor) return false
        if (disabledCheckedThumbColor != other.disabledCheckedThumbColor) return false
        if (disabledCheckedTrackColor != other.disabledCheckedTrackColor) return false
        if (disabledUncheckedThumbColor != other.disabledUncheckedThumbColor) return false
        if (disabledUncheckedTrackColor != other.disabledUncheckedTrackColor) return false

        return true
    }

    override fun hashCode(): Int {
        var result = checkedThumbColor.hashCode()
        result = 31 * result + checkedTrackColor.hashCode()
        result = 31 * result + uncheckedThumbColor.hashCode()
        result = 31 * result + uncheckedTrackColor.hashCode()
        result = 31 * result + disabledCheckedThumbColor.hashCode()
        result = 31 * result + disabledCheckedTrackColor.hashCode()
        result = 31 * result + disabledUncheckedThumbColor.hashCode()
        result = 31 * result + disabledUncheckedTrackColor.hashCode()
        return result
    }
}
