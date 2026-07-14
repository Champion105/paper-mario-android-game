package com.alijah.myapplication

import kotlin.math.abs

enum class ActionCommandType {
    JUMP_BOUNCE,
    GUARD,
    SUPERGUARD
}

enum class BattleTimingResult {
    EXCELLENT,
    GOOD,
    POOR,
    MISS
}

data class ActionCommandSpec(
    val commandType: ActionCommandType,
    val buttonType: String,
    val goodWindowStartSeconds: Float,
    val goodWindowEndSeconds: Float,
    val perfectWindowStartSeconds: Float,
    val perfectWindowEndSeconds: Float
) {
    init {
        require(goodWindowStartSeconds >= 0f) { "goodWindowStartSeconds must be non-negative." }
        require(goodWindowEndSeconds >= goodWindowStartSeconds) { "goodWindowEndSeconds must be after goodWindowStartSeconds." }
        require(perfectWindowStartSeconds >= goodWindowStartSeconds) { "perfectWindowStartSeconds must sit inside the good window." }
        require(perfectWindowEndSeconds <= goodWindowEndSeconds) { "perfectWindowEndSeconds must sit inside the good window." }
        require(perfectWindowEndSeconds >= perfectWindowStartSeconds) { "perfectWindowEndSeconds must be after perfectWindowStartSeconds." }
    }

    fun resultAt(elapsedSeconds: Float): BattleTimingResult {
        if (elapsedSeconds < goodWindowStartSeconds) return BattleTimingResult.POOR
        if (elapsedSeconds > goodWindowEndSeconds) return BattleTimingResult.MISS
        if (elapsedSeconds in perfectWindowStartSeconds..perfectWindowEndSeconds) return BattleTimingResult.EXCELLENT

        val perfectCenter = (perfectWindowStartSeconds + perfectWindowEndSeconds) * 0.5f
        val goodHalfWidth = ((goodWindowEndSeconds - goodWindowStartSeconds) * 0.5f).coerceAtLeast(0.001f)
        val normalizedDistance = abs(elapsedSeconds - perfectCenter) / goodHalfWidth
        return if (normalizedDistance <= 0.82f) BattleTimingResult.GOOD else BattleTimingResult.POOR
    }
}

data class ActionCommandCueState(
    val activeType: ActionCommandType?,
    val actionProgress: Float,
    val elapsedSeconds: Float,
    val isWindowActive: Boolean,
    val lastResult: BattleTimingResult?
) {
    companion object {
        val Idle = ActionCommandCueState(
            activeType = null,
            actionProgress = 0f,
            elapsedSeconds = 0f,
            isWindowActive = false,
            lastResult = null
        )
    }
}

class ActionCommandTracker {
    private var activeSpecs: List<ActionCommandSpec> = emptyList()
    private var elapsedSeconds = 0f
    private var commandEndSeconds = 0f
    private var consumedInput = false

    var lastResult: BattleTimingResult? = null
        private set

    var lastCommandType: ActionCommandType? = null
        private set

    val isActive: Boolean
        get() = activeSpecs.isNotEmpty()

    val cueState: ActionCommandCueState
        get() {
            if (!isActive) return ActionCommandCueState.Idle.copy(lastResult = lastResult)
            val earliest = activeSpecs.minOf { it.goodWindowStartSeconds }
            val latest = activeSpecs.maxOf { it.goodWindowEndSeconds }.coerceAtLeast(earliest + 0.001f)
            return ActionCommandCueState(
                activeType = activeSpecs.firstOrNull()?.commandType,
                actionProgress = ((elapsedSeconds - earliest) / (latest - earliest)).coerceIn(0f, 1f),
                elapsedSeconds = elapsedSeconds,
                isWindowActive = elapsedSeconds in earliest..latest,
                lastResult = lastResult
            )
        }

    fun start(vararg specs: ActionCommandSpec) {
        start(specs.toList())
    }

    fun start(specs: List<ActionCommandSpec>) {
        require(specs.isNotEmpty()) { "At least one action command spec is required." }
        activeSpecs = specs
        elapsedSeconds = 0f
        commandEndSeconds = specs.maxOf { it.goodWindowEndSeconds }
        consumedInput = false
        lastResult = null
        lastCommandType = null
    }

    fun cancel() {
        activeSpecs = emptyList()
        elapsedSeconds = 0f
        commandEndSeconds = 0f
        consumedInput = false
        lastCommandType = null
    }

    fun update(deltaTime: Float): BattleTimingResult? {
        if (!isActive) return null
        elapsedSeconds += deltaTime.coerceAtLeast(0f)
        if (!consumedInput && elapsedSeconds > commandEndSeconds) {
            return finish(BattleTimingResult.MISS, activeSpecs.first().commandType)
        }
        return null
    }

    fun registerBattleInput(inputTime: Long, buttonType: String): BattleTimingResult {
        if (!isActive || consumedInput) return BattleTimingResult.MISS
        val normalizedButton = buttonType.trim().uppercase()
        val matchingSpec = activeSpecs
            .filter { it.buttonType.uppercase() == normalizedButton }
            .minByOrNull { abs(windowCenter(it) - elapsedSeconds) }
            ?: return finish(BattleTimingResult.MISS, activeSpecs.first().commandType)

        if (elapsedSeconds < matchingSpec.goodWindowStartSeconds) {
            lastResult = BattleTimingResult.POOR
            lastCommandType = matchingSpec.commandType
            return BattleTimingResult.POOR
        }
        return finish(matchingSpec.resultAt(elapsedSeconds), matchingSpec.commandType)
    }

    private fun finish(result: BattleTimingResult, commandType: ActionCommandType): BattleTimingResult {
        consumedInput = true
        lastResult = result
        lastCommandType = commandType
        activeSpecs = emptyList()
        return result
    }

    private fun windowCenter(spec: ActionCommandSpec): Float {
        return (spec.perfectWindowStartSeconds + spec.perfectWindowEndSeconds) * 0.5f
    }
}
