package com.alijah.myapplication

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

class PaperRpgPrototypeView(context: Context) : FrameLayout(context) {
    private val renderer = PaperRpgRenderer(context)
    private val surfaceView = PaperRpgSurfaceView(context, renderer)
    private val controlsView = ControlsOverlayView(this, context, renderer)
    private var isGamepadConnected = false
    private var controlsTargetAlpha = 1.0f
    private var keyLeft = false
    private var keyRight = false
    private var keyUp = false
    private var keyDown = false

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val inputDeviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = checkGamepadConnection()
        override fun onInputDeviceRemoved(deviceId: Int) = checkGamepadConnection()
        override fun onInputDeviceChanged(deviceId: Int) = checkGamepadConnection()
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        addView(surfaceView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(controlsView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        checkGamepadConnection()
        inputManager.registerInputDeviceListener(inputDeviceListener, null)
    }

    private fun checkGamepadConnection() {
        val deviceIds = InputDevice.getDeviceIds()
        var foundGamepad = false
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id)
            if (device != null && !device.isVirtual) {
                val sources = device.sources
                if (((sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD) ||
                    ((sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)) {
                    foundGamepad = true
                    break
                }
            }
        }
        isGamepadConnected = foundGamepad
        setControlsTargetAlpha(if (isGamepadConnected) 0.0f else 1.0f)
    }

    private fun setControlsTargetAlpha(target: Float) {
        if (controlsTargetAlpha == target) return
        controlsTargetAlpha = target
        controlsView.animate()
            .alpha(target)
            .setDuration(150)
            .withEndAction {
                if (target == 0.0f) controlsView.visibility = View.GONE
            }
            .withStartAction {
                if (target > 0.0f) controlsView.visibility = View.VISIBLE
            }
            .start()
    }

    fun onTouchDetected() {
        if (isGamepadConnected || controlsTargetAlpha < 1.0f) {
            isGamepadConnected = false
            setControlsTargetAlpha(1.0f)
        }
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE) {
            
            if (!isGamepadConnected || controlsTargetAlpha > 0.0f) {
                isGamepadConnected = true
                setControlsTargetAlpha(0.0f)
            }

            renderer.keyboardX = event.getAxisValue(MotionEvent.AXIS_X)
            renderer.keyboardY = event.getAxisValue(MotionEvent.AXIS_Y)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        surfaceView.onResume()
    }

    override fun onDetachedFromWindow() {
        inputManager.unregisterInputDeviceListener(inputDeviceListener)
        surfaceView.onPause()
        super.onDetachedFromWindow()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if ((!isGamepadConnected || controlsTargetAlpha > 0.0f) && 
            event.device?.sources?.let { it and InputDevice.SOURCE_GAMEPAD } != 0) {
            isGamepadConnected = true
            setControlsTargetAlpha(0.0f)
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> {
                if (renderer.handleBattleMenuDirection(-1)) return true
                keyLeft = true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> {
                if (renderer.handleBattleMenuDirection(1)) return true
                keyRight = true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> keyUp = true
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> keyDown = true
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A -> renderer.handleActionButton(event.eventTime, "A")
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_B -> renderer.handleActionButton(event.eventTime, "B")
            else -> return super.onKeyDown(keyCode, event)
        }
        syncKeyboardInput()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> keyLeft = false
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> keyRight = false
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> keyUp = false
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> keyDown = false
            else -> return super.onKeyUp(keyCode, event)
        }
        syncKeyboardInput()
        return true
    }

    private fun syncKeyboardInput() {
        renderer.keyboardX = (if (keyRight) 1f else 0f) - (if (keyLeft) 1f else 0f)
        renderer.keyboardY = (if (keyDown) 1f else 0f) - (if (keyUp) 1f else 0f)
    }
}

private class PaperRpgSurfaceView(
    context: Context,
    renderer: PaperRpgRenderer
) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }
}

private class ControlsOverlayView(
    private val parentView: PaperRpgPrototypeView,
    context: Context,
    private val renderer: PaperRpgRenderer
) : android.view.View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        strokeWidth = 3f
    }
    private var joystickPointerId = MotionEvent.INVALID_POINTER_ID
    private var baseX = 0f
    private var baseY = 0f
    private var knobX = 0f
    private var knobY = 0f

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (baseX == 0f) {
            baseX = width * 0.17f
            baseY = height * 0.78f
            knobX = baseX
            knobY = baseY
        }

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(64, 255, 255, 255)
        canvas.drawCircle(baseX, baseY, width * 0.085f, paint)
        paint.color = Color.argb(125, 35, 40, 43)
        canvas.drawCircle(knobX, knobY, width * 0.04f, paint)

        val jumpX = width * 0.86f
        val jumpY = height * 0.78f
        val jumpRadius = width * 0.062f
        paint.color = Color.argb(76, 255, 255, 255)
        canvas.drawCircle(jumpX, jumpY, jumpRadius, paint)
        paint.style = Paint.Style.STROKE
        paint.color = Color.argb(150, 35, 40, 43)
        canvas.drawCircle(jumpX, jumpY, jumpRadius, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(220, 35, 40, 43)
        paint.textSize = jumpRadius * 0.9f
        canvas.drawText("A", jumpX, jumpY + jumpRadius * 0.31f, paint)

        postInvalidateOnAnimation()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parentView.onTouchDetected()
        requestFocus()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val x = event.getX(index)
                val y = event.getY(index)
                if (isJumpHit(x, y)) {
                    renderer.handleActionButton(event.eventTime, "A")
                } else if (x < width * 0.48f && y > height * 0.5f) {
                    joystickPointerId = event.getPointerId(index)
                    updateJoystick(x, y)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(joystickPointerId)
                if (index >= 0) {
                    updateJoystick(event.getX(index), event.getY(index))
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val liftedId = event.getPointerId(event.actionIndex)
                if (liftedId == joystickPointerId || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                    joystickPointerId = MotionEvent.INVALID_POINTER_ID
                    knobX = baseX
                    knobY = baseY
                    renderer.touchX = 0f
                    renderer.touchY = 0f
                }
            }
        }
        return true
    }

    private fun updateJoystick(x: Float, y: Float) {
        if (baseX == 0f) {
            baseX = width * 0.17f
            baseY = height * 0.78f
        }
        val radius = width * 0.075f
        val dx = x - baseX
        val dy = y - baseY
        val length = max(1f, hypot(dx, dy))
        val limited = min(radius, length)
        knobX = baseX + dx / length * limited
        knobY = baseY + dy / length * limited
        var inputX = (dx / radius).coerceIn(-1f, 1f)
        var inputY = (dy / radius).coerceIn(-1f, 1f)
        val inputLength = hypot(inputX, inputY)
        if (inputLength > 1f) {
            inputX /= inputLength
            inputY /= inputLength
        }
        renderer.touchX = inputX
        renderer.touchY = inputY
    }

    private fun isJumpHit(x: Float, y: Float): Boolean {
        return hypot(x - width * 0.86f, y - height * 0.78f) <= width * 0.09f
    }
}

private data class BattleCameraTarget(
    val eye: BattleVector3,
    val lookAt: BattleVector3,
    val fov: Float
)

private class PaperRpgRenderer(private val context: Context) : GLSurfaceView.Renderer {
    @Volatile var touchX = 0f
    @Volatile var touchY = 0f
    @Volatile var keyboardX = 0f
    @Volatile var keyboardY = 0f

    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)

    private var program = 0
    private var positionHandle = 0
    private var texCoordHandle = 0
    private var mvpHandle = 0
    private var tintHandle = 0
    private var textureHandle = 0
    private var keyWhiteHandle = 0

    private var colorProgram = 0
    private var colorPositionHandle = 0
    private var colorMvpHandle = 0
    private var colorHandle = 0

    private var floorTexture = 0
    private var heroTexture = 0
    private var playerFaceTexture = 0
    private var heartTexture = 0
    private var enemyTexture = 0
    private val textureAspects = mutableMapOf<Int, Float>()
    private val menuLabelTextures = mutableMapOf<String, Int>()
    private val menuIconTextures = mutableMapOf<BattleMenuItemType, Int>()
    private val menuBubbleTextures = mutableMapOf<BattleMenuItemType, Int>()
    private val damageNumberTextures = mutableMapOf<Int, Int>()
    private val hudTextTextures = mutableMapOf<String, Int>()
    private val wallTextures = IntArray(4)
    private var renderAlpha = 1f

    private var viewportWidth = 1
    private var viewportHeight = 1
    private var lastFrameNanos = 0L
    private var playerX = 0f
    private var playerZ = 2.15f
    private var cameraX = 0f
    private var cameraZ = 2.15f
    private var battleCameraEye = BattleVector3(0f, 1.5f, 6.2f)
    private var battleCameraLook = BattleVector3(0f, 0.95f, 0.25f)
    private var battleCameraFov = 44f
    private var cameraStageAmount = 0f
    private var cameraSideOffset = 0f
    private var cameraFocusBuilding: Building? = null
    private var facing = 1f
    private var walkClock = 0f
    private var jumpHeight = 0f
    private var jumpVelocity = 0f
    private var activeDoorTravel: DoorTravel? = null
    private var lastInputX = 0f
    private var lastInputY = 0f
    private var battleState = BattlePresentationState.OVERWORLD
    private var battleTimer = 0f
    private var battleContact = BattleContact.NEUTRAL
    private var battleContactX = 0f
    private var battleContactZ = 0f
    private var battleReturnTimer = 0f
    private var battleMenuRepeatTimer = 0f
    private val hapticFeedback = BattleHapticFeedback(context)
    private val battleManager = BattleManager()
    private val enemy = Enemy(1.35f, 1.2f, 0.9f)

    init {
        battleManager.feedbackListener = object : BattleFeedbackListener {
            override fun onBattleHaptic(type: BattleHapticType) {
                hapticFeedback.play(type)
            }
        }
    }

    private val buildings = listOf(
        Building(-4.25f, -3.85f, 3.1f, 4.35f, 2.05f, 0, floatArrayOf(0.58f, 0.18f, 0.14f, 1f)),
        Building(-0.8f, -4.3f, 3.35f, 4.5f, 2.35f, 1, floatArrayOf(0.18f, 0.42f, 0.48f, 1f)),
        Building(2.95f, -4.65f, 3.75f, 4.7f, 2.55f, 2, floatArrayOf(0.24f, 0.42f, 0.48f, 1f)),
        Building(6.35f, -4.0f, 3.05f, 4.4f, 2.15f, 3, floatArrayOf(0.58f, 0.33f, 0.13f, 1f))
    )

    fun handleActionButton(inputTime: Long, buttonType: String) {
        if (battleState == BattlePresentationState.STAGE) {
            val timingResult = battleManager.registerBattleInput(inputTime, buttonType)
            if (timingResult != null) return
        }
        if (buttonType == "A") {
            interactOrJump()
        }
    }

    fun handleBattleMenuDirection(direction: Int): Boolean {
        if (battleState != BattlePresentationState.STAGE) return false
        return battleManager.cycleBattleMenu(direction)
    }

    fun interactOrJump() {
        if (battleState == BattlePresentationState.STAGE && battleManager.currentState == BattleState.PLAYER_MENU) {
            battleManager.confirmSelectedMenuAction()
            return
        }
        if (battleState != BattlePresentationState.OVERWORLD) return
        if (!activateNearestDoor()) {
            jump()
        }
    }

    private fun jump() {
        if (jumpHeight == 0f) {
            jumpVelocity = 4.6f
        }
    }

    private fun activateNearestDoor(): Boolean {
        if (activeDoorTravel != null) return true
        val building = buildings
            .filter { it.isPlayerNearDoor(playerX, playerZ) }
            .minByOrNull { hypot(playerX - it.x, playerZ - it.maxZ) }
            ?: return false
        if (building.doorActivated && building.playerEnteredInterior) {
            building.exitRequested = true
            activeDoorTravel = DoorTravel(
                building = building,
                startX = playerX,
                startZ = playerZ,
                endX = building.x,
                endZ = building.maxZ + DOOR_EXIT_DISTANCE,
                exiting = true
            )
        } else {
            building.doorActivated = true
            building.exitRequested = false
            activeDoorTravel = DoorTravel(
                building = building,
                startX = playerX,
                startZ = playerZ,
                endX = building.x,
                endZ = building.maxZ - DOOR_ENTRY_DISTANCE,
                exiting = false
            )
        }
        return true
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.56f, 0.78f, 0.88f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)

        program = createProgram(TEXTURE_VERTEX_SHADER, TEXTURE_FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
        mvpHandle = GLES20.glGetUniformLocation(program, "u_MvpMatrix")
        tintHandle = GLES20.glGetUniformLocation(program, "u_Tint")
        textureHandle = GLES20.glGetUniformLocation(program, "u_Texture")
        keyWhiteHandle = GLES20.glGetUniformLocation(program, "u_KeyWhite")

        colorProgram = createProgram(COLOR_VERTEX_SHADER, COLOR_FRAGMENT_SHADER)
        colorPositionHandle = GLES20.glGetAttribLocation(colorProgram, "a_Position")
        colorMvpHandle = GLES20.glGetUniformLocation(colorProgram, "u_MvpMatrix")
        colorHandle = GLES20.glGetUniformLocation(colorProgram, "u_Color")

        floorTexture = loadTexture("textures/cobblestone.jpg", true)
        heroTexture = loadTexture("hero_sprite.png", false)
        playerFaceTexture = createPlayerFaceTexture("hero_sprite.png")
        heartTexture = createHeartTexture()
        enemyTexture = loadTexture("enemy_slime.jpg", false)
        wallTextures[0] = loadTexture("textures/brick_red.jpg", true)
        wallTextures[1] = loadTexture("textures/brick_light.jpg", true)
        wallTextures[2] = loadTexture("textures/brick_red.jpg", true)
        wallTextures[3] = loadTexture("textures/brick_light.jpg", true)
        createBattleMenuLabelTextures()
        loadBattleMenuTextures()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = max(1, width)
        viewportHeight = max(1, height)
        GLES20.glViewport(0, 0, viewportWidth, viewportHeight)
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 48f, aspect, 0.1f, 80f)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val deltaSeconds = if (lastFrameNanos == 0L) 1f / 60f else ((now - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
        lastFrameNanos = now
        update(deltaSeconds)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (battleState == BattlePresentationState.STAGE || battleState == BattlePresentationState.BATTLE_FADE_OUT) {
            updateBattleCamera()
            drawBattleStage()
            drawBattleTransitionOverlay()
        } else {
            updateCamera()
            drawSkyBackdrop()
            drawGround()
            drawBuildings()
            drawEnemy()
            drawHero()
            drawBattleTransitionOverlay()
        }
    }

    private fun update(deltaSeconds: Float) {
        if (battleState != BattlePresentationState.OVERWORLD) {
            updateBattleSequence(deltaSeconds)
            return
        }

        var inputX = touchX + keyboardX
        var inputY = touchY + keyboardY
        val inputLength = hypot(inputX, inputY)
        if (inputLength > 1f) {
            inputX /= inputLength
            inputY /= inputLength
        }
        val cameraInput = mapInputToCamera(inputX, inputY)
        val worldInputX = cameraInput[0]
        val worldInputZ = cameraInput[1]
        lastInputX = worldInputX
        lastInputY = worldInputZ

        val travel = activeDoorTravel
        val moving = travel != null || abs(inputX) > 0.05f || abs(inputY) > 0.05f
        if (travel != null) {
            updateDoorTravel(travel, deltaSeconds)
        } else if (moving) {
            val speed = 2.35f
            val proposedX = (playerX + worldInputX * speed * deltaSeconds).coerceIn(-6.4f, 7.7f)
            val proposedZ = (playerZ + worldInputZ * speed * deltaSeconds).coerceIn(-7.4f, 3.4f)
            moveWithCollision(proposedX, proposedZ)
            if (abs(inputX) > 0.08f || abs(worldInputX) > 0.08f) {
                facing = if (cameraStageAmount > 0.5f && cameraFocusBuilding != null) {
                    if (inputX >= 0f) 1f else -1f
                } else {
                    if (worldInputX >= 0f) 1f else -1f
                }
            }
            walkClock += deltaSeconds * 10f
        } else {
            walkClock += deltaSeconds * 3f
        }

        if (jumpHeight > 0f || jumpVelocity > 0f) {
            jumpVelocity -= 12f * deltaSeconds
            jumpHeight += jumpVelocity * deltaSeconds
            if (jumpHeight < 0f) {
                jumpHeight = 0f
                jumpVelocity = 0f
            }
        }

        updateEnemy(deltaSeconds)
        checkEnemyContact()

        var activeBuilding: Building? = null
        var strongestOpen = 0f
        buildings.forEach { building ->
            if (building.doorActivated && building.isPlayerInsideHouse(playerX, playerZ)) {
                building.playerEnteredInterior = true
            }
            if (building.doorActivated && building.exitRequested && activeDoorTravel?.building !== building && playerZ > building.maxZ + 0.6f) {
                building.resetDoorState()
            } else if (building.doorActivated && !building.playerEnteredInterior && !building.isPlayerInTrigger(playerX, playerZ)) {
                building.resetDoorState()
            }
            val target = if (building.doorActivated && (building.isPlayerInTrigger(playerX, playerZ) || activeDoorTravel?.building === building)) 1f else 0f
            building.openProgress = moveToward(
                building.openProgress,
                target,
                deltaSeconds / BUILDING_OPEN_DURATION_SECONDS
            )
            if (building.openProgress > strongestOpen) {
                strongestOpen = building.openProgress
                activeBuilding = building
            }
        }

        cameraStageAmount += (strongestOpen - cameraStageAmount) * min(1f, deltaSeconds * 3.6f)
        cameraFocusBuilding = activeBuilding
        val active = activeBuilding
        val sideTarget = if (active != null && cameraStageAmount > 0.02f) {
            DOLLHOUSE_CAMERA_SIDE_DIRECTION
        } else {
            0f
        }
        cameraSideOffset += (sideTarget - cameraSideOffset) * min(1f, deltaSeconds * 3.2f)

        val targetCameraX = if (active != null) {
            lerp(playerX, active.x, cameraStageAmount)
        } else {
            playerX
        }
        val targetCameraZ = if (active != null) {
            lerp(playerZ, active.z, cameraStageAmount)
        } else {
            playerZ
        }
        cameraX += (targetCameraX - cameraX) * min(1f, deltaSeconds * 4.5f)
        cameraZ += (targetCameraZ - cameraZ) * min(1f, deltaSeconds * 4.5f)
    }

    private fun mapInputToCamera(inputX: Float, inputY: Float): FloatArray {
        val sideAmount = if (cameraFocusBuilding != null) smoothStep(cameraStageAmount) else 0f
        if (sideAmount <= 0.001f) return floatArrayOf(inputX, inputY)

        val sideWorldX = inputY * DOLLHOUSE_CAMERA_SIDE_DIRECTION
        val sideWorldZ = inputX * -DOLLHOUSE_CAMERA_SIDE_DIRECTION
        return floatArrayOf(
            lerp(inputX, sideWorldX, sideAmount),
            lerp(inputY, sideWorldZ, sideAmount)
        )
    }

    private fun updateEnemy(deltaSeconds: Float) {
        if (!enemy.isActive) return
        enemy.x += enemy.direction * ENEMY_PATROL_SPEED * deltaSeconds
        if (enemy.x > enemy.centerX + enemy.range) {
            enemy.x = enemy.centerX + enemy.range
            enemy.direction = -1f
        } else if (enemy.x < enemy.centerX - enemy.range) {
            enemy.x = enemy.centerX - enemy.range
            enemy.direction = 1f
        }
        enemy.walkClock += deltaSeconds * 7f
    }

    private fun checkEnemyContact() {
        if (!enemy.isActive) return
        if (hypot(playerX - enemy.x, playerZ - enemy.z) > ENEMY_CONTACT_RADIUS) return
        battleContact = when {
            jumpHeight > 0.16f -> BattleContact.PLAYER_FIRST_STRIKE
            abs(lastInputX) + abs(lastInputY) < 0.08f -> BattleContact.ENEMY_FIRST_STRIKE
            lastInputZTowardEnemy() -> BattleContact.PLAYER_FIRST_STRIKE
            else -> BattleContact.NEUTRAL
        }
        battleContactX = (playerX + enemy.x) * 0.5f
        battleContactZ = (playerZ + enemy.z) * 0.5f
        battleTimer = 0f
        battleManager.startBattle(
            playerParty = listOf(
                BattleActor(
                    id = "hero",
                    name = "Hero",
                    maxHp = 20,
                    currentHp = 20,
                    baseAttack = 3,
                    baseDefense = 0,
                    isPlayerTeam = true,
                    position = BattleVector3(-1.75f, 0.22f, 0.68f)
                )
            ),
            enemyParty = listOf(
                BattleActor(
                    id = "slime",
                    name = "Drop Slime",
                    maxHp = 5,
                    currentHp = 5,
                    baseAttack = 2,
                    baseDefense = 0,
                    isPlayerTeam = false,
                    position = BattleVector3(1.55f, 0.24f, 0.7f),
                    xpReward = 3
                )
            ),
            advantage = battleContact.toBattleAdvantage()
        )
        battleState = BattlePresentationState.HIT_FREEZE
    }

    private fun lastInputZTowardEnemy(): Boolean {
        val toEnemyX = enemy.x - playerX
        val toEnemyZ = enemy.z - playerZ
        return lastInputX * toEnemyX + lastInputY * toEnemyZ > 0.08f
    }

    private fun updateBattleSequence(deltaSeconds: Float) {
        battleTimer += deltaSeconds
        when (battleState) {
            BattlePresentationState.HIT_FREEZE -> {
                if (battleTimer >= HIT_FREEZE_SECONDS) {
                    battleState = BattlePresentationState.STAGE_TRANSITION
                    battleTimer = 0f
                }
            }
            BattlePresentationState.STAGE_TRANSITION -> {
                if (battleTimer >= STAGE_TRANSITION_SECONDS) {
                    battleState = BattlePresentationState.STAGE
                    battleTimer = 0f
                }
            }
            BattlePresentationState.STAGE -> {
                updateBattleMenuAxis(deltaSeconds)
                battleManager.update(deltaSeconds)
                walkClock += deltaSeconds * 4f
                enemy.walkClock += deltaSeconds * 5f
                if (battleManager.currentState == BattleState.VICTORY_OUTRO && battleManager.renderState.stateProgress >= 1f) {
                    battleState = BattlePresentationState.BATTLE_FADE_OUT
                    battleReturnTimer = 0f
                    battleTimer = 0f
                }
            }
            BattlePresentationState.BATTLE_FADE_OUT -> {
                battleReturnTimer += deltaSeconds
                battleManager.update(deltaSeconds)
                if (battleReturnTimer >= BATTLE_RETURN_FADE_SECONDS) {
                    if (battleManager.lastResult?.victory == true) {
                        enemy.isActive = false
                    }
                    battleState = BattlePresentationState.OVERWORLD
                    battleTimer = 0f
                    battleReturnTimer = 0f
                    touchX = 0f
                    touchY = 0f
                    keyboardX = 0f
                    keyboardY = 0f
                }
            }
            BattlePresentationState.OVERWORLD -> Unit
        }
    }

    private fun updateBattleMenuAxis(deltaSeconds: Float) {
        if (battleMenuRepeatTimer > 0f) {
            battleMenuRepeatTimer = (battleMenuRepeatTimer - deltaSeconds).coerceAtLeast(0f)
        }
        if (battleManager.currentState != BattleState.PLAYER_MENU) {
            battleMenuRepeatTimer = 0f
            return
        }
        val axis = (touchX + keyboardX).coerceIn(-1f, 1f)
        if (abs(axis) < 0.45f) {
            battleMenuRepeatTimer = 0f
            return
        }
        if (battleMenuRepeatTimer == 0f && battleManager.cycleBattleMenu(if (axis > 0f) 1 else -1)) {
            battleMenuRepeatTimer = 0.24f
        }
    }

    private fun updateDoorTravel(travel: DoorTravel, deltaSeconds: Float) {
        if (travel.building.openProgress < DOOR_TRAVEL_OPEN_THRESHOLD) {
            facing = if (travel.exiting) 1f else -1f
            walkClock += deltaSeconds * 3f
            return
        }

        travel.progress = min(1f, travel.progress + deltaSeconds / DOOR_TRAVEL_SECONDS)
        val eased = smoothStep(travel.progress)
        playerX = lerp(travel.startX, travel.endX, eased)
        playerZ = lerp(travel.startZ, travel.endZ, eased)
        walkClock += deltaSeconds * 10f
        if (travel.progress >= 1f) {
            if (travel.exiting) {
                travel.building.resetDoorState()
            } else {
                travel.building.playerEnteredInterior = true
            }
            activeDoorTravel = null
        }
    }

    private fun moveWithCollision(proposedX: Float, proposedZ: Float) {
        val radius = 0.28f
        var nextX = proposedX
        var nextZ = proposedZ
        if (collides(nextX, playerZ, radius)) {
            nextX = playerX
        }
        if (collides(nextX, nextZ, radius)) {
            nextZ = playerZ
        }
        playerX = nextX
        playerZ = nextZ
    }

    private fun collides(x: Float, z: Float, radius: Float): Boolean {
        return buildings.any { building ->
            collidesWithPaperBuilding(building, x, z, radius)
        }
    }

    private fun collidesWithPaperBuilding(building: Building, x: Float, z: Float, radius: Float): Boolean {
        val thickness = 0.11f + radius
        val doorHalfWidth = building.doorWidth * 0.5f
        val frontWallCollisionEnabled = building.openProgress < 0.04f
        val doorwayPassable = building.doorActivated &&
            (!building.playerEnteredInterior || building.exitRequested)

        val hitsBack = z > building.minZ - thickness &&
            z < building.minZ + thickness &&
            x > building.minX - radius &&
            x < building.maxX + radius
        val hitsLeft = x > building.minX - thickness &&
            x < building.minX + thickness &&
            z > building.minZ - radius &&
            z < building.maxZ + radius
        val hitsRight = x > building.maxX - thickness &&
            x < building.maxX + thickness &&
            z > building.minZ - radius &&
            z < building.maxZ + radius
        val hitsFrontBand = z > building.maxZ - thickness &&
            z < building.maxZ + thickness
        val inDoorway = x > building.x - doorHalfWidth &&
            x < building.x + doorHalfWidth
        val hitsFrontWall = hitsFrontBand &&
            x > building.minX - radius &&
            x < building.maxX + radius &&
            frontWallCollisionEnabled &&
            !inDoorway
        val hitsClosedDoor = hitsFrontBand &&
            x > building.x - doorHalfWidth - radius &&
            x < building.x + doorHalfWidth + radius &&
            inDoorway &&
            !doorwayPassable

        return hitsBack || hitsLeft || hitsRight || hitsFrontWall || hitsClosedDoor
    }

    private fun updateCamera() {
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, 48f, aspect, 0.1f, 80f)
        val eyeHeight = lerp(2.75f, 2.35f, cameraStageAmount)
        val eyeBack = lerp(5.5f, 4.3f, cameraStageAmount)
        val lookHeight = lerp(0.82f, 1.35f, cameraStageAmount)
        val lookForward = lerp(2.4f, 1.5f, cameraStageAmount)
        val defaultEyeX = cameraX
        val defaultEyeZ = cameraZ + eyeBack
        val defaultLookX = cameraX
        val defaultLookZ = cameraZ - lookForward

        val focus = cameraFocusBuilding
        val sideAmount = if (focus != null) smoothStep(cameraStageAmount) else 0f
        val sideDirection = if (cameraSideOffset < 0f) -1f else 1f
        val sideEyeX = if (focus != null) {
            focus.x + sideDirection * (focus.width * 0.5f + SIDE_CAMERA_DISTANCE)
        } else {
            defaultEyeX
        }
        val sideEyeZ = focus?.z ?: defaultEyeZ
        val sideLookX = focus?.x ?: defaultLookX
        val sideLookZ = focus?.z ?: defaultLookZ

        Matrix.setLookAtM(
            viewMatrix,
            0,
            lerp(defaultEyeX, sideEyeX, sideAmount),
            eyeHeight,
            lerp(defaultEyeZ, sideEyeZ, sideAmount),
            lerp(defaultLookX, sideLookX, sideAmount),
            lookHeight,
            lerp(defaultLookZ, sideLookZ, sideAmount),
            0f,
            1f,
            0f
        )
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
    }

    private fun updateBattleCamera() {
        val renderState = battleManager.renderState
        val shake = renderState.cameraOffset
        val cameraTarget = battleCameraTarget(renderState)
        battleCameraEye = battleCameraEye.lerpTo(cameraTarget.eye, BATTLE_CAMERA_LERP_T)
        battleCameraLook = battleCameraLook.lerpTo(cameraTarget.lookAt, BATTLE_CAMERA_LERP_T)
        battleCameraFov = lerp(battleCameraFov, cameraTarget.fov, BATTLE_CAMERA_LERP_T)
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        Matrix.perspectiveM(projectionMatrix, 0, battleCameraFov, aspect, 0.1f, 80f)
        Matrix.setLookAtM(
            viewMatrix,
            0,
            battleCameraEye.x + shake.x,
            battleCameraEye.y + shake.y,
            battleCameraEye.z,
            battleCameraLook.x + shake.x,
            battleCameraLook.y + shake.y,
            battleCameraLook.z,
            0f,
            1f,
            0f
        )
        Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)
    }

    private fun battleCameraTarget(renderState: BattleRenderState): BattleCameraTarget {
        val player = renderState.actors.firstOrNull { it.isPlayerTeam }
        val enemy = renderState.activeActorId
            ?.let { id -> renderState.actors.firstOrNull { it.id == id && !it.isPlayerTeam } }
            ?: renderState.actors.firstOrNull { !it.isPlayerTeam && it.currentHp > 0 }
        val selectedTarget = renderState.selectedTargetId
            ?.let { id -> renderState.actors.firstOrNull { it.id == id } }

        return when (renderState.state) {
            BattleState.PLAYER_MENU -> {
                val focus = player
                BattleCameraTarget(
                    eye = BattleVector3(focus?.position?.x ?: 0f, 1.65f, 6.2f),
                    lookAt = BattleVector3(focus?.position?.x ?: 0f, 1.55f, focus?.position?.z ?: 0.45f),
                    fov = 58f
                )
            }
            BattleState.PLAYER_ATTACKING -> {
                val focus = selectedTarget ?: player
                val actor = renderState.activeActorId
                    ?.let { id -> renderState.actors.firstOrNull { it.id == id } }
                    ?: player
                val x = actor?.position?.x?.plus(0.5f) ?: -1.0f
                BattleCameraTarget(
                    eye = BattleVector3(x, 1.35f, 4.4f),
                    lookAt = BattleVector3(focus?.position?.x ?: 0f, 0.95f, focus?.position?.z ?: 0.35f),
                    fov = 42f
                )
            }
            BattleState.ENEMY_PRE_ATTACK_WINDUP -> {
                val focus = enemy ?: selectedTarget
                BattleCameraTarget(
                    eye = BattleVector3(focus?.position?.x ?: 0f, 1.35f, 4.6f),
                    lookAt = BattleVector3(focus?.position?.x ?: 0f, 0.95f, focus?.position?.z ?: 0.35f),
                    fov = 43f
                )
            }
            BattleState.ENEMY_ATTACKING -> {
                val actor = enemy
                val target = selectedTarget ?: player
                val midX = if (actor != null && target != null) (actor.position.x + target.position.x) * 0.5f else 0f
                BattleCameraTarget(
                    eye = BattleVector3(midX, 1.5f, 5.2f),
                    lookAt = BattleVector3(midX, 0.95f, 0.55f),
                    fov = 44f
                )
            }
            BattleState.ENEMY_POST_ATTACK_DELAY -> {
                val focus = selectedTarget ?: player
                BattleCameraTarget(
                    eye = BattleVector3(focus?.position?.x ?: 0f, 1.4f, 4.8f),
                    lookAt = BattleVector3(focus?.position?.x ?: 0f, 0.9f, focus?.position?.z ?: 0.35f),
                    fov = 43f
                )
            }
            else -> BattleCameraTarget(
                eye = BattleVector3(0f, 1.5f, 6.2f),
                lookAt = BattleVector3(0f, 0.95f, 0.35f),
                fov = max(renderState.cameraFov, 46f)
            )
        }
    }

    private fun drawSkyBackdrop() {
        drawColorQuad(
            floatArrayOf(
                -20f, 0f, -12f,
                20f, 0f, -12f,
                20f, 8f, -12f,
                -20f, 0f, -12f,
                20f, 8f, -12f,
                -20f, 8f, -12f
            ),
            floatArrayOf(0.72f, 0.9f, 0.92f, 1f)
        )
        drawColorQuad(
            floatArrayOf(
                -20f, 0f, -11.9f,
                20f, 0f, -11.9f,
                20f, -1.4f, -11.9f,
                -20f, 0f, -11.9f,
                20f, -1.4f, -11.9f,
                -20f, -1.4f, -11.9f
            ),
            floatArrayOf(0.46f, 0.63f, 0.55f, 1f)
        )
    }

    private fun drawGround() {
        val vertices = floatArrayOf(
            -12f, 0f, -14f, 0f, 12f,
            12f, 0f, -14f, 12f, 12f,
            12f, 0f, 6f, 12f, 0f,
            -12f, 0f, -14f, 0f, 12f,
            12f, 0f, 6f, 12f, 0f,
            -12f, 0f, 6f, 0f, 0f
        )
        drawTextured(vertices, floorTexture, floatArrayOf(0.9f, 0.9f, 0.84f, 1f))
    }

    private fun drawBattleStage() {
        drawColorQuad(
            floatArrayOf(
                -5.2f, 0f, -1.4f,
                5.2f, 0f, -1.4f,
                5.2f, 0f, 2.2f,
                -5.2f, 0f, -1.4f,
                5.2f, 0f, 2.2f,
                -5.2f, 0f, 2.2f
            ),
            floatArrayOf(0.48f, 0.28f, 0.12f, 1f)
        )
        drawColorQuad(
            floatArrayOf(
                -5.4f, 0.02f, 1.95f,
                5.4f, 0.02f, 1.95f,
                5.4f, 0.02f, 2.35f,
                -5.4f, 0.02f, 1.95f,
                5.4f, 0.02f, 2.35f,
                -5.4f, 0.02f, 2.35f
            ),
            floatArrayOf(0.24f, 0.11f, 0.06f, 1f)
        )
        drawColorQuad(
            quad(-5.4f, 0f, -1.55f, 5.4f, 3.5f, -1.55f),
            floatArrayOf(0.14f, 0.09f, 0.11f, 1f)
        )
        drawColorQuad(quad(-5.35f, 0f, -1.5f, -4.15f, 3.65f, -1.5f), floatArrayOf(0.48f, 0.04f, 0.08f, 1f))
        drawColorQuad(quad(4.15f, 0f, -1.5f, 5.35f, 3.65f, -1.5f), floatArrayOf(0.48f, 0.04f, 0.08f, 1f))
        drawColorQuad(quad(-5.35f, 3.05f, -1.48f, 5.35f, 3.65f, -1.48f), floatArrayOf(0.62f, 0.05f, 0.09f, 1f))

        drawSpotlight(-1.7f)
        drawSpotlight(1.7f)
        drawAudienceDots()

        val intro = smoothStep((battleTimer / 0.45f).coerceIn(0f, 1f))
        val playerHitDrop = if (battleContact == BattleContact.ENEMY_FIRST_STRIKE) (1f - intro) * 0.28f else 0f
        val enemyHitHop = if (battleContact == BattleContact.PLAYER_FIRST_STRIKE) sin(battleTimer * 22f) * 0.08f else 0f
        val battleRenderState = battleManager.renderState
        drawBattleCommandWheelLayer(battleRenderState, BattleMenuRenderLayer.BACKGROUND_DECORATION)
        battleRenderState.actors.forEach { actor ->
            val position = actor.position
            if (actor.isPlayerTeam) {
                drawBattleHero(position.x, position.y - playerHitDrop, position.z, actor.facing)
            } else {
                drawBattleEnemy(actor, enemyHitHop)
            }
        }
        drawBattleCommandWheelLayer(battleRenderState, BattleMenuRenderLayer.FOREGROUND)
        drawLocalizedBattleHuds(battleRenderState)
        drawDamageParticles(battleRenderState.damageParticles)
        drawExpFlyParticles(battleRenderState.expParticles)
        if (battleRenderState.isShieldActive) {
            val defender = battleRenderState.selectedTargetId
                ?.let { id -> battleRenderState.actors.firstOrNull { it.id == id } }
                ?: battleRenderState.actors.firstOrNull { it.isPlayerTeam }
            if (defender != null) drawShieldCue(defender.position)
        }
        drawBattleStatusAccent()
        drawBattleHud()
    }

    private fun drawSpotlight(x: Float) {
        drawColorQuad(
            floatArrayOf(
                x - 0.25f, 2.95f, -1.46f,
                x + 0.25f, 2.95f, -1.46f,
                x + 1.15f, 0.04f, 0.15f,
                x - 0.25f, 2.95f, -1.46f,
                x + 1.15f, 0.04f, 0.15f,
                x - 1.15f, 0.04f, 0.15f
            ),
            floatArrayOf(1f, 0.92f, 0.58f, 0.18f)
        )
    }

    private fun drawAudienceDots() {
        for (i in 0 until 10) {
            val x = -4.4f + i * 0.95f
            val y = 0.18f + (i % 2) * 0.14f
            drawColorQuad(
                quad(x - 0.1f, y, 2.32f, x + 0.1f, y + 0.2f, 2.32f),
                floatArrayOf(0.08f + (i % 3) * 0.08f, 0.08f, 0.12f + (i % 4) * 0.07f, 1f)
            )
        }
    }

    private fun drawBattleStatusAccent() {
        when (battleContact) {
            BattleContact.PLAYER_FIRST_STRIKE -> drawColorQuad(quad(-2.35f, 1.85f, 0.05f, -0.65f, 2.28f, 0.05f), floatArrayOf(1f, 0.84f, 0.18f, 0.9f))
            BattleContact.ENEMY_FIRST_STRIKE -> drawColorQuad(quad(-2.25f, 1.8f, 0.05f, -1.35f, 2.55f, 0.05f), floatArrayOf(0.96f, 0.12f, 0.1f, 0.85f))
            BattleContact.NEUTRAL -> drawColorQuad(quad(-0.8f, 2.04f, 0.05f, 0.8f, 2.28f, 0.05f), floatArrayOf(0.92f, 0.92f, 1f, 0.5f))
        }
    }

    private fun drawBattleHud() {
        val renderState = battleManager.renderState
        drawTopRightBattleHud(renderState)

        val stateColor = when (battleManager.currentState) {
            BattleState.PLAYER_MENU -> floatArrayOf(0.18f, 0.55f, 1f, 0.88f)
            BattleState.PLAYER_ATTACKING, BattleState.PLAYER_RETURN_POST_ATTACK_DELAY -> floatArrayOf(1f, 0.8f, 0.12f, 0.88f)
            BattleState.ENEMY_PRE_ATTACK_WINDUP, BattleState.ENEMY_ATTACKING, BattleState.ENEMY_POST_ATTACK_DELAY -> floatArrayOf(0.95f, 0.15f, 0.12f, 0.88f)
            BattleState.VICTORY_OUTRO -> floatArrayOf(0.18f, 0.9f, 0.35f, 0.9f)
            BattleState.DEFEAT_OUTRO -> floatArrayOf(0.45f, 0.08f, 0.08f, 0.9f)
            BattleState.INTRO, BattleState.CHECK_BATTLE_STATUS -> floatArrayOf(0.92f, 0.92f, 1f, 0.55f)
        }
        drawColorQuad(quad(-0.65f, 2.82f, 0.09f, 0.65f, 3.04f, 0.09f), stateColor)

        val actionProgress = renderState.actionProgress
        if (actionProgress > 0f || renderState.isActionWindowActive) {
            drawColorQuad(quad(-0.72f, 2.58f, 0.09f, 0.72f, 2.68f, 0.09f), floatArrayOf(0.05f, 0.05f, 0.07f, 0.78f))
            drawColorQuad(
                quad(-0.68f, 2.6f, 0.1f, lerp(-0.68f, 0.68f, actionProgress), 2.66f, 0.1f),
                floatArrayOf(1f, 0.86f, 0.18f, 0.92f)
            )
        }
        if (renderState.isActionWindowActive) {
            drawColorQuad(quad(0.82f, 2.56f, 0.11f, 1.08f, 2.72f, 0.11f), floatArrayOf(0.18f, 1f, 0.24f, 0.92f))
        }
    }

    private fun drawTopRightBattleHud(renderState: BattleRenderState) {
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val xOffset = (aspect - 1.777f).coerceAtLeast(0f) * 3.5f
        val spLabel = "STAR ${renderState.playerStarPoints}/${renderState.maxStarPoints}"
        val x0 = 2.25f + xOffset
        val x1 = 4.75f + xOffset
        drawColorQuad(quad(x0, 2.62f, 0.1f, x1, 2.92f, 0.1f), floatArrayOf(0.08f, 0.07f, 0.09f, 0.82f))
        drawColorQuad(quad(x0 + 0.1f, 2.62f, 0.11f, x1 - 0.13f, 2.77f, 0.11f), floatArrayOf(0.1f, 0.13f, 0.22f, 0.92f))
        val spAmount = (renderState.playerStarPoints.toFloat() / renderState.maxStarPoints.toFloat()).coerceIn(0f, 1f)
        val fillEnd = lerp(x0 + 0.13f, x1 - 0.16f, spAmount)
        drawColorQuad(quad(x0 + 0.13f, 2.65f, 0.12f, fillEnd, 2.74f, 0.12f), floatArrayOf(0.95f, 0.76f, 0.18f, 0.96f))
        drawHudText(spLabel, x0 + 0.17f, 2.69f, 0.13f, 1.16f, 0.16f)
    }

    private fun drawBattleTransitionOverlay() {
        when (battleState) {
            BattlePresentationState.HIT_FREEZE -> {
                val t = (battleTimer / HIT_FREEZE_SECONDS).coerceIn(0f, 1f)
                val flash = when (battleContact) {
                    BattleContact.PLAYER_FIRST_STRIKE -> 0.55f * (1f - t)
                    BattleContact.ENEMY_FIRST_STRIKE -> 0.32f * (1f - t)
                    BattleContact.NEUTRAL -> 0.22f * (1f - t)
                }
                drawHitSpark(battleContactX, battleContactZ, t)
                if (flash > 0.02f) {
                    drawColorQuad(quad(-9f, 0.03f, cameraZ - 1.6f, 9f, 5f, cameraZ - 1.6f), floatArrayOf(1f, 1f, 1f, flash))
                }
            }
            BattlePresentationState.STAGE_TRANSITION -> {
                val t = smoothStep((battleTimer / STAGE_TRANSITION_SECONDS).coerceIn(0f, 1f))
                val y0 = 0.02f
                val y1 = 5.2f
                val z = cameraZ - 1.45f
                drawColorQuad(quad(-9f, y0, z, lerp(-9f, 0f, t), y1, z), floatArrayOf(0.04f, 0.02f, 0.04f, 0.82f))
                drawColorQuad(quad(lerp(9f, 0f, t), y0, z, 9f, y1, z), floatArrayOf(0.04f, 0.02f, 0.04f, 0.82f))
                drawColorQuad(quad(-3.5f * t, 1.2f, z + 0.02f, 3.5f * t, 2.0f + t, z + 0.02f), floatArrayOf(0.86f, 0.08f, 0.12f, 0.7f))
            }
            BattlePresentationState.BATTLE_FADE_OUT -> {
                val t = (battleReturnTimer / BATTLE_RETURN_FADE_SECONDS).coerceIn(0f, 1f)
                drawColorQuad(quad(-9f, 0.02f, cameraZ - 1.45f, 9f, 5.2f, cameraZ - 1.45f), floatArrayOf(0.02f, 0.015f, 0.025f, t))
            }
            BattlePresentationState.OVERWORLD, BattlePresentationState.STAGE -> Unit
        }
    }

    private fun drawHitSpark(x: Float, z: Float, t: Float) {
        val size = when (battleContact) {
            BattleContact.PLAYER_FIRST_STRIKE -> lerp(0.28f, 0.74f, t)
            BattleContact.ENEMY_FIRST_STRIKE -> lerp(0.22f, 0.5f, t)
            BattleContact.NEUTRAL -> lerp(0.16f, 0.34f, t)
        }
        val y = 1.05f + sin(t * 3.14159f) * 0.22f
        val color = when (battleContact) {
            BattleContact.PLAYER_FIRST_STRIKE -> floatArrayOf(1f, 0.82f, 0.12f, 1f - t)
            BattleContact.ENEMY_FIRST_STRIKE -> floatArrayOf(1f, 0.12f, 0.1f, 1f - t)
            BattleContact.NEUTRAL -> floatArrayOf(0.9f, 0.94f, 1f, 0.85f - t * 0.55f)
        }
        drawColorQuad(
            floatArrayOf(
                x, y + size, z,
                x + size, y, z,
                x, y - size, z,
                x, y + size, z,
                x, y - size, z,
                x - size, y, z
            ),
            color
        )
    }

    private fun drawBuildings() {
        val active = cameraFocusBuilding
        val sortedBuildings = buildings.sortedBy { it.z }
        if (active == null || cameraStageAmount <= 0.03f) {
            sortedBuildings.forEach { drawBuilding(it, 1f) }
            return
        }

        val fadedAlpha = lerp(1f, SIDE_VIEW_BACKGROUND_BUILDING_ALPHA, smoothStep(cameraStageAmount))
        GLES20.glDepthMask(false)
        sortedBuildings
            .filter { it !== active }
            .forEach { drawBuilding(it, fadedAlpha) }
        GLES20.glDepthMask(true)
        drawBuilding(active, 1f)
    }

    private fun drawBuilding(building: Building, alpha: Float) {
        val wallTexture = wallTextures[building.textureIndex % wallTextures.size]
        renderAlpha = alpha
        drawPaperBuilding(building, wallTexture)
        renderAlpha = 1f
    }

    private fun drawPaperBuilding(building: Building, textureId: Int) {
        val open = building.openProgress

        drawPaperWall(
            building.minX,
            building.minZ,
            building.maxX,
            building.minZ,
            0f,
            building.height,
            textureId,
            floatArrayOf(0.78f, 0.76f, 0.68f, 1f),
            0f,
            0.012f
        )
        drawInteriorBackdrop(building, open)
        if (DOLLHOUSE_CAMERA_SIDE_DIRECTION < 0f) {
            drawHingedCameraSideWall(building, textureId, open, -1f)
            drawPaperWall(
                building.maxX,
                building.maxZ,
                building.maxX,
                building.minZ,
                0f,
                building.height,
                textureId,
                floatArrayOf(0.72f, 0.7f, 0.64f, 1f),
                -0.012f,
                0f
            )
        } else {
            drawPaperWall(
                building.minX,
                building.minZ,
                building.minX,
                building.maxZ,
                0f,
                building.height,
                textureId,
                floatArrayOf(0.82f, 0.8f, 0.72f, 1f),
                0.012f,
                0f
            )
            drawHingedCameraSideWall(building, textureId, open, 1f)
        }
        drawInteriorRightBackdrop(building, open)

        drawHingedFrontWall(building, textureId, open)
        drawOpeningArrow(building, open)
        drawPaperRoof(building, textureId, open)
        drawPaperWallEdges(building, open)
    }

    private fun drawPaperWall(
        x0: Float,
        z0: Float,
        x1: Float,
        z1: Float,
        y0: Float,
        y1: Float,
        textureId: Int,
        tint: FloatArray,
        normalOffsetX: Float,
        normalOffsetZ: Float
    ) {
        if (abs(x1 - x0) < 0.01f && abs(z1 - z0) < 0.01f) return
        val length = hypot(x1 - x0, z1 - z0)
        val height = y1 - y0
        val vertices = floatArrayOf(
            x0 + normalOffsetX, y0, z0 + normalOffsetZ, 0f, height,
            x1 + normalOffsetX, y0, z1 + normalOffsetZ, length, height,
            x1 + normalOffsetX, y1, z1 + normalOffsetZ, length, 0f,
            x0 + normalOffsetX, y0, z0 + normalOffsetZ, 0f, height,
            x1 + normalOffsetX, y1, z1 + normalOffsetZ, length, 0f,
            x0 + normalOffsetX, y1, z0 + normalOffsetZ, 0f, 0f
        )
        drawTextured(vertices, textureId, tint)
    }

    private fun drawHingedCameraSideWall(building: Building, textureId: Int, open: Float, sideSign: Float) {
        val alpha = (1f - open * 0.12f).coerceIn(0f, 1f)
        val vertices = hingedSideTexturedQuad(
            building,
            building.minZ,
            0f,
            building.maxZ,
            building.height,
            open,
            sideSign
        )
        drawTextured(vertices, textureId, floatArrayOf(0.82f, 0.8f, 0.72f, alpha))
        drawHingedSideEdges(building, open, sideSign, alpha)
    }

    private fun hingedSideTexturedQuad(
        building: Building,
        z0: Float,
        y0: Float,
        z1: Float,
        y1: Float,
        open: Float,
        sideSign: Float
    ): FloatArray {
        val p0 = sideHingePoint(building, z0, y0, open, sideSign)
        val p1 = sideHingePoint(building, z1, y0, open, sideSign)
        val p2 = sideHingePoint(building, z1, y1, open, sideSign)
        val p3 = sideHingePoint(building, z0, y1, open, sideSign)
        val u1 = abs(z1 - z0)
        val v1 = abs(y1 - y0)
        return floatArrayOf(
            p0[0], p0[1], p0[2], 0f, v1,
            p1[0], p1[1], p1[2], u1, v1,
            p2[0], p2[1], p2[2], u1, 0f,
            p0[0], p0[1], p0[2], 0f, v1,
            p2[0], p2[1], p2[2], u1, 0f,
            p3[0], p3[1], p3[2], 0f, 0f
        )
    }

    private fun drawHingedSideEdges(building: Building, open: Float, sideSign: Float, alpha: Float) {
        val points = floatArrayOf(
            building.minZ, 0f, building.maxZ, 0f,
            building.maxZ, 0f, building.maxZ, building.height,
            building.maxZ, building.height, building.minZ, building.height,
            building.minZ, building.height, building.minZ, 0f
        )
        val world = FloatArray(points.size / 2 * 3)
        var out = 0
        var i = 0
        while (i < points.size) {
            val p = sideHingePoint(building, points[i], points[i + 1], open, sideSign)
            world[out++] = p[0]
            world[out++] = p[1]
            world[out++] = p[2]
            i += 2
        }
        drawLines(world, floatArrayOf(0.04f, 0.035f, 0.03f, alpha))
    }

    private fun drawDoor(building: Building, alpha: Float) {
        val z = building.maxZ + 0.026f
        val halfWidth = building.doorWidth * 0.42f
        val vertices = quad(
            building.x - halfWidth,
            0.02f,
            z,
            building.x + halfWidth,
            building.doorHeight,
            z
        )
        drawColorQuad(vertices, floatArrayOf(0.34f, 0.17f, 0.08f, alpha))
        drawLineLoop(
            floatArrayOf(
                building.x - halfWidth, 0.02f, z + 0.002f,
                building.x + halfWidth, 0.02f, z + 0.002f,
                building.x + halfWidth, building.doorHeight, z + 0.002f,
                building.x - halfWidth, building.doorHeight, z + 0.002f
            ),
            floatArrayOf(0.05f, 0.04f, 0.03f, alpha)
        )
    }

    private fun drawHingedFrontWall(building: Building, textureId: Int, open: Float) {
        val alpha = (1f - open * 0.12f).coerceIn(0f, 1f)
        val vertices = hingedTexturedQuad(
            building,
            building.minX,
            0f,
            building.maxX,
            building.height,
            open,
            0f,
            building.height
        )
        drawTextured(vertices, textureId, floatArrayOf(1.04f, 1f, 0.92f, alpha))
        drawHingedDoor(building, open, alpha)
        drawHingedWindows(building, open, alpha)
        drawHingedFrontEdges(building, open, alpha)
    }

    private fun drawHingedDoor(building: Building, open: Float, alpha: Float) {
        val halfWidth = building.doorWidth * 0.42f
        val vertices = hingedColorQuad(
            building,
            building.x - halfWidth,
            0.02f,
            building.x + halfWidth,
            building.doorHeight,
            open
        )
        drawColorQuad(vertices, floatArrayOf(0.34f, 0.17f, 0.08f, alpha))
    }

    private fun drawHingedWindows(building: Building, open: Float, alpha: Float) {
        val y0 = building.height * 0.58f
        val y1 = y0 + building.height * 0.16f
        val windowWidth = building.width * 0.15f
        val centers = floatArrayOf(
            building.minX + building.width * 0.23f,
            building.maxX - building.width * 0.23f
        )
        centers.forEach { center ->
            val x0 = center - windowWidth * 0.5f
            val x1 = center + windowWidth * 0.5f
            drawColorQuad(
                hingedColorQuad(building, x0, y0, x1, y1, open),
                floatArrayOf(0.72f, 0.92f, 0.92f, alpha)
            )
        }
    }

    private fun drawHingedFrontEdges(building: Building, open: Float, alpha: Float) {
        val points = floatArrayOf(
            building.minX, 0f, building.maxX, 0f,
            building.maxX, 0f, building.maxX, building.height,
            building.maxX, building.height, building.minX, building.height,
            building.minX, building.height, building.minX, 0f
        )
        val world = FloatArray(points.size / 2 * 3)
        var out = 0
        var i = 0
        while (i < points.size) {
            val p = hingePoint(building, points[i], points[i + 1], open)
            world[out++] = p[0]
            world[out++] = p[1]
            world[out++] = p[2]
            i += 2
        }
        drawLines(world, floatArrayOf(0.04f, 0.035f, 0.03f, alpha))
    }

    private fun hingedTexturedQuad(
        building: Building,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        open: Float,
        u0: Float,
        v1: Float
    ): FloatArray {
        val p0 = hingePoint(building, x0, y0, open)
        val p1 = hingePoint(building, x1, y0, open)
        val p2 = hingePoint(building, x1, y1, open)
        val p3 = hingePoint(building, x0, y1, open)
        val u1 = u0 + abs(x1 - x0)
        val v0 = v1 - abs(y1 - y0)
        return floatArrayOf(
            p0[0], p0[1], p0[2], u0, v1,
            p1[0], p1[1], p1[2], u1, v1,
            p2[0], p2[1], p2[2], u1, v0,
            p0[0], p0[1], p0[2], u0, v1,
            p2[0], p2[1], p2[2], u1, v0,
            p3[0], p3[1], p3[2], u0, v0
        )
    }

    private fun hingedColorQuad(
        building: Building,
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        open: Float
    ): FloatArray {
        val p0 = hingeDetailPoint(building, x0, y0, open)
        val p1 = hingeDetailPoint(building, x1, y0, open)
        val p2 = hingeDetailPoint(building, x1, y1, open)
        val p3 = hingeDetailPoint(building, x0, y1, open)
        return floatArrayOf(
            p0[0], p0[1], p0[2],
            p1[0], p1[1], p1[2],
            p2[0], p2[1], p2[2],
            p0[0], p0[1], p0[2],
            p2[0], p2[1], p2[2],
            p3[0], p3[1], p3[2]
        )
    }

    private fun hingePoint(building: Building, x: Float, y: Float, open: Float): FloatArray {
        val eased = smoothStep(open)
        val angle = eased * HALF_PI
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        val z = building.maxZ + WALL_SURFACE_OFFSET + y * sin
        val rotatedY = y * cos + HINGED_WALL_GROUND_CLEARANCE * eased
        return floatArrayOf(x, rotatedY, z)
    }

    private fun hingeDetailPoint(building: Building, x: Float, y: Float, open: Float): FloatArray {
        val eased = smoothStep(open)
        val angle = eased * HALF_PI
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        val z = building.maxZ + WALL_DETAIL_OFFSET + y * sin
        val rotatedY = y * cos + HINGED_WALL_GROUND_CLEARANCE * eased + WALL_DETAIL_LIFT * sin
        return floatArrayOf(x, rotatedY, z)
    }

    private fun sideHingePoint(building: Building, z: Float, y: Float, open: Float, sideSign: Float): FloatArray {
        val eased = smoothStep(open)
        val angle = eased * HALF_PI
        val cos = kotlin.math.cos(angle)
        val sin = kotlin.math.sin(angle)
        val sideX = if (sideSign < 0f) building.minX else building.maxX
        val x = sideX + sideSign * (SIDE_WALL_SURFACE_OFFSET + y * sin)
        val rotatedY = y * cos + HINGED_WALL_GROUND_CLEARANCE * eased
        return floatArrayOf(x, rotatedY, z)
    }

    private fun drawInteriorBackdrop(building: Building, open: Float) {
        if (open <= 0.04f) return
        val alpha = min(1f, open * 1.6f)
        val z = building.minZ + 0.04f
        val trimHeight = 0.18f
        drawColorQuad(
            quad(building.minX + 0.12f, 0.02f, z, building.maxX - 0.12f, trimHeight, z),
            floatArrayOf(0.32f, 0.18f, 0.08f, alpha * 0.75f)
        )
        drawLines(
            floatArrayOf(
                building.minX + 0.1f, trimHeight, z + 0.002f,
                building.maxX - 0.1f, trimHeight, z + 0.002f,
                building.minX + 0.1f, building.height - 0.08f, z + 0.002f,
                building.maxX - 0.1f, building.height - 0.08f, z + 0.002f
            ),
            floatArrayOf(0.07f, 0.055f, 0.04f, alpha)
        )

        val pictureWidth = building.width * 0.32f
        val pictureHeight = building.height * 0.18f
        val pictureX0 = building.x - pictureWidth * 0.5f
        val pictureX1 = building.x + pictureWidth * 0.5f
        val pictureY0 = building.height * 0.48f
        val pictureY1 = pictureY0 + pictureHeight
        drawColorQuad(
            quad(pictureX0, pictureY0, z + 0.003f, pictureX1, pictureY1, z + 0.003f),
            floatArrayOf(0.64f, 0.52f, 0.32f, alpha * 0.82f)
        )
        drawLineLoop(
            floatArrayOf(
                pictureX0, pictureY0, z + 0.006f,
                pictureX1, pictureY0, z + 0.006f,
                pictureX1, pictureY1, z + 0.006f,
                pictureX0, pictureY1, z + 0.006f
            ),
            floatArrayOf(0.08f, 0.055f, 0.03f, alpha)
        )
    }

    private fun drawInteriorRightBackdrop(building: Building, open: Float) {
        if (open <= 0.04f) return
        val alpha = min(1f, open * 1.6f)
        val x = building.maxX - 0.025f
        val z0 = building.minZ + 0.18f
        val z1 = building.maxZ - 0.22f
        val y0 = 0.08f
        val y1 = building.height - 0.16f
        drawColorQuad(
            sideQuad(x, z0, y0, z1, y1),
            floatArrayOf(0.62f, 0.48f, 0.32f, alpha * 0.55f)
        )
        drawLines(
            floatArrayOf(
                x - 0.004f, y0, z0, x - 0.004f, y1, z0,
                x - 0.004f, y0, z1, x - 0.004f, y1, z1,
                x - 0.004f, y1, z0, x - 0.004f, y1, z1,
                x - 0.004f, y0 + (y1 - y0) * 0.45f, z0, x - 0.004f, y0 + (y1 - y0) * 0.45f, z1
            ),
            floatArrayOf(0.08f, 0.055f, 0.035f, alpha)
        )
    }

    private fun drawPaperWindows(building: Building) {
        val z = building.maxZ + 0.03f
        val y0 = building.height * 0.56f
        val y1 = y0 + building.height * 0.16f
        val windowWidth = building.width * 0.15f
        val usableLeft = building.minX + building.width * 0.18f
        val usableRight = building.maxX - building.width * 0.18f
        val centers = floatArrayOf(usableLeft, usableRight)
        centers.forEach { cx ->
            val x0 = cx - windowWidth * 0.5f
            val x1 = cx + windowWidth * 0.5f
            drawColorQuad(quad(x0, y0, z, x1, y1, z), floatArrayOf(0.72f, 0.92f, 0.92f, 1f))
            drawLineLoop(floatArrayOf(x0, y0, z + 0.002f, x1, y0, z + 0.002f, x1, y1, z + 0.002f, x0, y1, z + 0.002f), floatArrayOf(0.04f, 0.05f, 0.04f, 1f))
            drawLines(floatArrayOf((x0 + x1) * 0.5f, y0, z + 0.003f, (x0 + x1) * 0.5f, y1, z + 0.003f, x0, (y0 + y1) * 0.5f, z + 0.003f, x1, (y0 + y1) * 0.5f, z + 0.003f), floatArrayOf(0.04f, 0.05f, 0.04f, 1f))
        }
    }

    private fun drawOpeningArrow(building: Building, open: Float) {
        if (open <= 0.03f) return
        val z = building.maxZ + 0.04f
        val y = building.doorHeight + 0.18f + open * 0.15f
        val half = building.doorWidth * 0.42f
        drawLines(
            floatArrayOf(
                building.x - half, y, z,
                building.x - half - open * 0.42f, y + open * 0.22f, z + open * 0.12f,
                building.x + half, y, z,
                building.x + half + open * 0.42f, y + open * 0.22f, z + open * 0.12f
            ),
            floatArrayOf(0.08f, 0.07f, 0.05f, min(1f, open * 1.5f))
        )
    }

    private fun drawPaperRoof(building: Building, textureId: Int, open: Float) {
        val fold = smoothStep(open)
        val alpha = (1f - fold).coerceIn(0f, 1f)
        if (alpha < 0.04f) return
        val overhang = 0.18f
        val x0 = building.minX - overhang
        val x1 = building.maxX + overhang
        val backwardShift = fold * (building.depth * 1.05f)
        val z0 = building.minZ - overhang - backwardShift
        val z1 = building.maxZ + overhang - backwardShift
        val base = building.height + 0.03f + fold * 0.42f
        val ridge = base + 0.58f * (1f - fold * 0.55f)
        val midX = building.x
        val split = fold * 0.72f
        val tint = floatArrayOf(building.roofTint[0], building.roofTint[1], building.roofTint[2], alpha)

        val left = floatArrayOf(
            x0 - split, base, z1, 0f, 1f,
            midX - split, ridge, z1, 1f, 0f,
            midX - split, ridge, z0, 1f, 1f,
            x0 - split, base, z1, 0f, 1f,
            midX - split, ridge, z0, 1f, 1f,
            x0 - split, base, z0, 0f, 0f
        )
        val right = floatArrayOf(
            midX + split, ridge, z1, 0f, 0f,
            x1 + split, base, z1, 1f, 1f,
            x1 + split, base, z0, 1f, 0f,
            midX + split, ridge, z1, 0f, 0f,
            x1 + split, base, z0, 1f, 0f,
            midX + split, ridge, z0, 0f, 1f
        )
        drawTextured(left, textureId, tint)
        drawTextured(right, textureId, tint)
        drawRoofGable(x0, x1, z1 + 0.01f, base, ridge, midX, tint)
        drawRoofGable(x1, x0, z0 - 0.01f, base, ridge, midX, tint)
        drawLines(
            floatArrayOf(
                x0, base, z1 + 0.014f, midX, ridge, z1 + 0.014f,
                midX, ridge, z1 + 0.014f, x1, base, z1 + 0.014f,
                x0, base, z0 - 0.014f, midX, ridge, z0 - 0.014f,
                midX, ridge, z0 - 0.014f, x1, base, z0 - 0.014f
            ),
            floatArrayOf(0.04f, 0.035f, 0.03f, alpha)
        )
    }

    private fun drawRoofGable(x0: Float, x1: Float, z: Float, base: Float, ridge: Float, midX: Float, tint: FloatArray) {
        drawColor(
            floatArrayOf(
                x0, base, z,
                x1, base, z,
                midX, ridge, z
            ),
            floatArrayOf(tint[0] * 0.72f, tint[1] * 0.72f, tint[2] * 0.72f, tint[3]),
            GLES20.GL_TRIANGLES
        )
    }

    private fun drawPaperWallEdges(building: Building, open: Float) {
        val zBack = building.minZ - 0.02f
        val color = floatArrayOf(0.04f, 0.035f, 0.03f, 1f)
        val lines = mutableListOf<Float>()
        fun add(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
            lines.add(x0)
            lines.add(y0)
            lines.add(z0)
            lines.add(x1)
            lines.add(y1)
            lines.add(z1)
        }

        add(building.minX, 0f, zBack, building.maxX, 0f, zBack)
        add(building.maxX, 0f, zBack, building.maxX, building.height, zBack)
        add(building.minX, building.height, zBack, building.maxX, building.height, zBack)
        add(building.minX, 0f, zBack, building.minX, building.height, zBack)

        val farX = if (DOLLHOUSE_CAMERA_SIDE_DIRECTION < 0f) building.maxX else building.minX
        add(farX, 0f, building.minZ, farX, 0f, building.maxZ)
        add(farX, building.height, building.minZ, farX, building.height, building.maxZ)
        drawLines(lines.toFloatArray(), color)
    }

    private fun drawCuboid(building: Building, textureId: Int) {
        val x0 = building.minX
        val x1 = building.maxX
        val z0 = building.minZ
        val z1 = building.maxZ
        val y0 = 0f
        val y1 = building.height
        val tileX = building.width * 1.15f
        val tileZ = building.depth * 1.15f
        val tileY = building.height * 1.05f
        val vertices = floatArrayOf(
            x0, y0, z1, 0f, tileY, x1, y0, z1, tileX, tileY, x1, y1, z1, tileX, 0f,
            x0, y0, z1, 0f, tileY, x1, y1, z1, tileX, 0f, x0, y1, z1, 0f, 0f,

            x1, y0, z1, 0f, tileY, x1, y0, z0, tileZ, tileY, x1, y1, z0, tileZ, 0f,
            x1, y0, z1, 0f, tileY, x1, y1, z0, tileZ, 0f, x1, y1, z1, 0f, 0f,

            x1, y0, z0, 0f, tileY, x0, y0, z0, tileX, tileY, x0, y1, z0, tileX, 0f,
            x1, y0, z0, 0f, tileY, x0, y1, z0, tileX, 0f, x1, y1, z0, 0f, 0f,

            x0, y0, z0, 0f, tileY, x0, y0, z1, tileZ, tileY, x0, y1, z1, tileZ, 0f,
            x0, y0, z0, 0f, tileY, x0, y1, z1, tileZ, 0f, x0, y1, z0, 0f, 0f,

            x0, y1, z1, 0f, tileZ, x1, y1, z1, tileX, tileZ, x1, y1, z0, tileX, 0f,
            x0, y1, z1, 0f, tileZ, x1, y1, z0, tileX, 0f, x0, y1, z0, 0f, 0f
        )
        drawTextured(vertices, textureId, floatArrayOf(1.04f, 1.0f, 0.92f, 1f))
        drawCuboidEdges(building)
    }

    private fun drawRoof(building: Building, textureId: Int) {
        val overhang = 0.16f
        val x0 = building.minX - overhang
        val x1 = building.maxX + overhang
        val z0 = building.minZ - overhang
        val z1 = building.maxZ + overhang
        val roofBase = building.height + 0.02f
        val ridge = roofBase + 0.48f
        val midX = building.x
        val u = building.width * 1.4f
        val v = building.depth * 1.2f

        val vertices = floatArrayOf(
            x0, roofBase, z1, 0f, v, midX, ridge, z1, u * 0.5f, 0f, x1, roofBase, z1, u, v,
            x1, roofBase, z0, 0f, v, midX, ridge, z0, u * 0.5f, 0f, x0, roofBase, z0, u, v,

            x0, roofBase, z0, 0f, v, midX, ridge, z0, u * 0.5f, 0f, midX, ridge, z1, u * 0.5f, v,
            x0, roofBase, z0, 0f, v, midX, ridge, z1, u * 0.5f, v, x0, roofBase, z1, 0f, 0f,

            midX, ridge, z0, 0f, 0f, x1, roofBase, z0, u * 0.5f, v, x1, roofBase, z1, u * 0.5f, 0f,
            midX, ridge, z0, 0f, 0f, x1, roofBase, z1, u * 0.5f, 0f, midX, ridge, z1, 0f, v
        )
        drawTextured(vertices, textureId, building.roofTint)
        drawRoofEdges(x0, x1, z0, z1, roofBase, ridge, midX)
    }

    private fun drawWindowsAndDoor(building: Building) {
        val frontZ = building.maxZ + 0.012f
        val doorWidth = building.width * 0.22f
        val doorHeight = building.height * 0.38f
        val doorX0 = building.x - doorWidth * 0.5f
        val doorX1 = building.x + doorWidth * 0.5f
        drawColorQuad(
            quad(doorX0, 0.01f, frontZ, doorX1, doorHeight, frontZ),
            floatArrayOf(0.34f, 0.17f, 0.08f, 1f)
        )
        drawLineLoop(
            floatArrayOf(doorX0, 0.01f, frontZ, doorX1, 0.01f, frontZ, doorX1, doorHeight, frontZ, doorX0, doorHeight, frontZ),
            floatArrayOf(0.05f, 0.04f, 0.03f, 1f)
        )

        val count = if (building.width > 1.5f) 3 else 2
        val windowWidth = building.width * 0.16f
        val windowHeight = building.height * 0.18f
        val y0 = building.height * 0.52f
        for (i in 0 until count) {
            val t = (i + 1f) / (count + 1f)
            val cx = building.minX + building.width * t
            val x0 = cx - windowWidth * 0.5f
            val x1 = cx + windowWidth * 0.5f
            val y1 = y0 + windowHeight
            drawColorQuad(quad(x0, y0, frontZ, x1, y1, frontZ), floatArrayOf(0.72f, 0.92f, 0.92f, 1f))
            drawLineLoop(floatArrayOf(x0, y0, frontZ, x1, y0, frontZ, x1, y1, frontZ, x0, y1, frontZ), floatArrayOf(0.04f, 0.05f, 0.04f, 1f))
            drawLines(floatArrayOf(cx, y0, frontZ + 0.002f, cx, y1, frontZ + 0.002f, x0, (y0 + y1) * 0.5f, frontZ + 0.002f, x1, (y0 + y1) * 0.5f, frontZ + 0.002f), floatArrayOf(0.04f, 0.05f, 0.04f, 1f))
        }
    }

    private fun drawEnemy() {
        if (!enemy.isActive) return
        val bob = sin(enemy.walkClock) * 0.035f
        drawBillboardSprite(
            enemy.x,
            bob,
            enemy.z,
            0.38f,
            1.34f,
            if (enemy.direction >= 0f) 1f else -1f,
            enemyTexture,
            true,
            floatArrayOf(1f, 1f, 1f, 1f)
        )
    }

    private fun drawBattleHero(x: Float, y: Float, z: Float, face: Float) {
        drawBillboardSprite(
            x,
            y + sin(walkClock) * 0.018f,
            z,
            0.45f,
            1.18f,
            face,
            heroTexture,
            false,
            floatArrayOf(1f, 1f, 1f, 1f)
        )
    }

    private fun drawBattleEnemy(actor: BattleActorRenderSnapshot, introHitHop: Float) {
        if (actor.alpha <= 0.01f) return
        val x = actor.position.x + introHitHop + actor.shakeX
        val y = actor.position.y
        val z = actor.position.z
        val animationState = actor.animationState
        val hurtFlash = animationState == BattleAnimationState.HURT || animationState == BattleAnimationState.DEFEAT
        val tint = if ((battleContact == BattleContact.PLAYER_FIRST_STRIKE || hurtFlash) && (battleTimer * 18f).toInt() % 2 == 0) {
            floatArrayOf(1f, 0.45f, 0.42f, actor.alpha)
        } else {
            floatArrayOf(1f, 1f, 1f, actor.alpha)
        }
        drawBillboardSprite(
            x,
            y + sin(enemy.walkClock) * 0.018f,
            z,
            0.42f * actor.scaleX,
            1.5f * actor.scaleY,
            actor.facing,
            enemyTexture,
            true,
            tint
        )
        if (animationState == BattleAnimationState.DEFEAT) {
            drawEnemyDefeatBursts(actor)
        }
    }

    private fun drawEnemyDefeatBursts(actor: BattleActorRenderSnapshot) {
        val p = actor.defeatProgress.coerceIn(0f, 1f)
        val alpha = (1f - p).coerceIn(0f, 1f)
        for (i in 0 until 6) {
            val angle = i * 1.0471976f + p * 5.4f
            val radius = 0.18f + p * (0.35f + i * 0.025f)
            val x = actor.position.x + cos(angle) * radius
            val y = actor.position.y + 0.72f + sin(angle * 1.3f) * radius
            val z = actor.position.z - 0.16f
            val size = 0.06f + (1f - p) * 0.04f
            val tint = if (i % 2 == 0) {
                floatArrayOf(1f, 0.86f, 0.22f, alpha)
            } else {
                floatArrayOf(0.82f, 0.82f, 0.86f, alpha * 0.72f)
            }
            drawColorQuad(
                floatArrayOf(
                    x, y + size, z,
                    x + size, y, z,
                    x, y - size, z,
                    x, y + size, z,
                    x, y - size, z,
                    x - size, y, z
                ),
                tint
            )
        }
    }

    private fun drawDamageParticles(particles: List<DamageParticle>) {
        particles.forEach { particle ->
            val textureId = damageNumberTextures.getOrPut(particle.value) {
                createDamageNumberTexture(particle.value)
            }
            val width = 0.28f * particle.scale * if (particle.value >= 10) 1.45f else 1f
            val height = 0.38f * particle.scale
            drawRotatedBillboardQuad(
                centerX = particle.position.x,
                centerY = particle.position.y,
                z = particle.position.z,
                halfWidth = width * 0.5f,
                halfHeight = height * 0.5f,
                rotation = particle.zRotation,
                textureId = textureId,
                tint = floatArrayOf(1f, 1f, 1f, particle.alpha)
            )
        }
    }

    private fun drawExpFlyParticles(particles: List<ExpFlyParticle>) {
        particles.forEach { particle ->
            val label = "+${particle.value} SP"
            val textureId = hudTextTextures.getOrPut(label) { createHudTextTexture(label, 42f, Color.rgb(255, 226, 84)) }
            val width = 0.52f * particle.scale
            val height = 0.18f * particle.scale
            drawTextured(
                texturedQuad(
                    particle.position.x - width * 0.5f,
                    particle.position.y - height * 0.5f,
                    particle.position.z,
                    particle.position.x + width * 0.5f,
                    particle.position.y + height * 0.5f,
                    particle.position.z
                ),
                textureId,
                floatArrayOf(1f, 1f, 1f, particle.alpha)
            )
        }
    }

    private fun drawLocalizedBattleHuds(renderState: BattleRenderState) {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        renderState.actors
            .filter { it.isPlayerTeam && it.currentHp > 0 }
            .forEach(::drawPlayerLocalHud)
        renderState.hpBars
            .filter { it.isVisible }
            .forEach(::drawEnemyLocalHud)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawPlayerLocalHud(actor: BattleActorRenderSnapshot) {
        val y = actor.position.y - 1.2f
        val z = actor.position.z - 0.16f
        val portraitRadius = 0.18f
        val portraitX = actor.position.x - 0.47f
        val centerY = y + 0.16f
        val heartX = portraitX + 0.37f
        val textX = heartX + 0.18f
        val hpLabel = "${actor.currentHp} / ${actor.maxHp}"

        drawDisc(portraitX, centerY, z + 0.006f, portraitRadius + 0.035f, floatArrayOf(0.04f, 0.035f, 0.035f, 0.96f))
        drawDisc(portraitX, centerY, z + 0.004f, portraitRadius + 0.018f, floatArrayOf(1f, 0.9f, 0.58f, 1f))
        drawTextured(
            texturedQuad(
                portraitX - portraitRadius,
                centerY - portraitRadius,
                z,
                portraitX + portraitRadius,
                centerY + portraitRadius,
                z
            ),
            playerFaceTexture,
            floatArrayOf(1f, 1f, 1f, 1f)
        )
        drawTextured(
            texturedQuad(heartX - 0.105f, centerY - 0.105f, z - 0.01f, heartX + 0.105f, centerY + 0.105f, z - 0.01f),
            heartTexture,
            floatArrayOf(1f, 1f, 1f, 1f)
        )
        drawHudText(hpLabel, textX, centerY, z - 0.02f, 0.72f, 0.2f)
    }

    private fun drawEnemyLocalHud(bar: BattleHpBarSpec) {
        val center = bar.centerPosition
        val z = center.z - 0.08f
        val heartRadius = 0.12f
        val textWidth = 0.42f
        val textHeight = 0.14f
        val alpha = bar.alpha

        // Heart Icon
        val heartX = center.x - 0.18f
        val heartY = center.y
        drawTextured(
            texturedQuad(heartX - heartRadius, heartY - heartRadius, z, heartX + heartRadius, heartY + heartRadius, z),
            heartTexture,
            floatArrayOf(1f, 1f, 1f, alpha)
        )

        // HP Numbers (Current / Max) - Styled as bold clean numeric text
        val hpText = "${bar.currentHp} / ${bar.maxHp}"
        val textureId = hudTextTextures.getOrPut(hudTextKey(hpText, 48f, Color.WHITE, centered = false)) {
            createHudTextTexture(hpText, 48f, Color.WHITE, centered = false)
        }
        drawTextured(
            texturedQuad(heartX + heartRadius * 0.85f, heartY - textHeight * 0.5f, z - 0.01f, heartX + heartRadius * 0.85f + textWidth, heartY + textHeight * 0.5f, z - 0.01f),
            textureId,
            floatArrayOf(1f, 1f, 1f, alpha)
        )
    }

    private fun drawRotatedBillboardQuad(
        centerX: Float,
        centerY: Float,
        z: Float,
        halfWidth: Float,
        halfHeight: Float,
        rotation: Float,
        textureId: Int,
        tint: FloatArray
    ) {
        val cosR = cos(rotation)
        val sinR = sin(rotation)
        val corners = floatArrayOf(
            -halfWidth, -halfHeight, 0f, 1f,
            halfWidth, -halfHeight, 1f, 1f,
            halfWidth, halfHeight, 1f, 0f,
            -halfWidth, -halfHeight, 0f, 1f,
            halfWidth, halfHeight, 1f, 0f,
            -halfWidth, halfHeight, 0f, 0f
        )
        val vertices = FloatArray(30)
        var out = 0
        var i = 0
        while (i < corners.size) {
            val localX = corners[i]
            val localY = corners[i + 1]
            vertices[out++] = centerX + localX * cosR - localY * sinR
            vertices[out++] = centerY + localX * sinR + localY * cosR
            vertices[out++] = z
            vertices[out++] = corners[i + 2]
            vertices[out++] = corners[i + 3]
            i += 4
        }
        drawTextured(vertices, textureId, tint)
    }

    private fun drawBattleCommandWheelLayer(renderState: BattleRenderState, layer: BattleMenuRenderLayer) {
        if (!renderState.isCommandMenuActive || renderState.menuItemSpecs.isEmpty()) return
        val specs = renderState.menuItemSpecs.filter { it.renderLayer == layer }
        if (specs.isEmpty()) return

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        specs.forEach { spec ->
            val bob = sin(battleTimer * 4.4f) * 0.014f
            val x = spec.position.x
            val y = spec.position.y + bob
            val z = spec.position.z
            
            if (spec.useIconWithDescription) {
                // Focused state: The speech bubble container + icon/text combination
                drawSelectedMenuBubble(spec, x, y, z)
            } else {
                // Inactive state: Circular badge + grayed-out icon
                val badgeRadius = BATTLE_WHEEL_ICON_RADIUS * spec.scale * 1.15f
                drawDisc(x, y, z + 0.01f, badgeRadius, floatArrayOf(0.12f, 0.12f, 0.14f, spec.alpha * 0.85f))
                drawMenuBadge(
                    type = spec.option.itemType,
                    x = x,
                    y = y,
                    z = z,
                    radius = BATTLE_WHEEL_ICON_RADIUS * spec.scale,
                    selected = false,
                    alpha = spec.alpha
                )
            }
        }
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private fun drawSelectedMenuBubble(spec: BattleMenuItemRenderSpec, centerX: Float, centerY: Float, z: Float) {
        val labelTexture = menuLabelTextures[spec.option.displayName.uppercase()]
        val iconTexture = menuIconTextures[spec.option.itemType]
        
        if (labelTexture != null && iconTexture != null) {
            val aspect = textureAspects[labelTexture] ?: 3.2f
            
            // Subtle pulse animation (TTYD Style)
            val pulse = 1f + sin(battleTimer * 6.5f) * 0.035f
            val labelHeight = 0.55f * spec.scale * pulse
            val labelWidth = labelHeight * aspect
            
            // 1. Draw the prominent white speech bubble container
            drawTextured(
                texturedQuad(
                    centerX - labelWidth * 0.5f,
                    centerY - labelHeight * 0.5f,
                    z,
                    centerX + labelWidth * 0.5f,
                    centerY + labelHeight * 0.5f,
                    z
                ),
                labelTexture,
                floatArrayOf(1f, 1f, 1f, spec.descriptionAlpha)
            )
            
            // 2. Draw the icon INSIDE the bubble
            val iconAspect = textureAspects[iconTexture] ?: 1f
            val iconH = labelHeight * 0.62f
            val iconW = iconH * iconAspect
            val iconX = centerX - labelWidth * 0.3f
            val iconY = centerY + labelHeight * 0.05f // Centered in the main bubble body
            
            drawTextured(
                texturedQuad(
                    iconX - iconW * 0.5f,
                    iconY - iconH * 0.5f,
                    z - 0.012f,
                    iconX + iconW * 0.5f,
                    iconY + iconH * 0.5f,
                    z - 0.012f
                ),
                iconTexture,
                floatArrayOf(1f, 1f, 1f, spec.descriptionAlpha)
            )
        }
    }

    private fun drawMenuBadge(type: BattleMenuItemType, x: Float, y: Float, z: Float, radius: Float, selected: Boolean, alpha: Float) {
        val iconTexture = menuIconTextures[type]
        if (iconTexture != null) {
            val iconRadius = if (selected) radius * 1.16f else radius
            val aspect = textureAspects[iconTexture] ?: 1f
            val hWidth = iconRadius * aspect
            val hHeight = iconRadius
            
            // Apply a darkened/grayish tint to non-selected icons
            val tint = if (selected) {
                floatArrayOf(1f, 1f, 1f, alpha)
            } else {
                floatArrayOf(0.45f, 0.45f, 0.48f, alpha * 0.72f)
            }
            
            drawTextured(
                texturedQuad(x - hWidth, y - hHeight, z - 0.018f, x + hWidth, y + hHeight, z - 0.018f),
                iconTexture,
                tint
            )
        } else {
            drawMenuIcon(type, x, y, z - 0.018f, radius, if (selected) 1f else alpha.coerceAtLeast(0.68f))
        }
    }

    private fun drawMenuIcon(type: BattleMenuItemType, x: Float, y: Float, z: Float, radius: Float, alpha: Float) {
        when (type) {
            BattleMenuItemType.JUMP -> {
                drawColorQuad(quad(x - radius * 0.42f, y - radius * 0.06f, z, x + radius * 0.3f, y + radius * 0.18f, z), floatArrayOf(0.94f, 0.12f, 0.1f, alpha))
                drawColorQuad(quad(x - radius * 0.1f, y - radius * 0.34f, z - 0.004f, x + radius * 0.48f, y - radius * 0.06f, z - 0.004f), floatArrayOf(0.98f, 0.84f, 0.22f, alpha))
                drawLineLoop(floatArrayOf(x - radius * 0.42f, y - radius * 0.06f, z - 0.008f, x + radius * 0.3f, y - radius * 0.06f, z - 0.008f, x + radius * 0.3f, y + radius * 0.18f, z - 0.008f, x - radius * 0.42f, y + radius * 0.18f, z - 0.008f), floatArrayOf(0.05f, 0.04f, 0.035f, alpha))
            }
            BattleMenuItemType.HAMMER -> {
                drawColorQuad(quad(x - radius * 0.5f, y + radius * 0.13f, z, x + radius * 0.46f, y + radius * 0.36f, z), floatArrayOf(0.58f, 0.28f, 0.1f, alpha))
                drawColorQuad(quad(x + radius * 0.08f, y - radius * 0.42f, z - 0.004f, x + radius * 0.34f, y + radius * 0.16f, z - 0.004f), floatArrayOf(0.98f, 0.82f, 0.32f, alpha))
                drawLineLoop(floatArrayOf(x - radius * 0.5f, y + radius * 0.13f, z - 0.008f, x + radius * 0.46f, y + radius * 0.13f, z - 0.008f, x + radius * 0.46f, y + radius * 0.36f, z - 0.008f, x - radius * 0.5f, y + radius * 0.36f, z - 0.008f), floatArrayOf(0.05f, 0.04f, 0.035f, alpha))
            }
            BattleMenuItemType.ITEMS -> {
                drawColorQuad(quad(x - radius * 0.42f, y - radius * 0.32f, z, x + radius * 0.42f, y + radius * 0.26f, z), floatArrayOf(0.28f, 0.7f, 0.28f, alpha))
                drawColorQuad(quad(x - radius * 0.28f, y + radius * 0.22f, z - 0.004f, x + radius * 0.28f, y + radius * 0.42f, z - 0.004f), floatArrayOf(0.92f, 0.72f, 0.28f, alpha))
                drawColorQuad(quad(x - radius * 0.1f, y - radius * 0.32f, z - 0.008f, x + radius * 0.1f, y + radius * 0.26f, z - 0.008f), floatArrayOf(1f, 0.96f, 0.82f, alpha))
            }
            BattleMenuItemType.STRATEGY -> {
                drawLines(floatArrayOf(x - radius * 0.38f, y - radius * 0.44f, z, x - radius * 0.38f, y + radius * 0.46f, z), floatArrayOf(0.08f, 0.08f, 0.1f, alpha))
                drawColorQuad(
                    floatArrayOf(
                        x - radius * 0.32f, y + radius * 0.42f, z,
                        x + radius * 0.42f, y + radius * 0.22f, z,
                        x - radius * 0.32f, y + radius * 0.02f, z
                    ),
                    floatArrayOf(0.94f, 0.08f, 0.1f, alpha)
                )
            }
            BattleMenuItemType.RUN -> {
                drawColorQuad(quad(x - radius * 0.42f, y - radius * 0.1f, z, x + radius * 0.34f, y + radius * 0.18f, z), floatArrayOf(0.18f, 0.18f, 0.22f, alpha))
                drawColorQuad(quad(x + radius * 0.08f, y - radius * 0.34f, z - 0.004f, x + radius * 0.46f, y - radius * 0.08f, z - 0.004f), floatArrayOf(0.94f, 0.16f, 0.14f, alpha))
                drawColorQuad(quad(x - radius * 0.42f, y + radius * 0.12f, z - 0.008f, x - radius * 0.08f, y + radius * 0.34f, z - 0.008f), floatArrayOf(0.94f, 0.16f, 0.14f, alpha))
            }
            BattleMenuItemType.NO_ITEMS -> {
                drawColorQuad(quad(x - radius * 0.46f, y - radius * 0.08f, z, x + radius * 0.46f, y + radius * 0.1f, z), floatArrayOf(0.24f, 0.24f, 0.26f, alpha))
            }
        }
    }

    private fun drawDisc(x: Float, y: Float, z: Float, radius: Float, color: FloatArray) {
        val segments = 18
        val vertices = FloatArray(segments * 9)
        var out = 0
        for (i in 0 until segments) {
            val a0 = i.toFloat() / segments.toFloat() * 6.2831855f
            val a1 = (i + 1).toFloat() / segments.toFloat() * 6.2831855f
            vertices[out++] = x
            vertices[out++] = y
            vertices[out++] = z
            vertices[out++] = x + cos(a0) * radius
            vertices[out++] = y + sin(a0) * radius
            vertices[out++] = z
            vertices[out++] = x + cos(a1) * radius
            vertices[out++] = y + sin(a1) * radius
            vertices[out++] = z
        }
        drawColorQuad(vertices, color)
    }

    private fun drawShieldCue(position: BattleVector3) {
        val pulse = 0.08f + abs(sin(battleTimer * 18f)) * 0.08f
        val width = 0.72f + pulse
        val height = 1.18f + pulse
        val z = position.z - 0.035f
        drawColorQuad(
            floatArrayOf(
                position.x, position.y + height, z,
                position.x + width * 0.5f, position.y + height * 0.5f, z,
                position.x, position.y, z,
                position.x, position.y + height, z,
                position.x, position.y, z,
                position.x - width * 0.5f, position.y + height * 0.5f, z
            ),
            floatArrayOf(0.48f, 0.86f, 1f, 0.34f)
        )
    }

    private fun drawHero() {
        val heroY = jumpHeight + sin(walkClock) * 0.025f
        drawBillboardSprite(
            playerX,
            heroY,
            playerZ + 0.02f,
            0.36f,
            1.18f,
            facing,
            heroTexture,
            false,
            floatArrayOf(1f, 1f, 1f, 1f)
        )
    }

    private fun drawBillboardSprite(
        centerX: Float,
        baseY: Float,
        z: Float,
        halfWidth: Float,
        height: Float,
        face: Float,
        textureId: Int,
        keyWhite: Boolean,
        tint: FloatArray
    ) {
        val turnAmount = if (battleState == BattlePresentationState.OVERWORLD && cameraFocusBuilding != null) {
            smoothStep(cameraStageAmount)
        } else {
            0f
        }
        val axisX = lerp(1f, 0f, turnAmount)
        val axisZ = lerp(0f, -DOLLHOUSE_CAMERA_SIDE_DIRECTION, turnAmount)
        val leftX = centerX - axisX * halfWidth * face
        val leftZ = z - axisZ * halfWidth * face
        val rightX = centerX + axisX * halfWidth * face
        val rightZ = z + axisZ * halfWidth * face
        val vertices = floatArrayOf(
            leftX, baseY, leftZ, 0f, 1f,
            rightX, baseY, rightZ, 1f, 1f,
            rightX, baseY + height, rightZ, 1f, 0f,
            leftX, baseY, leftZ, 0f, 1f,
            rightX, baseY + height, rightZ, 1f, 0f,
            leftX, baseY + height, leftZ, 0f, 0f
        )
        drawTextured(vertices, textureId, tint, keyWhite)
    }

    private fun drawCuboidEdges(building: Building) {
        val x0 = building.minX
        val x1 = building.maxX
        val z0 = building.minZ
        val z1 = building.maxZ
        val y0 = 0f
        val y1 = building.height
        drawLines(
            floatArrayOf(
                x0, y0, z0, x1, y0, z0, x1, y0, z0, x1, y0, z1, x1, y0, z1, x0, y0, z1, x0, y0, z1, x0, y0, z0,
                x0, y1, z0, x1, y1, z0, x1, y1, z0, x1, y1, z1, x1, y1, z1, x0, y1, z1, x0, y1, z1, x0, y1, z0,
                x0, y0, z0, x0, y1, z0, x1, y0, z0, x1, y1, z0, x1, y0, z1, x1, y1, z1, x0, y0, z1, x0, y1, z1
            ),
            floatArrayOf(0.04f, 0.035f, 0.03f, 1f)
        )
    }

    private fun drawRoofEdges(x0: Float, x1: Float, z0: Float, z1: Float, base: Float, ridge: Float, midX: Float) {
        drawLines(
            floatArrayOf(
                x0, base, z0, midX, ridge, z0, midX, ridge, z0, x1, base, z0,
                x0, base, z1, midX, ridge, z1, midX, ridge, z1, x1, base, z1,
                x0, base, z0, x0, base, z1, x1, base, z0, x1, base, z1,
                midX, ridge, z0, midX, ridge, z1, x0, base, z0, x1, base, z0, x0, base, z1, x1, base, z1
            ),
            floatArrayOf(0.04f, 0.035f, 0.03f, 1f)
        )
    }

    private fun drawTextured(vertices: FloatArray, textureId: Int, tint: FloatArray, keyWhite: Boolean = false) {
        GLES20.glUseProgram(program)
        setIdentityMvp()
        GLES20.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)
        val adjustedTint = floatArrayOf(tint[0], tint[1], tint[2], tint[3] * renderAlpha)
        GLES20.glUniform4fv(tintHandle, 1, adjustedTint, 0)
        GLES20.glUniform1f(keyWhiteHandle, if (keyWhite) 1f else 0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        val buffer = vertices.toFloatBuffer()
        buffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 3, GLES20.GL_FLOAT, false, TEXTURED_STRIDE, buffer)
        GLES20.glEnableVertexAttribArray(positionHandle)
        buffer.position(3)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, TEXTURED_STRIDE, buffer)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertices.size / TEXTURED_COMPONENTS)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
    }

    private fun texturedQuad(x0: Float, y0: Float, z: Float, x1: Float, y1: Float, z1: Float): FloatArray {
        return floatArrayOf(
            x0, y0, z, 0f, 1f,
            x1, y0, z, 1f, 1f,
            x1, y1, z1, 1f, 0f,
            x0, y0, z, 0f, 1f,
            x1, y1, z1, 1f, 0f,
            x0, y1, z1, 0f, 0f
        )
    }

    private fun createBattleMenuLabelTextures() {
        menuLabelTextures.clear()
        listOf("JUMP", "HAMMER", "ITEMS", "BACKPACK", "STRATEGY", "TACTICS", "RUN", "NO ITEMS").forEach { label ->
            menuLabelTextures[label] = createLabelTexture(label)
        }
    }

    private fun loadBattleMenuTextures() {
        menuIconTextures.clear()
        menuBubbleTextures.clear()
        menuIconTextures[BattleMenuItemType.JUMP] = loadTextureWithEdgeBlackAlpha("menu/menu_jump_icon.png")
        menuIconTextures[BattleMenuItemType.HAMMER] = loadTextureWithEdgeBlackAlpha("menu/menu_hammer_icon.png")
        menuIconTextures[BattleMenuItemType.ITEMS] = loadTextureWithEdgeBlackAlpha("menu/menu_items_icon.png")
        menuIconTextures[BattleMenuItemType.STRATEGY] = loadTextureWithEdgeBlackAlpha("menu/menu_strategy_icon.png")
        menuBubbleTextures[BattleMenuItemType.JUMP] = loadTextureWithEdgeBlackAlpha("menu/menu_jump_bubble.png")
        menuBubbleTextures[BattleMenuItemType.HAMMER] = loadTextureWithEdgeBlackAlpha("menu/menu_hammer_bubble.png")
        menuBubbleTextures[BattleMenuItemType.ITEMS] = loadTextureWithEdgeBlackAlpha("menu/menu_items_bubble.png")
        menuBubbleTextures[BattleMenuItemType.STRATEGY] = loadTextureWithEdgeBlackAlpha("menu/menu_strategy_bubble.png")
    }

    private fun createLabelTexture(label: String): Int {
        val bitmap = Bitmap.createBitmap(440, 140, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        
        // 1. Draw the Bubble "Tail" pointing down (TTYD Style)
        val tailPath = Path().apply {
            moveTo(220f, 108f)
            lineTo(205f, 135f)
            lineTo(190f, 108f)
            close()
        }
        
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawRoundRect(RectF(10f, 12f, 430f, 110f), 50f, 50f, paint)
        canvas.drawPath(tailPath, paint)
        
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 9f
        paint.color = Color.BLACK
        canvas.drawRoundRect(RectF(10f, 12f, 430f, 110f), 50f, 50f, paint)
        // Redraw tail stroke separately to connect it
        canvas.drawPath(tailPath, paint)
        
        // 2. Draw Text (shifted right for icon)
        paint.style = Paint.Style.FILL
        paint.color = Color.BLACK
        paint.textSize = 46f
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.LEFT
        val fontMetrics = paint.fontMetrics
        val textY = (110f + 12f) / 2f - (fontMetrics.ascent + fontMetrics.descent) / 2f
        canvas.drawText(label, 175f, textY, paint)
        
        return createTextureFromBitmap(bitmap).also { bitmap.recycle() }
    }

    private fun createDamageNumberTexture(value: Int): Int {
        val clamped = value.coerceIn(0, 999)
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = if (clamped >= 100) 64f else 78f
        }
        val label = clamped.toString()
        val y = 64f - (paint.descent() + paint.ascent()) * 0.5f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 13f
        paint.color = Color.argb(255, 55, 28, 20)
        canvas.drawText(label, 64f, y, paint)
        paint.strokeWidth = 7f
        paint.color = Color.argb(255, 255, 246, 218)
        canvas.drawText(label, 64f, y, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(255, 232, 56, 42)
        canvas.drawText(label, 64f, y, paint)
        paint.color = Color.argb(210, 255, 210, 92)
        paint.textSize *= 0.78f
        canvas.drawText(label, 62f, y - 6f, paint)
        return createTextureFromBitmap(bitmap).also { bitmap.recycle() }
    }

    private fun drawHudText(label: String, x: Float, y: Float, z: Float, width: Float, height: Float) {
        val textureId = hudTextTextures.getOrPut(hudTextKey(label, 38f, Color.WHITE, centered = false)) {
            createHudTextTexture(label, 38f, Color.WHITE, centered = false)
        }
        drawTextured(texturedQuad(x, y - height * 0.5f, z, x + width, y + height * 0.5f, z), textureId, floatArrayOf(1f, 1f, 1f, 1f))
    }

    private fun drawCenteredHudText(label: String, centerX: Float, centerY: Float, z: Float, width: Float, height: Float, textSize: Float, fillColor: Int) {
        val textureId = hudTextTextures.getOrPut(hudTextKey(label, textSize, fillColor, centered = true)) {
            createHudTextTexture(label, textSize, fillColor, centered = true)
        }
        drawTextured(
            texturedQuad(centerX - width * 0.5f, centerY - height * 0.5f, z, centerX + width * 0.5f, centerY + height * 0.5f, z),
            textureId,
            floatArrayOf(1f, 1f, 1f, 1f)
        )
    }

    private fun createHudTextTexture(label: String, textSize: Float, fillColor: Int, centered: Boolean = false): Int {
        val bitmap = Bitmap.createBitmap(384, 96, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = if (centered) Paint.Align.CENTER else Paint.Align.LEFT
            isFakeBoldText = true
            this.textSize = textSize
        }
        val y = 48f - (paint.descent() + paint.ascent()) * 0.5f
        val x = if (centered) 192f else 12f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 7f
        paint.color = Color.argb(240, 20, 18, 22)
        canvas.drawText(label, x, y, paint)
        paint.style = Paint.Style.FILL
        paint.color = fillColor
        canvas.drawText(label, x, y, paint)
        return createTextureFromBitmap(bitmap).also { bitmap.recycle() }
    }

    private fun hudTextKey(label: String, textSize: Float, fillColor: Int, centered: Boolean): String {
        return "${if (centered) "C" else "L"}|${textSize.toInt()}|$fillColor|$label"
    }

    private fun createPlayerFaceTexture(assetPath: String): Int {
        val source = context.assets.open(assetPath).use { stream ->
            BitmapFactory.decodeStream(stream)
        }
        val bitmap = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val path = Path().apply {
            addCircle(96f, 96f, 82f, Path.Direction.CW)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val cropSize = min(source.width, source.height)
        val srcLeft = ((source.width - cropSize) * 0.5f).toInt()
        val srcTop = 0
        val sourceRect = android.graphics.Rect(srcLeft, srcTop, srcLeft + cropSize, srcTop + cropSize)
        val destRect = RectF(14f, 14f, 178f, 178f)
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(source, sourceRect, destRect, paint)
        canvas.restore()
        source.recycle()
        return createTextureFromBitmap(bitmap).also { bitmap.recycle() }
    }

    private fun createHeartTexture(): Int {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val heart = Path().apply {
            moveTo(64f, 108f)
            cubicTo(24f, 78f, 12f, 50f, 31f, 31f)
            cubicTo(45f, 17f, 59f, 25f, 64f, 39f)
            cubicTo(69f, 25f, 83f, 17f, 97f, 31f)
            cubicTo(116f, 50f, 104f, 78f, 64f, 108f)
            close()
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 12f
        paint.color = Color.argb(255, 45, 14, 18)
        canvas.drawPath(heart, paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(255, 228, 24, 42)
        canvas.drawPath(heart, paint)
        paint.color = Color.argb(120, 255, 178, 186)
        canvas.drawCircle(49f, 43f, 10f, paint)
        return createTextureFromBitmap(bitmap).also { bitmap.recycle() }
    }

    private fun createTextureFromBitmap(bitmap: Bitmap): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        val id = textureIds[0]
        textureAspects[id] = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1f
        return id
    }

    private fun drawColorQuad(vertices: FloatArray, color: FloatArray) {
        drawColor(vertices, color, GLES20.GL_TRIANGLES)
    }

    private fun drawLines(vertices: FloatArray, color: FloatArray) {
        drawColor(vertices, color, GLES20.GL_LINES)
    }

    private fun drawLineLoop(vertices: FloatArray, color: FloatArray) {
        drawColor(vertices, color, GLES20.GL_LINE_LOOP)
    }

    private fun drawColor(vertices: FloatArray, color: FloatArray, mode: Int) {
        GLES20.glUseProgram(colorProgram)
        setIdentityMvp()
        GLES20.glUniformMatrix4fv(colorMvpHandle, 1, false, mvpMatrix, 0)
        val adjustedColor = floatArrayOf(color[0], color[1], color[2], color[3] * renderAlpha)
        GLES20.glUniform4fv(colorHandle, 1, adjustedColor, 0)
        val buffer = vertices.toFloatBuffer()
        GLES20.glVertexAttribPointer(colorPositionHandle, 3, GLES20.GL_FLOAT, false, COLORED_STRIDE, buffer)
        GLES20.glEnableVertexAttribArray(colorPositionHandle)
        GLES20.glLineWidth(4f)
        GLES20.glDrawArrays(mode, 0, vertices.size / COLORED_COMPONENTS)
        GLES20.glDisableVertexAttribArray(colorPositionHandle)
    }

    private fun setIdentityMvp() {
        Matrix.setIdentityM(modelMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)
    }

    private fun quad(x0: Float, y0: Float, z: Float, x1: Float, y1: Float, z1: Float): FloatArray {
        return floatArrayOf(
            x0, y0, z,
            x1, y0, z,
            x1, y1, z1,
            x0, y0, z,
            x1, y1, z1,
            x0, y1, z1
        )
    }

    private fun sideQuad(x: Float, z0: Float, y0: Float, z1: Float, y1: Float): FloatArray {
        return floatArrayOf(
            x, y0, z0,
            x, y0, z1,
            x, y1, z1,
            x, y0, z0,
            x, y1, z1,
            x, y1, z0
        )
    }

    private fun loadTexture(assetPath: String, repeat: Boolean): Int {
        val textureIds = IntArray(1)
        GLES20.glGenTextures(1, textureIds, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureIds[0])
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, if (repeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, if (repeat) GLES20.GL_REPEAT else GLES20.GL_CLAMP_TO_EDGE)
        val bitmap = context.assets.open(assetPath).use { stream ->
            BitmapFactory.decodeStream(stream)
        }
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        val id = textureIds[0]
        textureAspects[id] = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height.toFloat() else 1f
        bitmap.recycle()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        return id
    }

    private fun loadTextureWithEdgeBlackAlpha(assetPath: String): Int {
        val decoded = context.assets.open(assetPath).use { stream ->
            BitmapFactory.decodeStream(stream)
        }
        val scaled = scaleBitmapForMenuTexture(decoded)
        if (scaled !== decoded) decoded.recycle()
        val bitmap = scaled.copy(Bitmap.Config.ARGB_8888, true)
        if (bitmap !== scaled) scaled.recycle()
        removeEdgeConnectedBlack(bitmap)
        return createTextureFromBitmap(bitmap).also { bitmap.recycle() }
    }

    private fun scaleBitmapForMenuTexture(source: Bitmap): Bitmap {
        val maxDimension = 512
        val largest = max(source.width, source.height)
        if (largest <= maxDimension) return source
        val scale = maxDimension.toFloat() / largest.toFloat()
        val width = max(1, (source.width * scale).toInt())
        val height = max(1, (source.height * scale).toInt())
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun removeEdgeConnectedBlack(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val visited = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var head = 0
        var tail = 0

        fun indexOf(x: Int, y: Int): Int = y * width + x
        fun enqueueIfBlack(x: Int, y: Int) {
            if (x !in 0 until width || y !in 0 until height) return
            val index = indexOf(x, y)
            if (visited[index]) return
            visited[index] = true
            if (isKeyBlack(pixels[index])) {
                queue[tail++] = index
            }
        }

        for (x in 0 until width) {
            enqueueIfBlack(x, 0)
            enqueueIfBlack(x, height - 1)
        }
        for (y in 0 until height) {
            enqueueIfBlack(0, y)
            enqueueIfBlack(width - 1, y)
        }

        while (head < tail) {
            val index = queue[head++]
            val x = index % width
            val y = index / width
            pixels[index] = pixels[index] and 0x00FFFFFF
            enqueueIfBlack(x + 1, y)
            enqueueIfBlack(x - 1, y)
            enqueueIfBlack(x, y + 1)
            enqueueIfBlack(x, y - 1)
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun isKeyBlack(pixel: Int): Boolean {
        return Color.red(pixel) < 28 && Color.green(pixel) < 28 && Color.blue(pixel) < 28
    }

    private fun createProgram(vertexShaderCode: String, fragmentShaderCode: String): Int {
        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        return GLES20.glCreateProgram().also { handle ->
            GLES20.glAttachShader(handle, vertexShader)
            GLES20.glAttachShader(handle, fragmentShader)
            GLES20.glLinkProgram(handle)
        }
    }

    private fun loadShader(type: Int, shaderCode: String): Int {
        return GLES20.glCreateShader(type).also { handle ->
            GLES20.glShaderSource(handle, shaderCode)
            GLES20.glCompileShader(handle)
        }
    }

    private fun FloatArray.toFloatBuffer(): FloatBuffer {
        return ByteBuffer.allocateDirect(size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(this@toFloatBuffer)
                position(0)
            }
    }
}

private data class MenuWheelEntry(
    val option: BattleMenuOption,
    val x: Float,
    val y: Float,
    val z: Float,
    val size: Float,
    val selected: Boolean,
    val alpha: Float
)

private class Building(
    val x: Float,
    val z: Float,
    val width: Float,
    val depth: Float,
    val height: Float,
    val textureIndex: Int,
    val roofTint: FloatArray
) {
    val minX: Float = x - width * 0.5f
    val maxX: Float = x + width * 0.5f
    val minZ: Float = z - depth * 0.5f
    val maxZ: Float = z + depth * 0.5f
    val doorWidth: Float = 0.82f
    val doorHeight: Float = 1.62f
    val doorZ: Float = maxZ
    var openProgress: Float = 0f
    var doorActivated: Boolean = false
    var playerEnteredInterior: Boolean = false
    var exitRequested: Boolean = false

    fun isPlayerInTrigger(playerX: Float, playerZ: Float): Boolean {
        val doorHalfWidth = doorWidth * 0.72f
        val inThreshold = playerX > x - doorHalfWidth &&
            playerX < x + doorHalfWidth &&
            playerZ > maxZ - 0.45f &&
            playerZ < maxZ + 0.95f
        val insideHouse = playerX > minX + 0.22f &&
            playerX < maxX - 0.22f &&
            playerZ > minZ + 0.22f &&
            playerZ < maxZ - 0.12f
        return inThreshold || insideHouse
    }

    fun isPlayerInsideHouse(playerX: Float, playerZ: Float): Boolean {
        return playerX > minX + 0.22f &&
            playerX < maxX - 0.22f &&
            playerZ > minZ + 0.22f &&
            playerZ < maxZ - 0.12f
    }

    fun isPlayerNearDoor(playerX: Float, playerZ: Float): Boolean {
        val doorHalfWidth = doorWidth * 0.9f
        return playerX > x - doorHalfWidth &&
            playerX < x + doorHalfWidth &&
            playerZ > maxZ - 0.7f &&
            playerZ < maxZ + 1.15f
    }

    fun resetDoorState() {
        doorActivated = false
        playerEnteredInterior = false
        exitRequested = false
    }
}

private data class DoorTravel(
    val building: Building,
    val startX: Float,
    val startZ: Float,
    val endX: Float,
    val endZ: Float,
    val exiting: Boolean,
    var progress: Float = 0f
)

private class Enemy(
    val centerX: Float,
    val z: Float,
    val range: Float
) {
    var x: Float = centerX
    var direction: Float = 1f
    var walkClock: Float = 0f
    var isActive: Boolean = true
}

private enum class BattlePresentationState {
    OVERWORLD,
    HIT_FREEZE,
    STAGE_TRANSITION,
    STAGE,
    BATTLE_FADE_OUT
}

private enum class BattleContact {
    NEUTRAL,
    PLAYER_FIRST_STRIKE,
    ENEMY_FIRST_STRIKE
}

private fun BattleContact.toBattleAdvantage(): BattleStartAdvantage {
    return when (this) {
        BattleContact.NEUTRAL -> BattleStartAdvantage.NEUTRAL
        BattleContact.PLAYER_FIRST_STRIKE -> BattleStartAdvantage.PLAYER_FIRST_STRIKE
        BattleContact.ENEMY_FIRST_STRIKE -> BattleStartAdvantage.ENEMY_FIRST_STRIKE
    }
}

private const val TEXTURED_COMPONENTS = 5
private const val TEXTURED_STRIDE = TEXTURED_COMPONENTS * 4
private const val COLORED_COMPONENTS = 3
private const val COLORED_STRIDE = COLORED_COMPONENTS * 4
private const val HALF_PI = 1.5707964f
private const val BUILDING_OPEN_DURATION_SECONDS = 0.4f
private const val DOOR_TRAVEL_SECONDS = 0.55f
private const val DOOR_TRAVEL_OPEN_THRESHOLD = 0.55f
private const val DOOR_ENTRY_DISTANCE = 0.92f
private const val DOOR_EXIT_DISTANCE = 0.95f
private const val WALL_SURFACE_OFFSET = 0.036f
private const val WALL_DETAIL_OFFSET = 0.052f
private const val WALL_DETAIL_LIFT = 0.018f
private const val SIDE_WALL_SURFACE_OFFSET = 0.065f
private const val HINGED_WALL_GROUND_CLEARANCE = 0.04f
private const val DOLLHOUSE_CAMERA_SIDE_DIRECTION = -1f
private const val SIDE_CAMERA_DISTANCE = 4.7f
private const val SIDE_VIEW_BACKGROUND_BUILDING_ALPHA = 0.16f
private const val ENEMY_PATROL_SPEED = 0.75f
private const val ENEMY_CONTACT_RADIUS = 0.62f
private const val HIT_FREEZE_SECONDS = 0.32f
private const val STAGE_TRANSITION_SECONDS = 0.68f
private const val BATTLE_RETURN_FADE_SECONDS = 0.65f
private const val BATTLE_CAMERA_LERP_T = 0.05f
private const val BATTLE_WHEEL_ICON_RADIUS = 0.28f
private const val BATTLE_WHEEL_BUBBLE_WIDTH = 1.72f
private const val BATTLE_WHEEL_BUBBLE_WIDE_WIDTH = 2.05f
private const val BATTLE_WHEEL_BUBBLE_HEIGHT = 0.94f

private fun lerp(start: Float, end: Float, amount: Float): Float {
    return start + (end - start) * amount.coerceIn(0f, 1f)
}

private fun moveToward(current: Float, target: Float, maxDelta: Float): Float {
    return when {
        current < target -> min(current + maxDelta, target)
        current > target -> max(current - maxDelta, target)
        else -> current
    }
}

private fun smoothStep(value: Float): Float {
    val x = value.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

private const val TEXTURE_VERTEX_SHADER = """
uniform mat4 u_MvpMatrix;
attribute vec4 a_Position;
attribute vec2 a_TexCoord;
varying vec2 v_TexCoord;
void main() {
    gl_Position = u_MvpMatrix * a_Position;
    v_TexCoord = a_TexCoord;
}
"""

private const val TEXTURE_FRAGMENT_SHADER = """
precision mediump float;
uniform sampler2D u_Texture;
uniform vec4 u_Tint;
uniform float u_KeyWhite;
varying vec2 v_TexCoord;
void main() {
    vec4 tex = texture2D(u_Texture, v_TexCoord);
    if (u_KeyWhite > 0.5 && tex.r > 0.94 && tex.g > 0.94 && tex.b > 0.94) {
        discard;
    }
    gl_FragColor = tex * u_Tint;
}
"""

private const val COLOR_VERTEX_SHADER = """
uniform mat4 u_MvpMatrix;
attribute vec4 a_Position;
void main() {
    gl_Position = u_MvpMatrix * a_Position;
}
"""

private const val COLOR_FRAGMENT_SHADER = """
precision mediump float;
uniform vec4 u_Color;
void main() {
    gl_FragColor = u_Color;
}
"""

