package com.alijah.myapplication

import android.view.KeyEvent
import android.view.MotionEvent
import kotlin.math.abs

enum class ActionCommandResult {
    EXCELLENT,
    GREAT,
    GOOD,
    MISS
}

enum class ActionCommandInputType {
    KEY,
    SCREEN_TAP
}

data class ActionCommand(
    val successWindowStartMs: Long,
    val successWindowEndMs: Long,
    val requiredKeyCode: Int = KeyEvent.KEYCODE_BUTTON_A,
    val inputType: ActionCommandInputType = ActionCommandInputType.KEY,
    val totalDurationMs: Long = successWindowEndMs + 450L
) {
    init {
        require(successWindowStartMs >= 0L) { "successWindowStartMs must be 0 or greater." }
        require(successWindowEndMs >= successWindowStartMs) { "successWindowEndMs must be after successWindowStartMs." }
        require(totalDurationMs >= successWindowEndMs) { "totalDurationMs must cover the success window." }
    }

    val successCenterMs: Long
        get() = (successWindowStartMs + successWindowEndMs) / 2L

    val successWindowSizeMs: Long
        get() = successWindowEndMs - successWindowStartMs

    fun acceptsKey(keyCode: Int): Boolean {
        return inputType == ActionCommandInputType.KEY && keyCode == requiredKeyCode
    }

    fun acceptsTap(): Boolean {
        return inputType == ActionCommandInputType.SCREEN_TAP
    }
}

data class TimingBarState(
    val progress: Float,
    val successStart: Float,
    val successEnd: Float,
    val isActive: Boolean,
    val lastResult: ActionCommandResult?
) {
    companion object {
        val Idle = TimingBarState(
            progress = 0f,
            successStart = 0f,
            successEnd = 0f,
            isActive = false,
            lastResult = null
        )
    }
}

class TimingBarHelper {
    fun createState(
        command: ActionCommand?,
        elapsedMs: Long,
        isActive: Boolean,
        lastResult: ActionCommandResult?
    ): TimingBarState {
        if (command == null) return TimingBarState.Idle.copy(lastResult = lastResult)

        val total = command.totalDurationMs.coerceAtLeast(1L).toFloat()
        return TimingBarState(
            progress = (elapsedMs / total).coerceIn(0f, 1f),
            successStart = (command.successWindowStartMs / total).coerceIn(0f, 1f),
            successEnd = (command.successWindowEndMs / total).coerceIn(0f, 1f),
            isActive = isActive,
            lastResult = lastResult
        )
    }
}

class ActionCommandManager(
    private val timingBarHelper: TimingBarHelper = TimingBarHelper()
) {
    private var activeCommand: ActionCommand? = null
    private var elapsedMs: Long = 0L
    private var consumedInput = false

    var lastResult: ActionCommandResult? = null
        private set

    val isActive: Boolean
        get() = activeCommand != null

    val timingBarState: TimingBarState
        get() = timingBarHelper.createState(activeCommand, elapsedMs, isActive, lastResult)

    fun start(command: ActionCommand) {
        activeCommand = command
        elapsedMs = 0L
        consumedInput = false
        lastResult = null
    }

    fun cancel() {
        activeCommand = null
        elapsedMs = 0L
        consumedInput = false
    }

    fun update(deltaMs: Long): ActionCommandResult? {
        val command = activeCommand ?: return null
        elapsedMs = (elapsedMs + deltaMs.coerceAtLeast(0L)).coerceAtMost(command.totalDurationMs)
        if (elapsedMs >= command.totalDurationMs && !consumedInput) {
            return finish(ActionCommandResult.MISS)
        }
        return null
    }

    fun onKeyEvent(event: KeyEvent): ActionCommandResult? {
        val command = activeCommand ?: return null
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return null
        if (!command.acceptsKey(event.keyCode)) return null
        return evaluateInput()
    }

    fun onMotionEvent(event: MotionEvent): ActionCommandResult? {
        val command = activeCommand ?: return null
        if (event.actionMasked != MotionEvent.ACTION_DOWN && event.actionMasked != MotionEvent.ACTION_POINTER_DOWN) {
            return null
        }
        if (!command.acceptsTap()) return null
        return evaluateInput()
    }

    fun onControllerButton(keyCode: Int): ActionCommandResult? {
        val command = activeCommand ?: return null
        if (!command.acceptsKey(keyCode)) return null
        return evaluateInput()
    }

    private fun evaluateInput(): ActionCommandResult {
        val command = activeCommand ?: return ActionCommandResult.MISS
        val result = when {
            elapsedMs < command.successWindowStartMs -> ActionCommandResult.MISS
            elapsedMs > command.successWindowEndMs -> ActionCommandResult.MISS
            else -> gradeInsideWindow(command, elapsedMs)
        }
        return finish(result)
    }

    private fun gradeInsideWindow(command: ActionCommand, inputMs: Long): ActionCommandResult {
        val halfWindow = (command.successWindowSizeMs / 2f).coerceAtLeast(1f)
        val normalizedDistance = abs(inputMs - command.successCenterMs) / halfWindow
        return when {
            normalizedDistance <= 0.2f -> ActionCommandResult.EXCELLENT
            normalizedDistance <= 0.5f -> ActionCommandResult.GREAT
            else -> ActionCommandResult.GOOD
        }
    }

    private fun finish(result: ActionCommandResult): ActionCommandResult {
        lastResult = result
        activeCommand = null
        consumedInput = true
        return result
    }
}
