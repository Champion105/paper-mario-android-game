package com.alijah.myapplication

import kotlin.math.max
import kotlin.math.min

data class CharacterStats(
    val maxHp: Int,
    val currentHp: Int,
    val maxFp: Int,
    val currentFp: Int,
    val maxBp: Int,
    val level: Int,
    val starPoints: Int
) {
    init {
        require(maxHp > 0) { "maxHp must be positive." }
        require(maxFp >= 0) { "maxFp cannot be negative." }
        require(maxBp >= 0) { "maxBp cannot be negative." }
        require(level > 0) { "level must be positive." }
        require(starPoints in 0..99) { "starPoints must stay between 0 and 99." }
        require(currentHp in 0..maxHp) { "currentHp must be between 0 and maxHp." }
        require(currentFp in 0..maxFp) { "currentFp must be between 0 and maxFp." }
    }

    val isDanger: Boolean
        get() = currentHp <= DANGER_HP_THRESHOLD

    val isAlive: Boolean
        get() = currentHp > 0

    fun spendFp(amount: Int): CharacterStats {
        require(amount >= 0) { "FP cost cannot be negative." }
        require(currentFp >= amount) { "Not enough FP." }
        return copy(currentFp = currentFp - amount)
    }

    fun takeDamage(amount: Int): CharacterStats {
        require(amount >= 0) { "Damage cannot be negative." }
        return copy(currentHp = max(0, currentHp - amount))
    }

    fun healHp(amount: Int): CharacterStats {
        require(amount >= 0) { "Healing cannot be negative." }
        return copy(currentHp = min(maxHp, currentHp + amount))
    }

    fun recoverFp(amount: Int): CharacterStats {
        require(amount >= 0) { "FP recovery cannot be negative." }
        return copy(currentFp = min(maxFp, currentFp + amount))
    }

    fun addStarPoints(amount: Int, onLevelUp: LevelUpBonus = LevelUpBonus.HP): CharacterStats {
        require(amount >= 0) { "Star points cannot be negative." }
        var next = this
        var total = starPoints + amount
        while (total >= STAR_POINTS_PER_LEVEL) {
            total -= STAR_POINTS_PER_LEVEL
            next = next.levelUp(onLevelUp)
        }
        return next.copy(starPoints = total)
    }

    private fun levelUp(bonus: LevelUpBonus): CharacterStats {
        return when (bonus) {
            LevelUpBonus.HP -> copy(
                level = level + 1,
                maxHp = maxHp + HP_LEVEL_UP_AMOUNT,
                currentHp = maxHp + HP_LEVEL_UP_AMOUNT,
                currentFp = maxFp
            )
            LevelUpBonus.FP -> copy(
                level = level + 1,
                maxFp = maxFp + FP_LEVEL_UP_AMOUNT,
                currentFp = maxFp + FP_LEVEL_UP_AMOUNT,
                currentHp = maxHp
            )
            LevelUpBonus.BP -> copy(
                level = level + 1,
                maxBp = maxBp + BP_LEVEL_UP_AMOUNT,
                currentHp = maxHp,
                currentFp = maxFp
            )
        }
    }

    companion object {
        private const val DANGER_HP_THRESHOLD = 5
        private const val STAR_POINTS_PER_LEVEL = 100
        private const val HP_LEVEL_UP_AMOUNT = 5
        private const val FP_LEVEL_UP_AMOUNT = 5
        private const val BP_LEVEL_UP_AMOUNT = 3
    }
}

enum class LevelUpBonus {
    HP,
    FP,
    BP
}

data class StatModifier(
    val attackBonus: Int = 0,
    val defenseBonus: Int = 0
)

data class BadgeAction(
    val id: String,
    val displayName: String,
    val fpCost: Int,
    val basePower: Int,
    val actionCommand: ActionCommand
)

abstract class Badge(
    val id: String,
    val displayName: String,
    val bpCost: Int
) {
    init {
        require(bpCost >= 0) { "Badge BP cost cannot be negative." }
    }

    open fun passiveModifier(stats: CharacterStats): StatModifier = StatModifier()

    open fun attackModifier(actionId: String, stats: CharacterStats): Int = 0

    open fun grantedActions(stats: CharacterStats): List<BadgeAction> = emptyList()
}

class CloseCallBadge : Badge(
    id = "close_call",
    displayName = "Close Call",
    bpCost = 1
) {
    override fun passiveModifier(stats: CharacterStats): StatModifier {
        return if (stats.isDanger) StatModifier(defenseBonus = 1) else StatModifier()
    }
}

class PowerJumpBadge : Badge(
    id = "power_jump",
    displayName = "Power Jump",
    bpCost = 1
) {
    override fun grantedActions(stats: CharacterStats): List<BadgeAction> {
        if (stats.currentFp < POWER_JUMP_FP_COST) return emptyList()
        return listOf(
            BadgeAction(
                id = POWER_JUMP_ACTION_ID,
                displayName = "Power Jump",
                fpCost = POWER_JUMP_FP_COST,
                basePower = POWER_JUMP_POWER,
                actionCommand = ActionCommand(
                    successWindowStartMs = 420L,
                    successWindowEndMs = 620L,
                    requiredKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_A,
                    inputType = ActionCommandInputType.KEY,
                    totalDurationMs = 900L
                )
            )
        )
    }

    override fun attackModifier(actionId: String, stats: CharacterStats): Int {
        return if (actionId == POWER_JUMP_ACTION_ID) POWER_JUMP_ATTACK_BONUS else 0
    }

    companion object {
        const val POWER_JUMP_ACTION_ID = "power_jump"
        private const val POWER_JUMP_FP_COST = 2
        private const val POWER_JUMP_POWER = 3
        private const val POWER_JUMP_ATTACK_BONUS = 1
    }
}

class BadgeManager(
    private val equippedBadges: MutableList<Badge> = mutableListOf()
) {
    val equipped: List<Badge>
        get() = equippedBadges.toList()

    fun equippedBp(): Int {
        return equippedBadges.sumOf { it.bpCost }
    }

    fun canEquip(badge: Badge, stats: CharacterStats): Boolean {
        if (equippedBadges.any { it.id == badge.id }) return false
        return equippedBp() + badge.bpCost <= stats.maxBp
    }

    fun equip(badge: Badge, stats: CharacterStats): Boolean {
        if (!canEquip(badge, stats)) return false
        equippedBadges += badge
        return true
    }

    fun unequip(badgeId: String): Boolean {
        return equippedBadges.removeAll { it.id == badgeId }
    }

    fun passiveModifier(stats: CharacterStats): StatModifier {
        return equippedBadges.fold(StatModifier()) { total, badge ->
            val next = badge.passiveModifier(stats)
            StatModifier(
                attackBonus = total.attackBonus + next.attackBonus,
                defenseBonus = total.defenseBonus + next.defenseBonus
            )
        }
    }

    fun attackModifier(actionId: String, stats: CharacterStats): Int {
        return equippedBadges.sumOf { it.attackModifier(actionId, stats) }
    }

    fun grantedActions(stats: CharacterStats): List<BadgeAction> {
        return equippedBadges.flatMap { it.grantedActions(stats) }
    }
}

fun calculateFinalDamage(
    baseAttack: Int,
    actionId: String,
    attackerStats: CharacterStats,
    attackerBadges: BadgeManager,
    enemyDefense: Int
): Int {
    val passiveAttackBonus = attackerBadges.passiveModifier(attackerStats).attackBonus
    val actionAttackBonus = attackerBadges.attackModifier(actionId, attackerStats)
    val rawDamage = baseAttack + passiveAttackBonus + actionAttackBonus - enemyDefense
    return max(0, rawDamage)
}
