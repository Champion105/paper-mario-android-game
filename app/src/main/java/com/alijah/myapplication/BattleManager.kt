package com.alijah.myapplication

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

data class BattleVector3(
    val x: Float,
    val y: Float,
    val z: Float
) {
    fun lerpTo(target: BattleVector3, amount: Float): BattleVector3 {
        val t = amount.coerceIn(0f, 1f)
        return BattleVector3(
            x = x + (target.x - x) * t,
            y = y + (target.y - y) * t,
            z = z + (target.z - z) * t
        )
    }
}

enum class BattleAnimationState {
    IDLE,
    RUNNING,
    JUMPING,
    HURT,
    DEFEAT
}

data class BattleActor(
    val id: String,
    val name: String,
    val maxHp: Int,
    var currentHp: Int,
    val baseAttack: Int,
    val baseDefense: Int,
    val isPlayerTeam: Boolean,
    var position: BattleVector3,
    var targetPosition: BattleVector3 = position,
    var animationState: BattleAnimationState = BattleAnimationState.IDLE,
    val xpReward: Int = 0,
    var defeatTimer: Float = 0f,
    var defeatRewardGranted: Boolean = false
) {
    val isAlive: Boolean
        get() = currentHp > 0

    fun receiveDamage(rawDamage: Int): Int {
        val wasAlive = isAlive
        val damage = max(1, rawDamage - baseDefense)
        currentHp = max(0, currentHp - damage)
        animationState = if (currentHp == 0) BattleAnimationState.DEFEAT else BattleAnimationState.HURT
        if (wasAlive && currentHp == 0) {
            defeatTimer = 0f
            defeatRewardGranted = false
        }
        return damage
    }

    fun receiveFinalDamage(finalDamage: Int): Int {
        val wasAlive = isAlive
        val damage = finalDamage.coerceAtLeast(0)
        currentHp = max(0, currentHp - damage)
        if (damage > 0) {
            animationState = if (currentHp == 0) BattleAnimationState.DEFEAT else BattleAnimationState.HURT
            if (wasAlive && currentHp == 0) {
                defeatTimer = 0f
                defeatRewardGranted = false
            }
        }
        return damage
    }
}

enum class BattleState {
    INTRO,
    PLAYER_MENU,
    PLAYER_ATTACKING,
    PLAYER_RETURN_POST_ATTACK_DELAY,
    ENEMY_PRE_ATTACK_WINDUP,
    ENEMY_ATTACKING,
    ENEMY_POST_ATTACK_DELAY,
    CHECK_BATTLE_STATUS,
    VICTORY_OUTRO,
    DEFEAT_OUTRO
}

enum class BattleStartAdvantage {
    NEUTRAL,
    PLAYER_FIRST_STRIKE,
    ENEMY_FIRST_STRIKE
}

enum class BattleMenuItemType {
    JUMP,
    HAMMER,
    ITEMS,
    STRATEGY,
    RUN,
    NO_ITEMS
}

enum class BattleMenuRenderLayer {
    BACKGROUND_DECORATION,
    FOREGROUND
}

private enum class BattleMenuMode {
    MAIN,
    TACTICS,
    ITEMS_NOTICE
}

data class BattleMenuOption(
    val itemType: BattleMenuItemType,
    val displayName: String,
    val actionId: String?,
    val isEnabled: Boolean
)

data class BattleMenuItemRenderSpec(
    val option: BattleMenuOption,
    val position: BattleVector3,
    val scale: Float,
    val alpha: Float,
    val isSelected: Boolean,
    val currentAngleDegrees: Float,
    val useIconWithDescription: Boolean,
    val descriptionAlpha: Float,
    val renderLayer: BattleMenuRenderLayer
)

data class BattleHpBarSpec(
    val actorId: String,
    val centerPosition: BattleVector3,
    val currentHp: Int,
    val maxHp: Int,
    val displayPercentage: Float,
    val isVisible: Boolean,
    val alpha: Float
)

class BattleMenu(
    initialItems: List<BattleMenuOption>,
    var currentSelectedIndex: Int = 0,
    var rotationAngle: Float = 0f,
    var targetAngle: Float = 0f
) {
    var items: List<BattleMenuOption> = initialItems
        private set
    private val descriptionAlphas = mutableMapOf<BattleMenuItemType, Float>()

    init {
        reset()
    }

    fun cycle(direction: Int) {
        if (items.isEmpty()) return
        currentSelectedIndex = (currentSelectedIndex + direction).floorMod(items.size)
        targetAngle = TARGET_ANGLE_DEGREES - baseAngleFor(currentSelectedIndex)
    }

    fun update(deltaTime: Float) {
        rotationAngle += (targetAngle - rotationAngle) * (12f * deltaTime).coerceIn(0f, 1f)
        items.forEachIndexed { index, option ->
            val currentAlpha = descriptionAlphas[option.itemType] ?: 0f
            descriptionAlphas[option.itemType] = if (isInActiveSlot(index)) {
                currentAlpha + (1f - currentAlpha) * DESCRIPTION_ALPHA_LERP
            } else {
                0f
            }
        }
    }

    fun reset() {
        currentSelectedIndex = 0
        targetAngle = TARGET_ANGLE_DEGREES - baseAngleFor(currentSelectedIndex)
        rotationAngle = targetAngle
        descriptionAlphas.clear()
    }

    fun setItems(nextItems: List<BattleMenuOption>) {
        items = nextItems
        reset()
    }

    fun selectedOption(): BattleMenuOption? {
        return items.getOrNull(currentSelectedIndex)
    }

    fun currentAngleFor(index: Int): Float {
        return normalizeAngle(baseAngleFor(index) + rotationAngle)
    }

    fun isInActiveSlot(index: Int): Boolean {
        return angleDistanceDegrees(currentAngleFor(index), TARGET_ANGLE_DEGREES) <= SELECTION_THRESHOLD_DEGREES
    }

    fun descriptionAlphaFor(option: BattleMenuOption): Float {
        return descriptionAlphas[option.itemType] ?: 0f
    }

    private fun baseAngleFor(index: Int): Float {
        val count = items.size
        if (count <= 1) return TARGET_ANGLE_DEGREES
        if (count <= UPPER_ARC_MAX_ITEMS) {
            val step = (UPPER_ARC_END_DEGREES - UPPER_ARC_START_DEGREES) / (count - 1).toFloat()
            return UPPER_ARC_END_DEGREES - index * step
        }
        val step = FULL_CIRCLE_DEGREES / count.toFloat()
        return TARGET_ANGLE_DEGREES - index * step
    }

    private fun angleDistanceDegrees(a: Float, b: Float): Float {
        val diff = kotlin.math.abs(normalizeAngle(a) - normalizeAngle(b))
        return kotlin.math.min(diff, FULL_CIRCLE_DEGREES - diff)
    }

    private fun normalizeAngle(angle: Float): Float {
        return ((angle % FULL_CIRCLE_DEGREES) + FULL_CIRCLE_DEGREES) % FULL_CIRCLE_DEGREES
    }

    private fun Int.floorMod(divisor: Int): Int {
        return ((this % divisor) + divisor) % divisor
    }

    companion object {
        private const val FULL_CIRCLE_DEGREES = 360f
        private const val TARGET_ANGLE_DEGREES = 90f
        private const val SELECTION_THRESHOLD_DEGREES = 5f
        private const val UPPER_ARC_START_DEGREES = 30f
        private const val UPPER_ARC_END_DEGREES = 150f
        private const val UPPER_ARC_MAX_ITEMS = 5
        private const val DESCRIPTION_ALPHA_LERP = 0.2f
    }
}

data class BattleMove(
    val id: String,
    val displayName: String,
    val attackBonus: Int,
    val travelSeconds: Float,
    val impactSeconds: Float,
    val recoverSeconds: Float,
    val jumpArcHeight: Float
) {
    val totalSeconds: Float
        get() = travelSeconds + impactSeconds + recoverSeconds
}

data class DamageParticle(
    val value: Int,
    val startPosition: BattleVector3,
    val position: BattleVector3,
    val velocity: BattleVector3,
    val alpha: Float = 1f,
    val scale: Float = 0f,
    val zRotation: Float = 0f,
    val gravity: Float = -9.8f,
    val age: Float = 0f,
    val maxLifetime: Float = 1.2f,
    val bounceFloorY: Float = 0.98f,
    val bounceCount: Int = 0
)

data class ExpFlyParticle(
    val value: Int,
    val startPosition: BattleVector3,
    val endPosition: BattleVector3,
    val position: BattleVector3 = startPosition,
    val age: Float = 0f,
    val maxLifetime: Float = 0.82f,
    val alpha: Float = 1f,
    val scale: Float = 1f
)

private data class HpBarAnimation(
    var displayPercentage: Float,
    var startPercentage: Float,
    var targetPercentage: Float,
    var elapsedSeconds: Float = 0.3f
)

data class BattleActorRenderSnapshot(
    val id: String,
    val name: String,
    val isPlayerTeam: Boolean,
    val position: BattleVector3,
    val targetPosition: BattleVector3,
    val animationState: BattleAnimationState,
    val currentHp: Int,
    val maxHp: Int,
    val facing: Float,
    val alpha: Float,
    val scaleX: Float,
    val scaleY: Float,
    val shakeX: Float,
    val defeatProgress: Float
)

data class BattleRenderState(
    val state: BattleState,
    val actors: List<BattleActorRenderSnapshot>,
    val activeActorId: String?,
    val selectedTargetId: String?,
    val activeCameraTarget: BattleVector3,
    val cameraFov: Float,
    val stateProgress: Float,
    val lastResolvedDamage: Int,
    val xpAwarded: Int,
    val actionProgress: Float,
    val isActionWindowActive: Boolean,
    val timingIndicator: String,
    val isShieldActive: Boolean,
    val isCommandMenuActive: Boolean,
    val menuOptions: List<BattleMenuOption>,
    val selectedMenuIndex: Int,
    val menuItemSpecs: List<BattleMenuItemRenderSpec>,
    val hpBars: List<BattleHpBarSpec>,
    val damageParticles: List<DamageParticle>,
    val expParticles: List<ExpFlyParticle>,
    val playerStarPoints: Int,
    val maxStarPoints: Int,
    val screenShake: Float,
    val cameraOffset: BattleVector3
)

data class BattleResult(
    val victory: Boolean,
    val xpAwarded: Int,
    val turnsTaken: Int
)

interface BattleFeedbackListener {
    fun onBattleHaptic(type: BattleHapticType)
}

class BattleManager(
    playerActors: List<BattleActor> = emptyList(),
    enemyActors: List<BattleActor> = emptyList(),
    private val random: Random = Random.Default
) {
    var currentState: BattleState = BattleState.INTRO
        private set

    val players: MutableList<BattleActor> = mutableListOf()
    val enemies: MutableList<BattleActor> = mutableListOf()
    val battleLog: MutableList<String> = mutableListOf()

    var xpAwarded: Int = 0
        private set
    var turnsTaken: Int = 0
        private set
    var lastResolvedDamage: Int = 0
        private set
    var playerStarPoints: Int = 0
        private set
    var lastResult: BattleResult? = null
        private set

    val activeActor: BattleActor?
        get() = activeActorId?.let(::findActor)

    val selectedTarget: BattleActor?
        get() = selectedTargetId?.let(::findActor)

    val renderState: BattleRenderState
        get() = BattleRenderState(
            state = currentState,
            actors = (players + enemies).map { actor ->
                val defeatProgress = (actor.defeatTimer / ENEMY_DEFEAT_SECONDS).coerceIn(0f, 1f)
                val sputter = if (actor.animationState == BattleAnimationState.DEFEAT) sin(actor.defeatTimer * 58f) else 0f
                BattleActorRenderSnapshot(
                    id = actor.id,
                    name = actor.name,
                    isPlayerTeam = actor.isPlayerTeam,
                    position = actor.position,
                    targetPosition = actor.targetPosition,
                    animationState = actor.animationState,
                    currentHp = actor.currentHp,
                    maxHp = actor.maxHp,
                    facing = if (actor.isPlayerTeam) 1f else -1f,
                    alpha = if (actor.animationState == BattleAnimationState.DEFEAT) {
                        (1f - defeatProgress).coerceIn(0f, 1f) * if ((actor.defeatTimer * 26f).toInt() % 2 == 0) 1f else 0.42f
                    } else {
                        1f
                    },
                    scaleX = if (actor.animationState == BattleAnimationState.DEFEAT) 1f + 0.16f * sputter else 1f,
                    scaleY = if (actor.animationState == BattleAnimationState.DEFEAT) 1f - 0.12f * sputter else 1f,
                    shakeX = if (actor.animationState == BattleAnimationState.DEFEAT) sin(actor.defeatTimer * 88f) * 0.07f * (1f - defeatProgress) else 0f,
                    defeatProgress = defeatProgress
                )
            },
            activeActorId = activeActorId,
            selectedTargetId = selectedTargetId,
            activeCameraTarget = cameraTarget,
            cameraFov = cameraFov,
            stateProgress = stateProgress,
            lastResolvedDamage = lastResolvedDamage,
            xpAwarded = xpAwarded,
            actionProgress = actionCommandTracker.cueState.actionProgress,
            isActionWindowActive = actionCommandTracker.cueState.isWindowActive,
            timingIndicator = timingIndicatorText,
            isShieldActive = shieldTimer > 0f,
            isCommandMenuActive = currentState == BattleState.PLAYER_MENU && activeActor?.isPlayerTeam == true,
            menuOptions = battleMenu.items,
            selectedMenuIndex = battleMenu.currentSelectedIndex,
            menuItemSpecs = createMenuItemSpecs(),
            hpBars = createHpBarSpecs(),
            damageParticles = damageParticles.toList(),
            expParticles = expParticles.toList(),
            playerStarPoints = playerStarPoints,
            maxStarPoints = MAX_STAR_POINTS,
            screenShake = screenShake,
            cameraOffset = cameraOffset
        )

    var feedbackListener: BattleFeedbackListener? = null

    private val actionCommandTracker = ActionCommandTracker()
    private val mainBattleMenuItems = listOf(
        BattleMenuOption(BattleMenuItemType.JUMP, "Jump", "jump", isEnabled = true),
        BattleMenuOption(BattleMenuItemType.HAMMER, "Hammer", "hammer", isEnabled = true),
        BattleMenuOption(BattleMenuItemType.ITEMS, "Backpack", null, isEnabled = true),
        BattleMenuOption(BattleMenuItemType.STRATEGY, "Tactics", null, isEnabled = true)
    )
    private val tacticsBattleMenuItems = listOf(
        BattleMenuOption(BattleMenuItemType.RUN, "Run", "run", isEnabled = true)
    )
    private val noItemsBattleMenuItems = listOf(
        BattleMenuOption(BattleMenuItemType.NO_ITEMS, "No Items", null, isEnabled = false)
    )
    private val battleMenu = BattleMenu(mainBattleMenuItems)
    private val turnQueue: ArrayDeque<String> = ArrayDeque()
    private val movesById = mapOf(
        "jump" to BattleMove("jump", "Jump", attackBonus = 0, travelSeconds = JUMP_FIRST_ARC_SECONDS, impactSeconds = JUMP_CONTACT_SETTLE_SECONDS, recoverSeconds = JUMP_WALK_BACK_SECONDS, jumpArcHeight = JUMP_FIRST_ARC_HEIGHT),
        "hammer" to BattleMove("hammer", "Hammer", attackBonus = 2, travelSeconds = 0.22f, impactSeconds = 0.16f, recoverSeconds = 0.32f, jumpArcHeight = 0.12f),
        "enemy_bump" to BattleMove("enemy_bump", "Bump", attackBonus = 0, travelSeconds = 0.36f, impactSeconds = 0.14f, recoverSeconds = 0.42f, jumpArcHeight = 0.18f),
        "enemy_tackle" to BattleMove("enemy_tackle", "Tackle", attackBonus = 1, travelSeconds = 0.42f, impactSeconds = 0.12f, recoverSeconds = 0.44f, jumpArcHeight = 0.08f)
    )
    private val enemyMoveIds = listOf("enemy_bump", "enemy_tackle")
    private val damageParticles = mutableListOf<DamageParticle>()
    private val expParticles = mutableListOf<ExpFlyParticle>()
    private val hpBarAnimations = mutableMapOf<String, HpBarAnimation>()
    private val hpBarAlphas = mutableMapOf<String, Float>()

    private var stateTimer = 0f
    private var stateProgress = 0f
    private var introStartPositions: Map<String, BattleVector3> = emptyMap()
    private var activeActorId: String? = null
    private var selectedTargetId: String? = null
    private var pendingMove: BattleMove? = null
    private var actionStart: BattleVector3 = BattleVector3(0f, 0f, 0f)
    private var actionImpact: BattleVector3 = BattleVector3(0f, 0f, 0f)
    private var actionHome: BattleVector3 = BattleVector3(0f, 0f, 0f)
    private var jumpStompPosition: BattleVector3 = BattleVector3(0f, 0f, 0f)
    private var jumpCommandStarted = false
    private var actionDamageResolved = false
    private var jumpBounceResult: BattleTimingResult? = null
    private var jumpBounceDamageResolved = false
    private var defenseCommandResult: BattleTimingResult? = null
    private var defenseCommandType: ActionCommandType? = null
    private var battleMenuMode = BattleMenuMode.MAIN
    private var battleMenuNoticeTimer = 0f
    private var nextStateAfterAttack = BattleState.CHECK_BATTLE_STATUS
    private var startAdvantage = BattleStartAdvantage.NEUTRAL
    private var cameraTarget = BattleVector3(0f, 0.95f, 0.25f)
    private var cameraFov = 44f
    private var timingIndicatorText = ""
    private var timingIndicatorTimer = 0f
    private var shieldTimer = 0f
    private var hpBarVisibleTimer = 0f
    private var screenShake = 0f
    private var screenShakeTimer = 0f
    private var screenShakeStrength = 0f
    private var cameraOffset = BattleVector3(0f, 0f, 0f)
    private var hitStopTimer = 0f

    init {
        if (playerActors.isNotEmpty() || enemyActors.isNotEmpty()) {
            startBattle(playerActors, enemyActors, BattleStartAdvantage.NEUTRAL)
        }
    }

    fun startBattle(
        playerParty: List<BattleActor>,
        enemyParty: List<BattleActor>,
        advantage: BattleStartAdvantage = BattleStartAdvantage.NEUTRAL
    ) {
        players.clear()
        enemies.clear()
        players += playerParty.mapIndexed { index, actor ->
            actor.copy(
                currentHp = actor.currentHp.coerceIn(0, actor.maxHp),
                position = playerIntroPosition(index),
                targetPosition = playerHomePosition(index),
                animationState = BattleAnimationState.RUNNING
            )
        }
        enemies += enemyParty.mapIndexed { index, actor ->
            actor.copy(
                currentHp = actor.currentHp.coerceIn(0, actor.maxHp),
                position = enemyIntroPosition(index),
                targetPosition = enemyHomePosition(index),
                animationState = BattleAnimationState.RUNNING
            )
        }
        startAdvantage = advantage
        introStartPositions = (players + enemies).associate { it.id to it.position }
        turnQueue.clear()
        battleLog.clear()
        xpAwarded = 0
        turnsTaken = 0
        lastResolvedDamage = 0
        lastResult = null
        activeActorId = null
        selectedTargetId = null
        pendingMove = null
        actionDamageResolved = false
        jumpBounceResult = null
        jumpBounceDamageResolved = false
        defenseCommandResult = null
        defenseCommandType = null
        actionCommandTracker.cancel()
        timingIndicatorText = ""
        timingIndicatorTimer = 0f
        shieldTimer = 0f
        hpBarVisibleTimer = 0f
        battleMenuMode = BattleMenuMode.MAIN
        battleMenuNoticeTimer = 0f
        battleMenu.setItems(mainBattleMenuItems)
        damageParticles.clear()
        expParticles.clear()
        hpBarAnimations.clear()
        enemies.forEach { actor ->
            val hpPercent = actor.hpPercent()
            hpBarAnimations[actor.id] = HpBarAnimation(
                displayPercentage = hpPercent,
                startPercentage = hpPercent,
                targetPercentage = hpPercent
            )
        }
        screenShake = 0f
        screenShakeTimer = 0f
        screenShakeStrength = 0f
        cameraOffset = BattleVector3(0f, 0f, 0f)
        hitStopTimer = 0f
        cameraTarget = BattleVector3(0f, 0.95f, 0.25f)
        cameraFov = 44f
        transitionTo(BattleState.INTRO)
        battleLog += "Battle started: ${advantage.name.lowercase().replace('_', ' ')}."
    }

    fun update(deltaTime: Float) {
        val dt = deltaTime.coerceIn(0f, 0.08f)
        updateScreenShake(dt)
        updateDamageParticles(dt)
        updateHpBarAnimations(dt)
        if (hitStopTimer > 0f) {
            hitStopTimer = (hitStopTimer - dt).coerceAtLeast(0f)
            return
        }
        updateActionCommandFeedback(dt)
        updateBattleMenu(dt)
        stateTimer += dt
        updateExpParticles(dt)
        updateDefeatAnimations(dt)
        when (currentState) {
            BattleState.INTRO -> updateIntro()
            BattleState.PLAYER_MENU -> updateChooseAction()
            BattleState.PLAYER_ATTACKING -> updateAttack()
            BattleState.PLAYER_RETURN_POST_ATTACK_DELAY -> updatePlayerReturnPostAttackDelay()
            BattleState.ENEMY_PRE_ATTACK_WINDUP -> updateEnemyPreAttackWindup()
            BattleState.ENEMY_ATTACKING -> updateAttack()
            BattleState.ENEMY_POST_ATTACK_DELAY -> updateEnemyPostAttackDelay()
            BattleState.CHECK_BATTLE_STATUS -> updateCheckStatus()
            BattleState.VICTORY_OUTRO -> updateVictoryOutro()
            BattleState.DEFEAT_OUTRO -> updateDefeatOutro()
        }
    }

    fun registerBattleInput(inputTime: Long, buttonType: String): BattleTimingResult? {
        if (!actionCommandTracker.isActive) return null
        val result = actionCommandTracker.registerBattleInput(inputTime, buttonType)
        val commandType = actionCommandTracker.lastCommandType
        when (commandType) {
            ActionCommandType.JUMP_BOUNCE -> {
                jumpBounceResult = result
                timingIndicatorText = jumpTimingIndicator(result)
                timingIndicatorTimer = TIMING_INDICATOR_SECONDS
                feedbackListener?.onBattleHaptic(if (result == BattleTimingResult.EXCELLENT) BattleHapticType.DOUBLE_EXPLODING_KICK else BattleHapticType.LIGHT_TAP)
            }
            ActionCommandType.GUARD, ActionCommandType.SUPERGUARD -> {
                defenseCommandResult = result
                defenseCommandType = commandType
                timingIndicatorText = guardTimingIndicator(commandType, result)
                timingIndicatorTimer = TIMING_INDICATOR_SECONDS
                if (isSuccessfulTiming(result)) shieldTimer = SHIELD_FLASH_SECONDS
                val haptic = when {
                    commandType == ActionCommandType.SUPERGUARD && isSuccessfulTiming(result) -> BattleHapticType.DOUBLE_EXPLODING_KICK
                    isSuccessfulTiming(result) -> BattleHapticType.MEDIUM_THUD
                    else -> BattleHapticType.LIGHT_TAP
                }
                feedbackListener?.onBattleHaptic(haptic)
            }
            null -> Unit
        }
        return result
    }

    fun selectPlayerAction(actionId: String, target: BattleActor): Boolean {
        if (currentState != BattleState.PLAYER_MENU) return false
        val actor = activeActor ?: return false
        if (!actor.isPlayerTeam || !actor.isAlive || !target.isAlive || target.isPlayerTeam) return false
        val move = movesById[actionId] ?: return false
        beginAttack(actor, target, move, BattleState.PLAYER_ATTACKING)
        return true
    }

    fun selectPlayerAction(actionId: String, targetId: String): Boolean {
        val target = enemies.firstOrNull { it.id == targetId && it.isAlive } ?: return false
        return selectPlayerAction(actionId, target)
    }

    fun cycleBattleMenu(direction: Int): Boolean {
        if (currentState != BattleState.PLAYER_MENU || activeActor?.isPlayerTeam != true) return false
        if (battleMenuMode == BattleMenuMode.ITEMS_NOTICE) return false
        battleMenu.cycle(direction)
        feedbackListener?.onBattleHaptic(BattleHapticType.LIGHT_TAP)
        return true
    }

    fun confirmSelectedMenuAction(): Boolean {
        if (currentState != BattleState.PLAYER_MENU || activeActor?.isPlayerTeam != true) return false
        val option = battleMenu.selectedOption() ?: return false
        when (battleMenuMode) {
            BattleMenuMode.MAIN -> {
                if (option.itemType == BattleMenuItemType.ITEMS) {
                    openNoItemsMenu()
                    return true
                }
                if (option.itemType == BattleMenuItemType.STRATEGY) {
                    openTacticsMenu()
                    return true
                }
            }
            BattleMenuMode.TACTICS -> {
                if (option.itemType == BattleMenuItemType.RUN) {
                    timingIndicatorText = "RUN"
                    timingIndicatorTimer = 0.65f
                    resetMainBattleMenu()
                    feedbackListener?.onBattleHaptic(BattleHapticType.LIGHT_TAP)
                    return true
                }
            }
            BattleMenuMode.ITEMS_NOTICE -> return true
        }
        if (!option.isEnabled || option.actionId == null) {
            timingIndicatorText = "${option.displayName.uppercase()}!"
            timingIndicatorTimer = 0.45f
            feedbackListener?.onBattleHaptic(BattleHapticType.LIGHT_TAP)
            return true
        }
        val target = enemies.firstOrNull { it.isAlive } ?: return false
        feedbackListener?.onBattleHaptic(BattleHapticType.LIGHT_TAP)
        return selectPlayerAction(option.actionId, target)
    }

    private fun openTacticsMenu() {
        battleMenuMode = BattleMenuMode.TACTICS
        battleMenuNoticeTimer = 0f
        timingIndicatorText = "TACTICS"
        timingIndicatorTimer = 0.45f
        battleMenu.setItems(tacticsBattleMenuItems)
        feedbackListener?.onBattleHaptic(BattleHapticType.LIGHT_TAP)
    }

    private fun openNoItemsMenu() {
        battleMenuMode = BattleMenuMode.ITEMS_NOTICE
        battleMenuNoticeTimer = NO_ITEMS_NOTICE_SECONDS
        timingIndicatorText = "NO ITEMS"
        timingIndicatorTimer = NO_ITEMS_NOTICE_SECONDS
        battleMenu.setItems(noItemsBattleMenuItems)
        feedbackListener?.onBattleHaptic(BattleHapticType.LIGHT_TAP)
    }

    private fun resetMainBattleMenu() {
        battleMenuMode = BattleMenuMode.MAIN
        battleMenuNoticeTimer = 0f
        battleMenu.setItems(mainBattleMenuItems)
    }

    fun isBattleOver(): Boolean {
        return currentState == BattleState.VICTORY_OUTRO || currentState == BattleState.DEFEAT_OUTRO
    }

    private fun updateIntro() {
        stateProgress = (stateTimer / INTRO_SECONDS).coerceIn(0f, 1f)
        val t = easeOutCubic(stateProgress)
        players.forEachIndexed { index, actor ->
            val start = introStartPositions[actor.id] ?: playerIntroPosition(index)
            val home = playerHomePosition(index)
            actor.position = start.lerpTo(home, t)
            actor.targetPosition = home
            actor.animationState = if (stateProgress < 1f) BattleAnimationState.RUNNING else BattleAnimationState.IDLE
        }
        enemies.forEachIndexed { index, actor ->
            val start = introStartPositions[actor.id] ?: enemyIntroPosition(index)
            val home = enemyHomePosition(index)
            actor.position = start.lerpTo(home, t)
            actor.targetPosition = home
            actor.animationState = if (stateProgress < 1f) BattleAnimationState.RUNNING else BattleAnimationState.IDLE
        }
        cameraTarget = BattleVector3(0f, 0.95f, 0.25f)
        cameraFov = 46f - 2f * t
        if (stateProgress >= 1f) {
            buildTurnQueue()
            when (startAdvantage) {
                BattleStartAdvantage.PLAYER_FIRST_STRIKE -> {
                    activeActorId = players.firstOrNull { it.isAlive }?.id
                    val target = enemies.firstOrNull { it.isAlive }
                    val actor = activeActor
                    if (actor != null && target != null) {
                        beginAttack(actor, target, movesById.getValue("jump"), BattleState.PLAYER_ATTACKING, firstStrikeBonus = 1)
                    } else {
                        transitionTo(BattleState.CHECK_BATTLE_STATUS)
                    }
                }
                BattleStartAdvantage.ENEMY_FIRST_STRIKE -> {
                    activeActorId = enemies.firstOrNull { it.isAlive }?.id
                    transitionTo(BattleState.ENEMY_PRE_ATTACK_WINDUP)
                }
                BattleStartAdvantage.NEUTRAL -> advanceToNextTurn()
            }
        }
    }

    private fun updateChooseAction() {
        stateProgress = 1f
        val actor = activeActor
        if (actor == null || !actor.isAlive || !actor.isPlayerTeam) {
            transitionTo(BattleState.CHECK_BATTLE_STATUS)
            return
        }
        actor.animationState = BattleAnimationState.IDLE
        cameraTarget = actor.position.lerpTo(BattleVector3(0f, 0.95f, 0.25f), 0.55f)
        cameraFov = 42f
    }

    private fun updateEnemyPreAttackWindup() {
        stateProgress = (stateTimer / ENEMY_PRE_ATTACK_TOTAL_SECONDS).coerceIn(0f, 1f)
        val actor = activeActor
        if (actor == null || !actor.isAlive || actor.isPlayerTeam) {
            transitionTo(BattleState.CHECK_BATTLE_STATUS)
            return
        }
        actor.position = homePositionFor(actor)
        actor.targetPosition = actor.position
        actor.animationState = BattleAnimationState.IDLE
        cameraTarget = actor.position.copy(y = 0.95f)
        cameraFov = 42f
        if (stateTimer < ENEMY_PRE_ATTACK_TOTAL_SECONDS) return
        val target = players.firstOrNull { it.isAlive }
        if (target == null) {
            transitionTo(BattleState.CHECK_BATTLE_STATUS)
            return
        }
        val moveId = enemyMoveIds[random.nextInt(enemyMoveIds.size)]
        beginAttack(actor, target, movesById.getValue(moveId), BattleState.ENEMY_ATTACKING)
    }

    private fun updatePlayerReturnPostAttackDelay() {
        stateProgress = (stateTimer / PLAYER_POST_ATTACK_DELAY_SECONDS).coerceIn(0f, 1f)
        activeActor?.let { actor ->
            actor.position = homePositionFor(actor)
            actor.targetPosition = actor.position
            actor.animationState = BattleAnimationState.IDLE
            cameraTarget = actor.position.lerpTo(BattleVector3(0f, 0.95f, 0.25f), 0.45f)
            cameraFov = 42f
        }
        if (stateTimer >= PLAYER_POST_ATTACK_DELAY_SECONDS) {
            transitionTo(BattleState.CHECK_BATTLE_STATUS)
        }
    }

    private fun updateEnemyPostAttackDelay() {
        stateProgress = (stateTimer / ENEMY_POST_ATTACK_DELAY_SECONDS).coerceIn(0f, 1f)
        val actor = activeActor
        val target = selectedTarget ?: players.firstOrNull { it.isAlive }
        actor?.let {
            it.position = homePositionFor(it)
            it.targetPosition = it.position
            it.animationState = BattleAnimationState.IDLE
        }
        target?.let {
            if (it.isAlive && it.animationState != BattleAnimationState.DEFEAT) {
                it.animationState = BattleAnimationState.IDLE
            }
            cameraTarget = it.position.copy(y = 0.92f)
        }
        cameraFov = 42f
        if (stateTimer >= ENEMY_POST_ATTACK_DELAY_SECONDS) {
            transitionTo(BattleState.CHECK_BATTLE_STATUS)
        }
    }

    private fun beginAttack(
        actor: BattleActor,
        target: BattleActor,
        move: BattleMove,
        attackState: BattleState,
        firstStrikeBonus: Int = 0
    ) {
        pendingMove = move.copy(attackBonus = move.attackBonus + firstStrikeBonus)
        activeActorId = actor.id
        selectedTargetId = target.id
        actionDamageResolved = false
        jumpBounceResult = null
        jumpBounceDamageResolved = false
        defenseCommandResult = null
        defenseCommandType = null
        actionCommandTracker.cancel()
        lastResolvedDamage = 0
        actionStart = actor.position
        actionHome = homePositionFor(actor)
        val direction = if (actor.isPlayerTeam) -1f else 1f
        actionImpact = BattleVector3(
            x = target.position.x + direction * 0.42f,
            y = 0.22f,
            z = target.position.z
        )
        jumpStompPosition = target.position.copy(
            x = target.position.x,
            y = target.position.y + JUMP_STOMP_Y_OFFSET,
            z = target.position.z - 0.02f
        )
        if (actor.isPlayerTeam && move.id == "jump") {
            actionImpact = target.position.copy(
                x = target.position.x - JUMP_TAKEOFF_DISTANCE,
                y = actor.position.y,
                z = target.position.z
            )
        }
        if (!actor.isPlayerTeam) {
            actionImpact = actionImpact.copy(
                x = target.position.x + 0.46f,
                y = actor.position.y,
                z = target.position.z
            )
        }
        actor.targetPosition = actionImpact
        actor.animationState = if (move.jumpArcHeight > 0.2f) BattleAnimationState.JUMPING else BattleAnimationState.RUNNING
        target.animationState = BattleAnimationState.IDLE
        nextStateAfterAttack = if (actor.isPlayerTeam) {
            BattleState.PLAYER_RETURN_POST_ATTACK_DELAY
        } else {
            BattleState.ENEMY_POST_ATTACK_DELAY
        }
        transitionTo(attackState)
        jumpCommandStarted = false
        if (!(actor.isPlayerTeam && move.id == "jump")) {
            startActionCommandForAttack(actor, pendingMove ?: move)
        }
    }

    private fun updateAttack() {
        val actor = activeActor
        val target = selectedTarget
        val move = pendingMove
        if (actor == null || target == null || move == null || !actor.isAlive) {
            transitionTo(BattleState.CHECK_BATTLE_STATUS)
            return
        }

        val total = actionTotalSeconds(move)
        stateProgress = (stateTimer / total).coerceIn(0f, 1f)
        if (currentState == BattleState.ENEMY_ATTACKING) {
            updateEnemyAttack(actor, target, move, total)
        } else if (currentState == BattleState.PLAYER_ATTACKING && move.id == "jump") {
            updateJumpAttack(actor, target, move, total)
        } else {
            updateBasicAttack(actor, target, move, total)
        }

        if (currentState == BattleState.ENEMY_ATTACKING) {
            val zoomAmount = 1f - stateProgress * 0.35f
            cameraTarget = actor.position.lerpTo(target.position, 0.58f).copy(y = 0.92f)
            cameraFov = lerp(43f, 36.5f, zoomAmount.coerceIn(0f, 1f))
        } else {
            val impactPulse = if (actionDamageResolved && stateProgress < 0.72f) 1f else 0f
            val attackZoom = when {
                stateProgress < 0.2f -> easeInOut(stateProgress / 0.2f)
                stateProgress > 0.72f -> 1f - easeInOut((stateProgress - 0.72f) / 0.28f)
                else -> 1f
            }.coerceIn(0f, 1f)
            cameraTarget = actor.position.lerpTo(target.position, 0.5f).copy(y = 0.95f)
            cameraFov = lerp(44f, if (impactPulse > 0f) 34.5f else 37.5f, attackZoom)
        }
    }

    private fun updateBasicAttack(actor: BattleActor, target: BattleActor, move: BattleMove, total: Float) {
        when {
            stateTimer <= move.travelSeconds -> {
                val t = easeOutCubic(stateTimer / move.travelSeconds)
                actor.position = arcLerp(actionStart, actionImpact, t, move.jumpArcHeight)
                actor.animationState = if (move.jumpArcHeight > 0.2f) BattleAnimationState.JUMPING else BattleAnimationState.RUNNING
            }
            stateTimer <= move.travelSeconds + move.impactSeconds -> {
                actor.position = actionImpact
                actor.animationState = BattleAnimationState.IDLE
                if (!actionDamageResolved) {
                    lastResolvedDamage = resolveAttackDamage(actor, target, move)
                    actionDamageResolved = true
                }
            }
            stateTimer <= total -> {
                val recoverElapsed = stateTimer - move.travelSeconds - move.impactSeconds
                val t = easeInOut(recoverElapsed / move.recoverSeconds)
                actor.position = actionImpact.lerpTo(actionHome, t)
                actor.targetPosition = actionHome
                actor.animationState = BattleAnimationState.RUNNING
                if (target.isAlive && target.animationState == BattleAnimationState.HURT && recoverElapsed > 0.18f) {
                    target.animationState = BattleAnimationState.IDLE
                }
            }
            else -> finishAttack(actor, target)
        }
    }

    private fun updateJumpAttack(actor: BattleActor, target: BattleActor, move: BattleMove, total: Float) {
        val bounceSuccess = isSuccessfulTiming(jumpBounceResult)
        val approachEnd = JUMP_APPROACH_SECONDS
        val firstStompEnd = approachEnd + JUMP_FIRST_ARC_SECONDS
        val firstImpactEnd = firstStompEnd + JUMP_CONTACT_SETTLE_SECONDS
        val bounceEnd = firstImpactEnd + if (bounceSuccess) JUMP_SECOND_BOUNCE_SECONDS else 0f
        val returnArcEnd = bounceEnd + JUMP_RETURN_ARC_SECONDS
        when {
            stateTimer <= approachEnd -> {
                val t = easeInOut(stateTimer / approachEnd)
                actor.position = actionStart.lerpTo(actionImpact, t)
                actor.targetPosition = actionImpact
                actor.animationState = BattleAnimationState.RUNNING
            }
            stateTimer <= firstStompEnd -> {
                if (!jumpCommandStarted) {
                    startActionCommandForAttack(actor, move)
                    jumpCommandStarted = true
                }
                val t = easeInOut((stateTimer - approachEnd) / JUMP_FIRST_ARC_SECONDS)
                actor.position = arcLerp(actionImpact, jumpStompPosition, t, JUMP_FIRST_ARC_HEIGHT)
                actor.animationState = BattleAnimationState.JUMPING
                if (!actionDamageResolved && t >= JUMP_IMPACT_PROGRESS) {
                    actor.position = jumpStompPosition
                    lastResolvedDamage = target.receiveDamage(JUMP_DAMAGE_PER_HIT + target.baseDefense)
                    spawnDamageParticle(lastResolvedDamage, target, fromPlayerTeam = actor.isPlayerTeam)
                    handlePotentialEnemyDefeat(target)
                    triggerImpactFeedback(BattleHapticType.MEDIUM_THUD, shakeAmount = 0.42f)
                    battleLog += "${actor.name} landed ${move.displayName} on ${target.name} for $lastResolvedDamage damage."
                    actionDamageResolved = true
                }
            }
            stateTimer <= firstImpactEnd -> {
                actor.position = jumpStompPosition
                actor.animationState = BattleAnimationState.JUMPING
                if (!actionDamageResolved) {
                    lastResolvedDamage = target.receiveDamage(JUMP_DAMAGE_PER_HIT + target.baseDefense)
                    spawnDamageParticle(lastResolvedDamage, target, fromPlayerTeam = actor.isPlayerTeam)
                    handlePotentialEnemyDefeat(target)
                    triggerImpactFeedback(BattleHapticType.MEDIUM_THUD, shakeAmount = 0.42f)
                    battleLog += "${actor.name} landed ${move.displayName} on ${target.name} for $lastResolvedDamage damage."
                    actionDamageResolved = true
                }
            }
            bounceSuccess && stateTimer <= bounceEnd -> {
                val t = ((stateTimer - firstImpactEnd) / JUMP_SECOND_BOUNCE_SECONDS).coerceIn(0f, 1f)
                val lift = 4f * t * (1f - t) * JUMP_SECOND_BOUNCE_HEIGHT
                actor.position = jumpStompPosition.copy(y = jumpStompPosition.y + lift)
                actor.animationState = BattleAnimationState.JUMPING
                if (!jumpBounceDamageResolved && t >= JUMP_IMPACT_PROGRESS) {
                    actor.position = jumpStompPosition
                    val dealt = target.receiveDamage(JUMP_DAMAGE_PER_HIT + target.baseDefense)
                    lastResolvedDamage += dealt
                    spawnDamageParticle(dealt, target, fromPlayerTeam = actor.isPlayerTeam)
                    handlePotentialEnemyDefeat(target)
                    triggerImpactFeedback(
                        if (jumpBounceResult == BattleTimingResult.EXCELLENT) BattleHapticType.DOUBLE_EXPLODING_KICK else BattleHapticType.MEDIUM_THUD,
                        shakeAmount = if (jumpBounceResult == BattleTimingResult.EXCELLENT) 0.62f else 0.45f
                    )
                    battleLog += "${actor.name} bounced again for $dealt extra damage."
                    jumpBounceDamageResolved = true
                }
            }
            stateTimer <= returnArcEnd -> {
                val t = easeInOut((stateTimer - bounceEnd) / JUMP_RETURN_ARC_SECONDS)
                actor.position = arcLerp(jumpStompPosition, actionImpact, t, JUMP_RETURN_ARC_HEIGHT)
                actor.targetPosition = actionImpact
                actor.animationState = BattleAnimationState.JUMPING
                if (target.isAlive && target.animationState == BattleAnimationState.HURT && stateTimer - bounceEnd > 0.18f) {
                    target.animationState = BattleAnimationState.IDLE
                }
            }
            stateTimer <= total -> {
                val recoverElapsed = stateTimer - returnArcEnd
                val t = easeInOut(recoverElapsed / JUMP_WALK_BACK_SECONDS)
                actor.position = actionImpact.lerpTo(actionHome, t)
                actor.targetPosition = actionHome
                actor.animationState = BattleAnimationState.RUNNING
                if (target.isAlive && target.animationState == BattleAnimationState.HURT && recoverElapsed > 0.18f) {
                    target.animationState = BattleAnimationState.IDLE
                }
            }
            else -> finishAttack(actor, target)
        }
    }

    private fun updateEnemyAttack(actor: BattleActor, target: BattleActor, move: BattleMove, total: Float) {
        val rushEnd = move.travelSeconds
        val impactEnd = rushEnd + move.impactSeconds

        when {
            stateTimer <= rushEnd -> {
                val t = easeOutCubic(stateTimer / move.travelSeconds)
                actor.position = arcLerp(actionHome, actionImpact, t, move.jumpArcHeight)
                actor.targetPosition = actionImpact
                actor.animationState = BattleAnimationState.RUNNING
            }
            stateTimer <= impactEnd -> {
                actor.position = actionImpact
                actor.animationState = BattleAnimationState.IDLE
                if (!actionDamageResolved || actorReachedTarget(actor, target)) {
                    if (!actionDamageResolved) {
                        lastResolvedDamage = resolveAttackDamage(actor, target, move)
                        actionDamageResolved = true
                    }
                }
            }
            stateTimer <= total -> {
                val recoverElapsed = stateTimer - impactEnd
                val t = easeInOut(recoverElapsed / move.recoverSeconds)
                actor.position = actionImpact.lerpTo(actionHome, t)
                actor.targetPosition = actionHome
                actor.animationState = BattleAnimationState.RUNNING
                if (target.isAlive && target.animationState == BattleAnimationState.HURT && recoverElapsed > 0.2f) {
                    target.animationState = BattleAnimationState.IDLE
                }
            }
            else -> finishAttack(actor, target)
        }
    }

    private fun finishAttack(actor: BattleActor, target: BattleActor) {
        actor.position = actionHome
        actor.targetPosition = actionHome
        actor.animationState = BattleAnimationState.IDLE
        if (target.isAlive && target.animationState != BattleAnimationState.DEFEAT) {
            target.animationState = BattleAnimationState.IDLE
        }
        pendingMove = null
        actionCommandTracker.cancel()
        transitionTo(nextStateAfterAttack)
    }

    private fun resolveAttackDamage(actor: BattleActor, target: BattleActor, move: BattleMove): Int {
        if (currentState == BattleState.ENEMY_ATTACKING) {
            val incomingDamage = max(1, actor.baseAttack + move.attackBonus - target.baseDefense)
            val dealt = when {
                defenseCommandType == ActionCommandType.SUPERGUARD && isSuccessfulTiming(defenseCommandResult) -> {
                    val counterDamage = actor.receiveDamage(SUPERGUARD_COUNTER_DAMAGE)
                    spawnDamageParticle(counterDamage, actor, fromPlayerTeam = target.isPlayerTeam)
                    handlePotentialEnemyDefeat(actor)
                    triggerImpactFeedback(BattleHapticType.DOUBLE_EXPLODING_KICK, shakeAmount = 0.72f, hitStopSeconds = 0.14f)
                    battleLog += "${target.name} superguarded! ${actor.name} took $counterDamage counter damage."
                    0
                }
                defenseCommandType == ActionCommandType.GUARD && isSuccessfulTiming(defenseCommandResult) -> {
                    val guardedDamage = (incomingDamage - GUARD_DAMAGE_REDUCTION).coerceAtLeast(0)
                    battleLog += "${target.name} guarded the hit."
                    val dealt = target.receiveFinalDamage(guardedDamage)
                    if (dealt > 0) spawnDamageParticle(dealt, target, fromPlayerTeam = actor.isPlayerTeam)
                    handlePotentialEnemyDefeat(target)
                    triggerImpactFeedback(BattleHapticType.MEDIUM_THUD, shakeAmount = 0.32f, hitStopSeconds = 0.1f)
                    dealt
                }
                else -> {
                    val dealt = target.receiveFinalDamage(incomingDamage)
                    spawnDamageParticle(dealt, target, fromPlayerTeam = actor.isPlayerTeam)
                    handlePotentialEnemyDefeat(target)
                    triggerImpactFeedback(BattleHapticType.MEDIUM_THUD, shakeAmount = 0.52f, hitStopSeconds = 0.12f)
                    dealt
                }
            }
            if (dealt > 0) {
                battleLog += "${actor.name} used ${move.displayName} on ${target.name} for $dealt damage."
            }
            return dealt
        }

        val dealt = target.receiveDamage(actor.baseAttack + move.attackBonus)
        spawnDamageParticle(dealt, target, fromPlayerTeam = actor.isPlayerTeam)
        handlePotentialEnemyDefeat(target)
        triggerImpactFeedback(BattleHapticType.MEDIUM_THUD, shakeAmount = 0.42f, hitStopSeconds = 0.1f)
        battleLog += "${actor.name} used ${move.displayName} on ${target.name} for $dealt damage."
        return dealt
    }

    private fun startActionCommandForAttack(actor: BattleActor, move: BattleMove) {
        when {
            actor.isPlayerTeam && move.id == "jump" -> actionCommandTracker.start(
                ActionCommandSpec(
                    commandType = ActionCommandType.JUMP_BOUNCE,
                    buttonType = "A",
                    goodWindowStartSeconds = JUMP_GOOD_START_SECONDS,
                    goodWindowEndSeconds = JUMP_GOOD_END_SECONDS,
                    perfectWindowStartSeconds = JUMP_PERFECT_START_SECONDS,
                    perfectWindowEndSeconds = JUMP_PERFECT_END_SECONDS
                )
            )
            !actor.isPlayerTeam -> {
                val impactSeconds = move.travelSeconds
                actionCommandTracker.start(
                    ActionCommandSpec(
                        commandType = ActionCommandType.GUARD,
                        buttonType = "A",
                        goodWindowStartSeconds = (impactSeconds - GUARD_WINDOW_SECONDS).coerceAtLeast(0f),
                        goodWindowEndSeconds = impactSeconds,
                        perfectWindowStartSeconds = (impactSeconds - SUPERGUARD_WINDOW_SECONDS).coerceAtLeast(0f),
                        perfectWindowEndSeconds = impactSeconds
                    ),
                    ActionCommandSpec(
                        commandType = ActionCommandType.SUPERGUARD,
                        buttonType = "B",
                        goodWindowStartSeconds = (impactSeconds - SUPERGUARD_WINDOW_SECONDS).coerceAtLeast(0f),
                        goodWindowEndSeconds = impactSeconds,
                        perfectWindowStartSeconds = (impactSeconds - SUPERGUARD_WINDOW_SECONDS).coerceAtLeast(0f),
                        perfectWindowEndSeconds = impactSeconds
                    )
                )
            }
        }
    }

    private fun updateActionCommandFeedback(deltaTime: Float) {
        if (timingIndicatorTimer > 0f) {
            timingIndicatorTimer = (timingIndicatorTimer - deltaTime).coerceAtLeast(0f)
            if (timingIndicatorTimer == 0f) timingIndicatorText = ""
        }
        if (shieldTimer > 0f) {
            shieldTimer = (shieldTimer - deltaTime).coerceAtLeast(0f)
        }

        val autoResult = actionCommandTracker.update(deltaTime) ?: return
        when (val commandType = actionCommandTracker.lastCommandType) {
            ActionCommandType.JUMP_BOUNCE -> {
                jumpBounceResult = autoResult
                timingIndicatorText = jumpTimingIndicator(autoResult)
                timingIndicatorTimer = TIMING_INDICATOR_SECONDS
            }
            ActionCommandType.GUARD, ActionCommandType.SUPERGUARD -> {
                defenseCommandResult = autoResult
                defenseCommandType = commandType
                timingIndicatorText = guardTimingIndicator(commandType, autoResult)
                timingIndicatorTimer = TIMING_INDICATOR_SECONDS
                if (isSuccessfulTiming(autoResult)) shieldTimer = SHIELD_FLASH_SECONDS
            }
            null -> Unit
        }
    }

    private fun updateBattleMenu(deltaTime: Float) {
        if (currentState == BattleState.PLAYER_MENU && activeActor?.isPlayerTeam == true) {
            battleMenu.update(deltaTime)
            if (battleMenuNoticeTimer > 0f) {
                battleMenuNoticeTimer = (battleMenuNoticeTimer - deltaTime).coerceAtLeast(0f)
                if (battleMenuNoticeTimer == 0f && battleMenuMode == BattleMenuMode.ITEMS_NOTICE) {
                    resetMainBattleMenu()
                }
            }
        }
        if (hpBarVisibleTimer > 0f) {
            hpBarVisibleTimer = (hpBarVisibleTimer - deltaTime).coerceAtLeast(0f)
        }
    }

    private fun createMenuItemSpecs(): List<BattleMenuItemRenderSpec> {
        if (currentState != BattleState.PLAYER_MENU || activeActor?.isPlayerTeam != true) return emptyList()
        val actor = activeActor ?: return emptyList()
        val items = battleMenu.items
        if (items.isEmpty()) return emptyList()

        val selectedIndex = battleMenu.currentSelectedIndex
        val inactiveItems = items.filterIndexed { index, _ -> index != selectedIndex }
        
        val center = BattleVector3(
            x = actor.position.x,
            y = actor.position.y + 0.95f, // Anchored above head
            z = actor.position.z
        )

        val specs = mutableListOf<BattleMenuItemRenderSpec>()

        // 1. The Active Selection Focus State
        // Sits prominently at the bottom-center of the arc (90 degrees, but lower radius)
        val selectedOption = items[selectedIndex]
        specs.add(
            BattleMenuItemRenderSpec(
                option = selectedOption,
                position = BattleVector3(
                    x = center.x,
                    y = center.y + 0.35f, // Positioned at the focus point
                    z = center.z - 0.55f // Prominently in front
                ),
                scale = 1.35f,
                alpha = 1.0f,
                isSelected = true,
                currentAngleDegrees = 90f,
                useIconWithDescription = true,
                descriptionAlpha = battleMenu.descriptionAlphaFor(selectedOption),
                renderLayer = BattleMenuRenderLayer.FOREGROUND
            )
        )

        // 2. The Background Arc (Inactive circular badges)
        if (inactiveItems.isNotEmpty()) {
            val radius = 1.25f // Tightened
            val startAngle = 40f
            val endAngle = 140f
            val arcRange = endAngle - startAngle
            
            val step = if (inactiveItems.size > 1) arcRange / (inactiveItems.size - 1) else 0f

            inactiveItems.forEachIndexed { index, option ->
                val angleDeg = startAngle + index * step
                val angleRad = degreesToRadians(angleDeg)
                
                // Recede background items slightly more in Z-space based on their angle
                val zRecede = 0.25f + abs(90f - angleDeg) * 0.005f
                
                specs.add(
                    BattleMenuItemRenderSpec(
                        option = option,
                        position = BattleVector3(
                            x = center.x + radius * cos(angleRad),
                            y = center.y + radius * sin(angleRad) * 0.85f, // Slightly elliptical arc
                            z = center.z + zRecede
                        ),
                        scale = 0.62f, // Slightly smaller
                        alpha = if (option.isEnabled) 0.55f else 0.25f,
                        isSelected = false,
                        currentAngleDegrees = angleDeg,
                        useIconWithDescription = false,
                        descriptionAlpha = 0f,
                        renderLayer = BattleMenuRenderLayer.BACKGROUND_DECORATION
                    )
                )
            }
        }

        return specs.sortedBy { it.renderLayer.ordinal }
    }

    private fun createHpBarSpecs(): List<BattleHpBarSpec> {
        return enemies.map { actor ->
            val displayPercent = hpBarAnimations[actor.id]?.displayPercentage ?: actor.hpPercent()
            val alpha = hpBarAlphas.getOrDefault(actor.id, 0.0f)
            BattleHpBarSpec(
                actorId = actor.id,
                centerPosition = BattleVector3(
                    x = actor.position.x,
                    y = actor.position.y - 0.22f, // Closer to feet
                    z = actor.position.z - 0.15f // Slightly forward
                ),
                currentHp = actor.currentHp,
                maxHp = actor.maxHp,
                displayPercentage = displayPercent.coerceIn(0f, 1f),
                isVisible = alpha > 0.01f,
                alpha = alpha
            )
        }
    }

    private fun updateHpBarAnimations(deltaTime: Float) {
        enemies.forEach { actor ->
            // 1. Manage Percentage Animation
            val target = actor.hpPercent()
            val animation = hpBarAnimations.getOrPut(actor.id) {
                HpBarAnimation(
                    displayPercentage = target,
                    startPercentage = target,
                    targetPercentage = target
                )
            }
            if (kotlin.math.abs(animation.targetPercentage - target) > 0.001f) {
                animation.startPercentage = animation.displayPercentage
                animation.targetPercentage = target
                animation.elapsedSeconds = 0f
            }
            if (animation.elapsedSeconds < HP_BAR_LERP_SECONDS) {
                animation.elapsedSeconds = (animation.elapsedSeconds + deltaTime).coerceAtMost(HP_BAR_LERP_SECONDS)
                val t = easeInOut(animation.elapsedSeconds / HP_BAR_LERP_SECONDS)
                animation.displayPercentage = lerp(animation.startPercentage, animation.targetPercentage, t)
            } else {
                animation.displayPercentage = animation.targetPercentage
            }

            // 2. Manage Visibility Alpha
            val isTargeted = selectedTargetId == actor.id
            val hasRecentDamage = hpBarVisibleTimer > 0f
            val desiredAlpha = if (actor.isAlive && (isTargeted || hasRecentDamage)) 1.0f else 0.0f
            val currentAlpha = hpBarAlphas.getOrDefault(actor.id, 0.0f)
            
            // Fast fade in, slightly slower fade out
            val fadeSpeed = if (desiredAlpha > currentAlpha) 10f else 4.5f
            val nextAlpha = if (currentAlpha < desiredAlpha) {
                (currentAlpha + fadeSpeed * deltaTime).coerceAtMost(desiredAlpha)
            } else {
                (currentAlpha - fadeSpeed * deltaTime).coerceAtLeast(desiredAlpha)
            }
            hpBarAlphas[actor.id] = nextAlpha
        }
        hpBarAnimations.keys.retainAll(enemies.map { it.id }.toSet())
        hpBarAlphas.keys.retainAll(enemies.map { it.id }.toSet())
    }

    private fun BattleActor.hpPercent(): Float {
        return if (maxHp <= 0) 0f else (currentHp.toFloat() / maxHp.toFloat()).coerceIn(0f, 1f)
    }

    private fun actionTotalSeconds(move: BattleMove): Float {
        if (currentState == BattleState.ENEMY_ATTACKING) {
            return move.travelSeconds + move.impactSeconds + move.recoverSeconds
        }
        if (currentState == BattleState.PLAYER_ATTACKING && move.id == "jump") {
            val bounceSeconds = if (isSuccessfulTiming(jumpBounceResult)) JUMP_SECOND_BOUNCE_SECONDS else 0f
            return JUMP_APPROACH_SECONDS + JUMP_FIRST_ARC_SECONDS + JUMP_CONTACT_SETTLE_SECONDS + bounceSeconds + JUMP_RETURN_ARC_SECONDS + JUMP_WALK_BACK_SECONDS
        }
        return move.totalSeconds
    }

    private fun isSuccessfulTiming(result: BattleTimingResult?): Boolean {
        return result == BattleTimingResult.EXCELLENT || result == BattleTimingResult.GOOD
    }

    private fun updateDamageParticles(deltaTime: Float) {
        val iterator = damageParticles.listIterator()
        while (iterator.hasNext()) {
            val particle = iterator.next()
            val nextAge = particle.age + deltaTime
            if (nextAge >= particle.maxLifetime) {
                iterator.remove()
                continue
            }

            val nextPosition = if (nextAge <= DAMAGE_NUMBER_POP_SECONDS) {
                val t = easeOutQuad(nextAge / DAMAGE_NUMBER_POP_SECONDS)
                particle.startPosition.copy(y = particle.startPosition.y + DAMAGE_NUMBER_POP_HEIGHT * t)
            } else {
                val floatT = ((nextAge - DAMAGE_NUMBER_POP_SECONDS) / DAMAGE_NUMBER_FLOAT_SECONDS).coerceIn(0f, 1f)
                particle.startPosition.copy(
                    y = particle.startPosition.y + DAMAGE_NUMBER_POP_HEIGHT + DAMAGE_NUMBER_FLOAT_HEIGHT * floatT
                )
            }
            val popScale = when {
                nextAge < 0.05f -> lerp(0.85f, 1.28f, easeOutQuad(nextAge / 0.05f))
                nextAge < DAMAGE_NUMBER_POP_SECONDS -> lerp(1.28f, 1f, easeOutQuad((nextAge - 0.05f) / (DAMAGE_NUMBER_POP_SECONDS - 0.05f)))
                else -> 1f
            }
            val nextAlpha = if (nextAge <= DAMAGE_NUMBER_POP_SECONDS) {
                1f
            } else {
                (1f - (nextAge - DAMAGE_NUMBER_POP_SECONDS) / DAMAGE_NUMBER_FLOAT_SECONDS).coerceIn(0f, 1f)
            }
            val wobble = (kotlin.math.sin(nextAge * 24f) * 0.06f).coerceIn(-0.12f, 0.12f)

            iterator.set(
                particle.copy(
                    position = nextPosition,
                    age = nextAge,
                    scale = popScale,
                    alpha = nextAlpha,
                    zRotation = wobble
                )
            )
        }
    }

    private fun updateExpParticles(deltaTime: Float) {
        val iterator = expParticles.listIterator()
        while (iterator.hasNext()) {
            val particle = iterator.next()
            val nextAge = particle.age + deltaTime
            if (nextAge >= particle.maxLifetime) {
                iterator.remove()
                continue
            }
            val t = easeInOut(nextAge / particle.maxLifetime)
            val arc = 4f * t * (1f - t) * 0.55f
            val nextPosition = particle.startPosition.lerpTo(particle.endPosition, t).copy(
                y = particle.startPosition.lerpTo(particle.endPosition, t).y + arc
            )
            iterator.set(
                particle.copy(
                    age = nextAge,
                    position = nextPosition,
                    alpha = (1f - (nextAge / particle.maxLifetime) * 0.15f).coerceIn(0f, 1f),
                    scale = 1f + sin(nextAge * 18f) * 0.08f
                )
            )
        }
    }

    private fun updateDefeatAnimations(deltaTime: Float) {
        val canPlayDefeat = currentState == BattleState.CHECK_BATTLE_STATUS ||
            currentState == BattleState.VICTORY_OUTRO ||
            currentState == BattleState.DEFEAT_OUTRO
        enemies.filter { it.currentHp == 0 }.forEach { enemy ->
            enemy.animationState = BattleAnimationState.DEFEAT
            if (canPlayDefeat) {
                enemy.defeatTimer = (enemy.defeatTimer + deltaTime).coerceAtMost(ENEMY_DEFEAT_SECONDS)
                if (!enemy.defeatRewardGranted) {
                    grantEnemyDefeatReward(enemy)
                }
            }
        }
    }

    private fun handlePotentialEnemyDefeat(actor: BattleActor) {
        if (!actor.isPlayerTeam && actor.currentHp == 0) {
            actor.animationState = BattleAnimationState.HURT
        }
    }

    private fun grantEnemyDefeatReward(enemy: BattleActor) {
        enemy.defeatRewardGranted = true
        val gained = enemy.xpReward.coerceAtLeast(0)
        if (gained <= 0) return
        xpAwarded += gained
        playerStarPoints = (playerStarPoints + gained).coerceAtMost(MAX_STAR_POINTS)
        expParticles += ExpFlyParticle(
            value = gained,
            startPosition = enemy.position.copy(y = enemy.position.y + 1.05f, z = enemy.position.z - 0.12f),
            endPosition = STAR_POINT_HUD_WORLD_POSITION
        )
        feedbackListener?.onBattleHaptic(BattleHapticType.LIGHT_TAP)
        battleLog += "${enemy.name} sputtered out. Earned $gained star points."
    }

    private fun updateScreenShake(deltaTime: Float) {
        if (screenShakeTimer <= 0f) {
            screenShake = 0f
            screenShakeStrength = 0f
            cameraOffset = BattleVector3(0f, 0f, 0f)
            return
        }
        screenShakeTimer = (screenShakeTimer - deltaTime).coerceAtLeast(0f)
        val t = screenShakeTimer / SCREEN_SHAKE_SECONDS
        screenShake = screenShakeStrength * t
        val amplitude = CAMERA_SHAKE_MAX_OFFSET * screenShakeStrength * t
        cameraOffset = BattleVector3(
            x = (random.nextFloat() * 2f - 1f) * amplitude,
            y = (random.nextFloat() * 2f - 1f) * amplitude,
            z = 0f
        )
    }

    private fun spawnDamageParticle(value: Int, actor: BattleActor, fromPlayerTeam: Boolean) {
        if (value <= 0) return
        if (!actor.isPlayerTeam) hpBarVisibleTimer = HP_BAR_RECENT_DAMAGE_SECONDS
        val spawn = actor.position.copy(
            y = actor.position.y + DAMAGE_NUMBER_SPAWN_HEIGHT,
            z = actor.position.z - 0.09f
        )
        damageParticles += DamageParticle(
            value = value,
            startPosition = spawn,
            position = spawn,
            velocity = BattleVector3(0f, 0f, 0f),
            scale = 0.85f,
            gravity = 0f,
            bounceFloorY = spawn.y,
            maxLifetime = DAMAGE_NUMBER_LIFETIME_SECONDS
        )
    }

    private fun triggerImpactFeedback(hapticType: BattleHapticType, shakeAmount: Float, hitStopSeconds: Float = HIT_STOP_SECONDS) {
        feedbackListener?.onBattleHaptic(hapticType)
        screenShakeStrength = shakeAmount.coerceIn(0f, 1f)
        screenShake = screenShakeStrength
        screenShakeTimer = SCREEN_SHAKE_SECONDS
        hitStopTimer = max(hitStopTimer, hitStopSeconds)
    }

    private fun actorReachedTarget(actor: BattleActor, target: BattleActor): Boolean {
        return kotlin.math.abs(actor.position.x - target.position.x) <= 0.58f &&
            kotlin.math.abs(actor.position.z - target.position.z) <= 0.3f
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float {
        return start + (end - start) * amount.coerceIn(0f, 1f)
    }

    private fun degreesToRadians(degrees: Float): Float {
        return degrees * 0.017453292f
    }

    private fun jumpTimingIndicator(result: BattleTimingResult): String {
        return when (result) {
            BattleTimingResult.EXCELLENT -> "EXCELLENT!"
            BattleTimingResult.GOOD -> "GREAT!"
            BattleTimingResult.POOR -> "EARLY!"
            BattleTimingResult.MISS -> "MISS!"
        }
    }

    private fun guardTimingIndicator(commandType: ActionCommandType?, result: BattleTimingResult): String {
        if (!isSuccessfulTiming(result)) return "MISS!"
        return if (commandType == ActionCommandType.SUPERGUARD) "SUPERGUARD!" else "GUARD!"
    }

    private fun updateCheckStatus() {
        stateProgress = (stateTimer / CHECK_STATUS_SECONDS).coerceIn(0f, 1f)
        players.filter { !it.isAlive }.forEach { it.animationState = BattleAnimationState.DEFEAT }
        enemies.filter { !it.isAlive }.forEach { it.animationState = BattleAnimationState.DEFEAT }

        val playersAlive = players.any { it.isAlive }
        val enemiesAlive = enemies.any { it.isAlive }
        val enemyDefeatsComplete = enemies
            .filter { it.currentHp == 0 }
            .all { it.defeatTimer >= ENEMY_DEFEAT_SECONDS }
        when {
            !enemiesAlive && enemyDefeatsComplete -> {
                lastResult = BattleResult(victory = true, xpAwarded = xpAwarded, turnsTaken = turnsTaken)
                battleLog += "Victory! Earned $xpAwarded star points."
                transitionTo(BattleState.VICTORY_OUTRO)
            }
            !enemiesAlive -> Unit
            !playersAlive -> {
                lastResult = BattleResult(victory = false, xpAwarded = 0, turnsTaken = turnsTaken)
                battleLog += "Defeat..."
                transitionTo(BattleState.DEFEAT_OUTRO)
            }
            stateTimer >= CHECK_STATUS_SECONDS -> advanceToNextTurn()
        }
    }

    private fun updateVictoryOutro() {
        stateProgress = (stateTimer / OUTRO_SECONDS).coerceIn(0f, 1f)
        players.filter { it.isAlive }.forEach { it.animationState = BattleAnimationState.IDLE }
        cameraTarget = BattleVector3(-1.2f, 1.0f, 0.35f)
        cameraFov = 40f
    }

    private fun updateDefeatOutro() {
        stateProgress = (stateTimer / OUTRO_SECONDS).coerceIn(0f, 1f)
        players.forEach { it.animationState = BattleAnimationState.DEFEAT }
        cameraTarget = BattleVector3(-1.2f, 0.85f, 0.35f)
        cameraFov = 42f
    }

    private fun advanceToNextTurn() {
        if (turnQueue.isEmpty()) buildTurnQueue()
        while (turnQueue.isNotEmpty()) {
            val candidateId = turnQueue.removeFirst()
            val candidate = findActor(candidateId)
            if (candidate != null && candidate.isAlive) {
                activeActorId = candidate.id
                selectedTargetId = null
                battleMenu.reset()
                if (candidate.isPlayerTeam) {
                    transitionTo(BattleState.PLAYER_MENU)
                } else {
                    transitionTo(BattleState.ENEMY_PRE_ATTACK_WINDUP)
                }
                return
            }
        }
        turnsTaken += 1
        buildTurnQueue()
        advanceToNextTurn()
    }

    private fun buildTurnQueue() {
        turnQueue.clear()
        players.filter { it.isAlive }.forEach { turnQueue.addLast(it.id) }
        enemies.filter { it.isAlive }.forEach { turnQueue.addLast(it.id) }
    }

    private fun transitionTo(nextState: BattleState) {
        currentState = nextState
        stateTimer = 0f
        stateProgress = 0f
    }

    private fun findActor(id: String): BattleActor? {
        return players.firstOrNull { it.id == id } ?: enemies.firstOrNull { it.id == id }
    }

    private fun homePositionFor(actor: BattleActor): BattleVector3 {
        val index = if (actor.isPlayerTeam) players.indexOfFirst { it.id == actor.id } else enemies.indexOfFirst { it.id == actor.id }
        return if (actor.isPlayerTeam) playerHomePosition(max(0, index)) else enemyHomePosition(max(0, index))
    }

    private fun playerHomePosition(index: Int): BattleVector3 {
        return BattleVector3(-1.75f - index * 0.45f, 0.22f, 0.68f + index * 0.16f)
    }

    private fun enemyHomePosition(index: Int): BattleVector3 {
        return BattleVector3(1.55f + index * 0.5f, 0.24f, 0.7f + index * 0.16f)
    }

    private fun playerIntroPosition(index: Int): BattleVector3 {
        return BattleVector3(-4.2f - index * 0.2f, 0.22f, 0.68f + index * 0.16f)
    }

    private fun enemyIntroPosition(index: Int): BattleVector3 {
        return BattleVector3(4.1f + index * 0.2f, 0.24f, 0.7f + index * 0.16f)
    }

    private fun arcLerp(start: BattleVector3, end: BattleVector3, amount: Float, arcHeight: Float): BattleVector3 {
        val t = amount.coerceIn(0f, 1f)
        val base = start.lerpTo(end, t)
        val arc = 4f * t * (1f - t) * arcHeight
        return base.copy(y = base.y + arc)
    }

    private fun easeOutCubic(value: Float): Float {
        val t = value.coerceIn(0f, 1f) - 1f
        return t * t * t + 1f
    }

    private fun easeOutQuad(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t)
    }

    private fun easeInOut(value: Float): Float {
        val t = value.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun Int.floorMod(divisor: Int): Int {
        return ((this % divisor) + divisor) % divisor
    }

    companion object {
        private const val INTRO_SECONDS = 1.5f
        private const val ENEMY_PRE_ATTACK_FOCUS_SECONDS = 0.6f
        private const val ENEMY_PRE_ATTACK_WINDUP_SECONDS = 0.5f
        private const val ENEMY_PRE_ATTACK_TOTAL_SECONDS = ENEMY_PRE_ATTACK_FOCUS_SECONDS + ENEMY_PRE_ATTACK_WINDUP_SECONDS
        private const val PLAYER_POST_ATTACK_DELAY_SECONDS = 0.4f
        private const val ENEMY_POST_ATTACK_DELAY_SECONDS = 0.8f
        private const val CHECK_STATUS_SECONDS = 0.25f
        private const val OUTRO_SECONDS = 1.2f
        private const val MAX_STAR_POINTS = 100
        private const val ENEMY_DEFEAT_SECONDS = 0.72f
        private val STAR_POINT_HUD_WORLD_POSITION = BattleVector3(3.92f, 2.9f, 0.08f)
        private const val JUMP_APPROACH_SECONDS = 0.34f
        private const val JUMP_FIRST_ARC_SECONDS = 0.56f
        private const val JUMP_FIRST_ARC_HEIGHT = 0.9f
        private const val JUMP_CONTACT_SETTLE_SECONDS = 0.12f
        private const val JUMP_GOOD_START_SECONDS = 0.427f
        private const val JUMP_GOOD_END_SECONDS = JUMP_FIRST_ARC_SECONDS
        private const val JUMP_PERFECT_START_SECONDS = 0.505f
        private const val JUMP_PERFECT_END_SECONDS = JUMP_FIRST_ARC_SECONDS
        private const val JUMP_IMPACT_PROGRESS = 0.985f
        private const val JUMP_SECOND_BOUNCE_SECONDS = 0.36f
        private const val JUMP_SECOND_BOUNCE_HEIGHT = 0.48f
        private const val JUMP_RETURN_ARC_SECONDS = 0.42f
        private const val JUMP_RETURN_ARC_HEIGHT = 0.54f
        private const val JUMP_WALK_BACK_SECONDS = 0.34f
        private const val JUMP_TAKEOFF_DISTANCE = 0.58f
        private const val JUMP_STOMP_Y_OFFSET = 0.82f
        private const val JUMP_DAMAGE_PER_HIT = 1
        private const val GUARD_WINDOW_SECONDS = 0.2f
        private const val SUPERGUARD_WINDOW_SECONDS = 0.05f
        private const val GUARD_DAMAGE_REDUCTION = 1
        private const val SUPERGUARD_COUNTER_DAMAGE = 1
        private const val TIMING_INDICATOR_SECONDS = 1f
        private const val NO_ITEMS_NOTICE_SECONDS = 0.95f
        private const val SHIELD_FLASH_SECONDS = 0.28f
        private const val HIT_STOP_SECONDS = 0.11f
        private const val SCREEN_SHAKE_SECONDS = 0.06f
        private const val CAMERA_SHAKE_MAX_OFFSET = 0.1f
        private const val DAMAGE_PARTICLE_BOUNCE_DAMPING = 0.5f
        private const val DAMAGE_PARTICLE_MAX_BOUNCES = 2
        private const val DAMAGE_NUMBER_SPAWN_HEIGHT = 1.5f
        private const val DAMAGE_NUMBER_POP_SECONDS = 0.15f
        private const val DAMAGE_NUMBER_FLOAT_SECONDS = 0.35f
        private const val DAMAGE_NUMBER_LIFETIME_SECONDS = DAMAGE_NUMBER_POP_SECONDS + DAMAGE_NUMBER_FLOAT_SECONDS
        private const val DAMAGE_NUMBER_POP_HEIGHT = 0.5f
        private const val DAMAGE_NUMBER_FLOAT_HEIGHT = 0.2f
        private const val HP_BAR_LERP_SECONDS = 0.3f
        private const val HP_BAR_RECENT_DAMAGE_SECONDS = 1.6f
    }
}

