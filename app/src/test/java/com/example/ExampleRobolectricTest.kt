package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.KeyEvent
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.MacroRepository
import com.example.dualweb.DualLayoutMode
import com.example.dualweb.DualWebConfigState
import com.example.dualweb.DualWebInputBridge
import com.example.dualweb.DualWebMacroDelegate
import com.example.dualweb.DualWebPreferences
import com.example.dualweb.WebViewTextInputHelper
import com.example.engine.ExecutionStatus
import com.example.engine.MacroInterpreter
import com.example.flyff.ActiveBuffDefinition
import com.example.flyff.BarCalibrationSpec
import com.example.flyff.BuffKeyLink
import com.example.flyff.BuffTimerOcrReader
import com.example.flyff.CharacterHudRegions
import com.example.flyff.CombatRotationConfig
import com.example.flyff.FlyffCatalogEntry
import com.example.flyff.FlyffIconMatcher
import com.example.flyff.FlyffMacroCompiler
import com.example.flyff.FlyffProfile
import com.example.flyff.FlyffSkillGroup
import com.example.flyff.FlyffVisionAnalyzer
import com.example.flyff.HealRuleConfig
import com.example.flyff.LayoutHudCalibration
import com.example.flyff.RectSpec
import com.example.flyff.ScannedSkillKey
import com.example.flyff.SkillCategory
import com.example.flyff.SmartEnginePhase
import com.example.flyff.SmartFlyffEngine
import com.example.flyff.TaskbarGroupKey
import com.example.flyff.VitalTriggerRule
import com.example.flyff.VitalType
import com.example.flyff.ui.FlyffSetupController
import com.example.model.BranchAction
import com.example.model.CallFunctionStep
import com.example.model.IfImageStep
import com.example.model.IfVariableStep
import com.example.model.InputInjectionMode
import com.example.model.JumpStep
import com.example.model.KeyPressStep
import com.example.model.LoopEndStep
import com.example.model.LoopStartStep
import com.example.model.MacroCloner
import com.example.model.MacroDefinition
import com.example.model.MacroFunction
import com.example.model.ScriptClipboard
import com.example.model.ScriptClipboardItem
import com.example.model.SetVariableStep
import com.example.model.StopStep
import com.example.model.TapStep
import com.example.model.TouchGuardPolicy
import com.example.model.VarComparator
import com.example.model.VarOperation
import com.example.model.WebViewTarget
import com.example.service.OverlayCoordinator
import com.example.service.PickerMode
import com.example.service.PickerRequest
import com.example.service.PickerResult
import com.example.ui.components.computeTemplateCropRect
import com.example.vision.ScreenDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun appName_isFlyffFtools() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("Flyff Ftools", appName)
    }

    @Test
    fun repository_saveExportAndImportRoundTrip() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())

        val loopId = "loop-1"
        val original = MacroDefinition(
            id = "macro-1",
            name = "Test Macro",
            targetAppLabel = "Sandbox",
            loopCount = 2,
            steps = listOf(
                SetVariableStep(variableName = "count", operation = VarOperation.SET, valueExpression = "1"),
                LoopStartStep(id = loopId, iterations = 3),
                SetVariableStep(variableName = "count", operation = VarOperation.INCREMENT, valueExpression = "2"),
                LoopEndStep(loopStartId = loopId)
            )
        )
        repo.saveMacro(original)

        val jsonPayload = repo.exportMacrosToJson(setOf(original.id))
        repo.deleteMacro(original.id)
        assertTrue(repo.macros.first().isEmpty())

        val imported = repo.importMacrosFromJson(jsonPayload)
        assertEquals(1, imported.getOrThrow())

        val saved = repo.macros.first()
        assertEquals(1, saved.size)
        assertEquals("Test Macro", saved.first().name)
        assertEquals(4, saved.first().steps.size)
        db.close()
    }

    @Test
    fun screenDetector_matchesColorAndTemplate() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val detector = ScreenDetector(context)

        val screen = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(screen)
        canvas.drawColor(Color.BLACK)
        val paint = Paint().apply { color = Color.rgb(16, 185, 129) }
        canvas.drawRect(Rect(40, 50, 80, 90), paint)

        val colorMatch = detector.matchColor(screen, 60, 70, "#10B981", tolerancePct = 5)
        assertTrue(colorMatch.matched)
        assertEquals("#10B981", colorMatch.sampledHex)

        val template = Bitmap.createBitmap(screen, 44, 54, 24, 24)
        val imgMatch = detector.matchTemplate(screen, template, null, minConfidencePct = 90)
        assertTrue(imgMatch.matched)
        assertTrue(imgMatch.confidencePct >= 95)

        template.recycle()
        screen.recycle()
    }

    @Test
    fun interpreter_executesLoopVariablesAndBranching() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())
        val detector = ScreenDetector(context)
        val coordinator = OverlayCoordinator()
        val interpreter = MacroInterpreter(repo, detector, coordinator)

        val loopId = "l1"
        val macro = MacroDefinition(
            id = "logic-test",
            name = "Logic Engine Test",
            loopCount = 1,
            steps = listOf(
                SetVariableStep(variableName = "score", operation = VarOperation.SET, valueExpression = "0"),
                LoopStartStep(id = loopId, iterations = 4),
                SetVariableStep(variableName = "score", operation = VarOperation.INCREMENT, valueExpression = "5"),
                LoopEndStep(loopStartId = loopId),
                IfVariableStep(
                    variableName = "score",
                    comparator = VarComparator.EQUALS,
                    compareValue = "20",
                    thenAction = BranchAction.CONTINUE_NEXT,
                    elseAction = BranchAction.STOP_MACRO
                ),
                SetVariableStep(variableName = "verified", operation = VarOperation.SET, valueExpression = "true")
            )
        )

        interpreter.start(macro)
        var attempts = 0
        while (interpreter.state.value.status == ExecutionStatus.RUNNING && attempts < 60) {
            delay(50)
            attempts++
        }

        val finalState = interpreter.state.value
        assertEquals(ExecutionStatus.IDLE, finalState.status)
        assertEquals("20", finalState.variables["score"])
        assertEquals("true", finalState.variables["verified"])
        db.close()
    }

    @Test
    fun overlayCoordinator_preservesPickerDraftAndRoiState() = runBlocking {
        val coordinator = OverlayCoordinator()
        coordinator.setOverlayShowing(true, "m1", "Macro 1")
        coordinator.setOverlayStudioOpen(true, editingStepId = "step-img")
        assertTrue(coordinator.overlayState.value.showOverlayStudio)
        assertEquals("step-img", coordinator.overlayState.value.studioEditingStepId)

        val initialReq = PickerRequest(
            macroId = "m1",
            stepId = "step-img",
            mode = PickerMode.PICK_SEARCH_ROI,
            initialLeft = 100,
            initialTop = 200,
            initialRight = 500,
            initialBottom = 700
        )
        coordinator.startPicker(initialReq)
        assertEquals(PickerMode.PICK_SEARCH_ROI, coordinator.overlayState.value.pickerMode)
        assertEquals(false, coordinator.overlayState.value.showOverlayStudio)

        // Simulate user dragging handles then tapping Switch App
        val draggedReq = initialReq.copy(initialLeft = 140, initialTop = 260, initialRight = 620, initialBottom = 880)
        coordinator.updatePendingPickerRequest(draggedReq)
        coordinator.setPickerPassThrough(true)
        assertTrue(coordinator.overlayState.value.isPickerPassThrough)
        assertEquals(620, coordinator.overlayState.value.pendingPickerRequest?.initialRight)

        // Returning to Place Target preserves dragged bounds
        coordinator.setPickerPassThrough(false)
        assertEquals(140, coordinator.overlayState.value.pendingPickerRequest?.initialLeft)

        coordinator.publishPickerResult(PickerResult.RoiPicked("m1", "step-img", Rect(140, 260, 620, 880)))
        assertEquals(PickerMode.NONE, coordinator.overlayState.value.pickerMode)

        // Verify IfImageStep ROI survives repository save/load
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())
        repo.saveMacro(
            MacroDefinition(
                id = "m1",
                name = "Vision Macro",
                steps = listOf(
                    IfImageStep(
                        id = "step-img",
                        templateId = "tpl-1",
                        templateName = "Button",
                        searchLeft = 140,
                        searchTop = 260,
                        searchRight = 620,
                        searchBottom = 880
                    )
                )
            )
        )
        val loaded = repo.getMacro("m1")!!.steps.first() as IfImageStep
        assertEquals(140, loaded.searchLeft)
        assertEquals(880, loaded.searchBottom)
        db.close()
    }

    @Test
    fun macro_injectionMode_persistsAndDefaultsCleanly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())

        val stylusMacro = MacroDefinition(
            id = "stylus-1",
            name = "Game Keyboard & Touch Flow",
            injectionMode = InputInjectionMode.CO_TOUCH_MULTIPLEX,
            touchGuardPolicy = TouchGuardPolicy.YIELD_350MS,
            steps = listOf(
                KeyPressStep(
                    customKeyCode = KeyEvent.KEYCODE_Q,
                    keyLabel = "Q",
                    holdMs = 50L,
                    metaState = KeyEvent.META_SHIFT_ON
                )
            )
        )
        repo.saveMacro(stylusMacro)

        val loaded = repo.getMacro("stylus-1")!!
        assertEquals(InputInjectionMode.CO_TOUCH_MULTIPLEX, loaded.injectionMode)
        assertEquals(TouchGuardPolicy.YIELD_350MS, loaded.touchGuardPolicy)
        val loadedKeyStep = loaded.steps.first() as KeyPressStep
        assertEquals(KeyEvent.KEYCODE_Q, loadedKeyStep.resolvedKeyCode())
        assertEquals(50L, loadedKeyStep.holdMs)
        assertEquals(KeyEvent.META_SHIFT_ON, loadedKeyStep.metaState)
        assertEquals("Shift+Q", loadedKeyStep.formattedBinding())

        val legacyBundleJson = """
            {
              "schemaVersion": 1,
              "exportedAt": 1700000000000,
              "macros": [
                {
                  "id": "legacy-1",
                  "name": "Legacy Flow",
                  "steps": []
                }
              ]
            }
        """.trimIndent()
        val count = repo.importMacrosFromJson(legacyBundleJson).getOrThrow()
        assertEquals(1, count)
        val importedLegacy = repo.macros.first().first { it.name == "Legacy Flow" }
        assertEquals(InputInjectionMode.TOUCHSCREEN, importedLegacy.injectionMode)
        assertEquals(TouchGuardPolicy.OFF, importedLegacy.touchGuardPolicy)
        db.close()
    }

    @Test
    fun interpreter_dispatchesPerStepAccount2AndAccount1ViaDualWebBridge() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())
        val detector = ScreenDetector(context)
        val coordinator = OverlayCoordinator()
        val dualWebBridge = DualWebInputBridge()

        val recorded = mutableListOf<String>()
        val delegate = object : DualWebMacroDelegate {
            override fun dispatchKey(
                keyCode: Int,
                holdMs: Long,
                metaState: Int,
                targetOverride: WebViewTarget
            ): Boolean {
                recorded += "KEY:$keyCode:${targetOverride.name}"
                return true
            }

            override fun dispatchTap(
                screenX: Float,
                screenY: Float,
                holdMs: Long,
                jitterPx: Int,
                targetOverride: WebViewTarget
            ): Boolean {
                recorded += "TAP:${screenX.toInt()},${screenY.toInt()}:${targetOverride.name}"
                return true
            }

            override fun dispatchSwipe(
                startX: Float,
                startY: Float,
                endX: Float,
                endY: Float,
                durationMs: Long,
                steps: Int,
                targetOverride: WebViewTarget
            ): Boolean = true

            override fun describeTarget(targetOverride: WebViewTarget): String = targetOverride.name
        }
        dualWebBridge.attach(delegate)

        val interpreter = MacroInterpreter(repo, detector, coordinator, dualWebBridge)
        val macro = MacroDefinition(
            id = "dual-web-routing-test",
            name = "Dual Web Routing Test",
            loopCount = 1,
            steps = listOf(
                KeyPressStep(
                    label = "Buff A2",
                    customKeyCode = KeyEvent.KEYCODE_1,
                    keyLabel = "1",
                    targetWebView = WebViewTarget.ACCOUNT_2
                ),
                TapStep(
                    label = "Tap A1 Main",
                    x = 320f,
                    y = 1400f,
                    targetWebView = WebViewTarget.ACCOUNT_1
                ),
                KeyPressStep(
                    label = "Action A1 Main",
                    customKeyCode = KeyEvent.KEYCODE_SPACE,
                    keyLabel = "Space",
                    targetWebView = WebViewTarget.ACCOUNT_1
                )
            )
        )

        interpreter.start(macro)
        var attempts = 0
        while (interpreter.state.value.status == ExecutionStatus.RUNNING && attempts < 80) {
            delay(40)
            attempts++
        }

        assertEquals(ExecutionStatus.IDLE, interpreter.state.value.status)
        assertEquals(
            listOf(
                "KEY:${KeyEvent.KEYCODE_1}:ACCOUNT_2",
                "TAP:320,1400:ACCOUNT_1",
                "KEY:${KeyEvent.KEYCODE_SPACE}:ACCOUNT_1"
            ),
            recorded
        )

        // Detaching Dual WebView should fail preflight cleanly with clear Dual Flyff message
        dualWebBridge.detach(delegate)
        interpreter.start(macro)
        assertEquals(ExecutionStatus.ERROR, interpreter.state.value.status)
        assertTrue(interpreter.state.value.errorMessage?.contains("Dual Flyff") == true)
        db.close()
    }

    @Test
    fun interpreter_executesFunctionsNestedCallsAndRecursionGuard() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())
        val detector = ScreenDetector(context)
        val coordinator = OverlayCoordinator()
        val interpreter = MacroInterpreter(repo, detector, coordinator)

        val fnInner = MacroFunction(
            id = "fn-inner",
            name = "AddThree",
            steps = listOf(
                SetVariableStep(variableName = "total", operation = VarOperation.INCREMENT, valueExpression = "3")
            )
        )
        val fnOuter = MacroFunction(
            id = "fn-outer",
            name = "Combo",
            steps = listOf(
                CallFunctionStep(label = "Call Inner", functionId = fnInner.id, functionName = fnInner.name, repeatCount = 2),
                SetVariableStep(variableName = "total", operation = VarOperation.INCREMENT, valueExpression = "1")
            )
        )
        val fnRecursive = MacroFunction(
            id = "fn-self",
            name = "SelfCall",
            steps = listOf(
                SetVariableStep(variableName = "depthHits", operation = VarOperation.INCREMENT, valueExpression = "1"),
                CallFunctionStep(label = "Recurse", functionId = "fn-self", functionName = "SelfCall", repeatCount = 1)
            )
        )
        val fnStop = MacroFunction(
            id = "fn-stop",
            name = "StopEarly",
            steps = listOf(
                SetVariableStep(variableName = "stoppedInFn", operation = VarOperation.SET, valueExpression = "yes"),
                StopStep(label = "Abort")
            )
        )

        val macro = MacroDefinition(
            id = "fn-engine-test",
            name = "Functions Engine Test",
            loopCount = 1,
            functions = listOf(fnInner, fnOuter, fnStop),
            steps = listOf(
                SetVariableStep(variableName = "total", operation = VarOperation.SET, valueExpression = "0"),
                // Combo runs twice -> each run calls AddThree(2x = +6) + 1 = +7 => total = 14
                CallFunctionStep(label = "Run Combo", functionId = fnOuter.id, functionName = fnOuter.name, repeatCount = 2),
                // Stop inside function must abort before afterStop is set
                CallFunctionStep(label = "Stop Now", functionId = fnStop.id, functionName = fnStop.name, repeatCount = 1),
                SetVariableStep(variableName = "afterStop", operation = VarOperation.SET, valueExpression = "should_not_run")
            )
        )

        interpreter.start(macro)
        var attempts = 0
        while (interpreter.state.value.status == ExecutionStatus.RUNNING && attempts < 80) {
            delay(40)
            attempts++
        }

        val finalState = interpreter.state.value
        assertEquals(ExecutionStatus.IDLE, finalState.status)
        assertEquals("14", finalState.variables["total"])
        assertEquals("yes", finalState.variables["stoppedInFn"])
        assertNull(finalState.variables["afterStop"])

        // Self-recursive function must trigger MAX_CALL_DEPTH error cleanly without hanging
        val recursiveMacro = MacroDefinition(
            id = "fn-recursion-test",
            name = "Recursion Guard Test",
            loopCount = 1,
            functions = listOf(fnRecursive),
            steps = listOf(
                SetVariableStep(variableName = "depthHits", operation = VarOperation.SET, valueExpression = "0"),
                CallFunctionStep(label = "Test Recursion", functionId = fnRecursive.id, functionName = fnRecursive.name, repeatCount = 1)
            )
        )
        interpreter.start(recursiveMacro)
        attempts = 0
        while (interpreter.state.value.status == ExecutionStatus.RUNNING && attempts < 80) {
            delay(40)
            attempts++
        }
        val recState = interpreter.state.value
        assertEquals(ExecutionStatus.ERROR, recState.status)
        assertEquals("16", recState.variables["depthHits"])
        assertTrue(recState.lastTelemetry.contains("Max function call depth"))
        db.close()
    }

    @Test
    fun scriptClipboardAndCloner_clonesStepsAndFunctionsWithRemappedIds() {
        val clipboard = ScriptClipboard()
        val loopStart = LoopStartStep(id = "loop-start-1", label = "Loop 2x", iterations = 2)
        val body = SetVariableStep(id = "set-1", label = "Bump", variableName = "x", operation = VarOperation.INCREMENT, valueExpression = "1")
        val branch = IfVariableStep(
            id = "if-1",
            variableName = "x",
            comparator = VarComparator.EQUALS,
            compareValue = "2",
            thenAction = BranchAction.JUMP_TO_STEP,
            thenJumpStepId = body.id,
            elseAction = BranchAction.CONTINUE_NEXT
        )
        val jump = JumpStep(id = "jump-1", targetStepId = loopStart.id)
        val loopEnd = LoopEndStep(id = "loop-end-1", loopStartId = loopStart.id)

        val fn = MacroFunction(
            id = "fn-orig",
            name = "FarmLoop",
            steps = listOf(loopStart, body, branch, jump, loopEnd)
        )

        clipboard.copyStep(body)
        val copiedStepItem = clipboard.current.value as ScriptClipboardItem.SingleStep
        val clonedSingle = MacroCloner.cloneStep(copiedStepItem.step) as SetVariableStep
        assertNotEquals(body.id, clonedSingle.id)
        assertEquals(body.variableName, clonedSingle.variableName)

        clipboard.copyFunction(fn)
        val copiedFnItem = clipboard.current.value as ScriptClipboardItem.FunctionClip
        val clonedFn1 = MacroCloner.cloneFunction(copiedFnItem.function, existingNames = setOf("FarmLoop"))
        val clonedFn2 = MacroCloner.cloneFunction(copiedFnItem.function, existingNames = setOf("FarmLoop", "FarmLoop (Copy)"))

        assertNotEquals(fn.id, clonedFn1.id)
        assertEquals("FarmLoop (Copy)", clonedFn1.name)
        assertEquals("FarmLoop (2)", clonedFn2.name)
        assertEquals(5, clonedFn1.steps.size)

        val clonedLoopStart = clonedFn1.steps[0] as LoopStartStep
        val clonedBody = clonedFn1.steps[1] as SetVariableStep
        val clonedBranch = clonedFn1.steps[2] as IfVariableStep
        val clonedJump = clonedFn1.steps[3] as JumpStep
        val clonedLoopEnd = clonedFn1.steps[4] as LoopEndStep

        assertNotEquals(loopStart.id, clonedLoopStart.id)
        assertNotEquals(body.id, clonedBody.id)
        assertEquals(clonedLoopStart.id, clonedLoopEnd.loopStartId)
        assertEquals(clonedBody.id, clonedBranch.thenJumpStepId)
        assertEquals(clonedLoopStart.id, clonedJump.targetStepId)

        clipboard.clear()
        assertNull(clipboard.current.value)
    }

    @Test
    fun repository_persistsAndExportsMacroFunctionsWithTemplates() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())

        val bmp = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.CYAN)
        val template = repo.saveTemplateBitmap("LootIcon", bmp)
        bmp.recycle()

        val fn = MacroFunction(
            id = "fn-loot",
            name = "CheckLoot",
            steps = listOf(
                IfImageStep(
                    id = "img-in-fn",
                    templateId = template.id,
                    templateName = template.name,
                    minConfidencePct = 85
                )
            )
        )
        val macro = MacroDefinition(
            id = "macro-fn-1",
            name = "Modular Run",
            functions = listOf(fn),
            steps = listOf(
                CallFunctionStep(label = "Call CheckLoot", functionId = fn.id, functionName = fn.name, repeatCount = 3)
            )
        )
        repo.saveMacro(macro)

        val reloaded = repo.getMacro(macro.id)!!
        assertEquals(1, reloaded.functions.size)
        assertEquals("CheckLoot", reloaded.functions.first().name)
        assertEquals(1, reloaded.functions.first().steps.size)

        val exportedJson = repo.exportMacrosToJson(setOf(macro.id))
        repo.deleteMacro(macro.id)
        repo.deleteTemplate(template.id)

        val importedCount = repo.importMacrosFromJson(exportedJson).getOrThrow()
        assertEquals(1, importedCount)

        val importedMacro = repo.macros.first().first()
        assertEquals(1, importedMacro.functions.size)
        val importedImgStep = importedMacro.functions.first().steps.first() as IfImageStep
        assertNotEquals(template.id, importedImgStep.templateId)
        val restoredBitmap = repo.loadTemplateBitmap(importedImgStep.templateId)
        assertTrue(restoredBitmap != null)
        restoredBitmap?.recycle()
        db.close()
    }

    @Test
    fun templateCropEditor_trimsBy1PxPerSideAndPersistsCopyOrOverwrite() = runBlocking {
        // Verify pure 4-side crop bounds math + min-size clamping
        val rect = computeTemplateCropRect(
            sourceWidth = 20,
            sourceHeight = 16,
            trimLeft = 1,
            trimTop = 2,
            trimRight = 3,
            trimBottom = 4,
            minSize = 4
        )
        assertEquals(1, rect.left)
        assertEquals(2, rect.top)
        assertEquals(16, rect.width)
        assertEquals(10, rect.height)

        val clamped = computeTemplateCropRect(
            sourceWidth = 10,
            sourceHeight = 10,
            trimLeft = 5,
            trimTop = 5,
            trimRight = 9,
            trimBottom = 9,
            minSize = 4
        )
        assertEquals(4, clamped.width)
        assertEquals(4, clamped.height)

        // Verify repository cropTemplate saves exact trimmed pixels for both Copy and Overwrite
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())

        // 20x16 bitmap with RED outer border and GREEN inner rectangle [2..17) x [1..13)
        val bmp = Bitmap.createBitmap(20, 16, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        val canvas = Canvas(bmp)
        val innerPaint = Paint().apply { color = Color.GREEN }
        canvas.drawRect(Rect(2, 1, 17, 13), innerPaint)

        val original = repo.saveTemplateBitmap("TargetBtn", bmp)
        bmp.recycle()

        // Save Copy trimmed by L=2, T=1, R=3, B=3 -> 15x12 pure GREEN
        val copy = repo.cropTemplate(
            templateId = original.id,
            trimLeft = 2,
            trimTop = 1,
            trimRight = 3,
            trimBottom = 3,
            saveAsNew = true
        )!!
        assertNotEquals(original.id, copy.id)
        assertEquals("TargetBtn (Crop)", copy.name)
        assertEquals(15, copy.width)
        assertEquals(12, copy.height)
        val copyBmp = repo.loadTemplateBitmap(copy.id)!!
        assertEquals(15, copyBmp.width)
        assertEquals(12, copyBmp.height)
        assertEquals(Color.GREEN, copyBmp.getPixel(0, 0))
        assertEquals(Color.GREEN, copyBmp.getPixel(14, 11))
        copyBmp.recycle()

        // Original should remain untouched at 20x16 with RED corner
        val origStillBmp = repo.loadTemplateBitmap(original.id)!!
        assertEquals(20, origStillBmp.width)
        assertEquals(16, origStillBmp.height)
        assertEquals(Color.RED, origStillBmp.getPixel(0, 0))
        origStillBmp.recycle()

        // Overwrite original by trimming 1px on each side -> 18x14
        val overwritten = repo.cropTemplate(
            templateId = original.id,
            trimLeft = 1,
            trimTop = 1,
            trimRight = 1,
            trimBottom = 1,
            saveAsNew = false
        )!!
        assertEquals(original.id, overwritten.id)
        assertEquals(18, overwritten.width)
        assertEquals(14, overwritten.height)
        val overwrittenBmp = repo.loadTemplateBitmap(original.id)!!
        assertEquals(18, overwrittenBmp.width)
        assertEquals(14, overwrittenBmp.height)
        // Pixel (1,0) of trimmed bitmap was (2,1) of original -> GREEN
        assertEquals(Color.GREEN, overwrittenBmp.getPixel(1, 0))
        overwrittenBmp.recycle()

        repo.deleteTemplate(original.id)
        repo.deleteTemplate(copy.id)
        db.close()
    }

    @Test
    fun webViewTextInputHelper_resolvesQuickTypeTargetAndEscapesJsPayload() {
        assertEquals(1, WebViewTextInputHelper.resolveQuickTypeInitialAccount(DualLayoutMode.SINGLE_ACCT_1, 2))
        assertEquals(2, WebViewTextInputHelper.resolveQuickTypeInitialAccount(DualLayoutMode.SINGLE_ACCT_2, 1))
        assertEquals(1, WebViewTextInputHelper.resolveQuickTypeInitialAccount(DualLayoutMode.SPLIT_50_50, 1))
        assertEquals(2, WebViewTextInputHelper.resolveQuickTypeInitialAccount(DualLayoutMode.SPLIT_50_50, 2))

        val sampleText = "/say \"Hello\" \\ world"
        val jsWithEnter = WebViewTextInputHelper.buildJsTextInjectionScript(
            text = sampleText,
            pressEnter = true
        )
        assertTrue(jsWithEnter.contains("var str = ${org.json.JSONObject.quote(sampleText)};"))
        assertTrue(jsWithEnter.contains("""\"Hello\""""))
        assertTrue(jsWithEnter.contains("""\\"""))
        assertTrue(jsWithEnter.contains("if (true)"))
        assertTrue(jsWithEnter.contains("key: 'Enter'"))

        val jsNoEnter = WebViewTextInputHelper.buildJsTextInjectionScript(
            text = "125000",
            pressEnter = false
        )
        assertTrue(jsNoEnter.contains("""var str = "125000";"""))
        assertTrue(jsNoEnter.contains("if (false)"))
    }

    @Test
    fun keepLoginReset_protectsAuthKeysAndPersistsAutoResetPref() {
        // Auth, session, account, character, and server keys MUST NEVER be pruned
        assertFalse(WebViewTextInputHelper.shouldPruneLocalStorageKey("auth_token"))
        assertFalse(WebViewTextInputHelper.shouldPruneLocalStorageKey("flyff_last_character"))
        assertFalse(WebViewTextInputHelper.shouldPruneLocalStorageKey("gala_session_id"))
        assertFalse(WebViewTextInputHelper.shouldPruneLocalStorageKey("autoLoginAccount"))
        assertFalse(WebViewTextInputHelper.shouldPruneLocalStorageKey("user_keyboard_token"))
        assertFalse(WebViewTextInputHelper.shouldPruneLocalStorageKey("unrelated_setting"))

        // Explicit non-auth keyboard/IME UI keys are pruned cleanly
        assertTrue(WebViewTextInputHelper.shouldPruneLocalStorageKey("virtualKeyboardEnabled"))
        assertTrue(WebViewTextInputHelper.shouldPruneLocalStorageKey("flyff_inputMode"))
        assertTrue(WebViewTextInputHelper.shouldPruneLocalStorageKey("ime_focusState"))
        assertTrue(WebViewTextInputHelper.shouldPruneLocalStorageKey("touchMode"))

        val resetJs = WebViewTextInputHelper.buildKeepLoginKeyboardResetScript()
        assertTrue(resetJs.contains("window.sessionStorage.clear()"))
        assertTrue(resetJs.contains("window.caches.keys()"))
        assertTrue(resetJs.contains("\"token\""))
        assertTrue(resetJs.contains("\"keyboard\""))

        // DualWebPreferences defaults autoResetCacheOnReopen = true and persists updates
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DualWebPreferences(context)
        assertTrue(prefs.load().autoResetCacheOnReopen)
        prefs.save(DualWebConfigState(autoResetCacheOnReopen = false))
        assertFalse(prefs.load().autoResetCacheOnReopen)
        prefs.save(DualWebConfigState(autoResetCacheOnReopen = true))
        assertTrue(prefs.load().autoResetCacheOnReopen)
    }

    private fun fillBitmapRect(bmp: Bitmap, left: Int, top: Int, right: Int, bottom: Int, color: Int) {
        val l = left.coerceIn(0, bmp.width)
        val t = top.coerceIn(0, bmp.height)
        val r = right.coerceIn(l, bmp.width)
        val b = bottom.coerceIn(t, bmp.height)
        val rw = r - l
        val rh = b - t
        if (rw <= 0 || rh <= 0) return
        val row = IntArray(rw) { color }
        for (y in t until b) {
            bmp.setPixels(row, 0, rw, l, y, rw, 1)
        }
    }

    @Test
    fun flyffVisionAnalyzer_detectsOnlyTenSlotKeybarAndTightCropsIcons() {
        val w = 1812
        val h = 1088
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fillBitmapRect(bmp, 0, 0, w, h, Color.rgb(60, 85, 110)) // Game world background

        val tanBorder = Color.rgb(180, 130, 95)
        val creamBg = Color.rgb(245, 240, 230)
        val emptyDot = Color.rgb(175, 170, 165)

        // Draw Original View bottom taskbar strip
        fillBitmapRect(bmp, 0, h - 56, w, h, tanBorder)
        // Left 20-slot clickable shortcut tray (MUST BE IGNORED by 1-0 key scanner)
        fillBitmapRect(bmp, 262, h - 50, 1102, h - 10, creamBg)
        val decoyColor = Color.rgb(220, 40, 180)
        for (i in 0 until 6) {
            val dx = 270 + i * 41
            fillBitmapRect(bmp, dx, h - 49, dx + 37, h - 12, decoyColor)
        }

        // Right 10-slot 1-0 keybar tray (x=1112..1523, icon y=[h-49 .. h-12], 37x37 icons, 41.1px pitch)
        fillBitmapRect(bmp, 1112, h - 50, 1523, h - 10, creamBg)
        val occupiedIndices = setOf(0, 1, 7, 9) // Keys 1, 2, 8, 0
        val iconTop = h - 49
        val iconSize = 37
        for (slot in 0 until 10) {
            val sx = (1114f + slot * 41.1f).toInt()
            if (slot in occupiedIndices) {
                val frameColor = Color.rgb(45, 25, 15)
                val fillColor = when (slot) {
                    0 -> Color.rgb(240, 190, 30)
                    1 -> Color.rgb(230, 70, 40)
                    7 -> Color.rgb(40, 170, 235)
                    else -> Color.rgb(160, 80, 230)
                }
                fillBitmapRect(bmp, sx, iconTop, sx + iconSize, iconTop + iconSize, frameColor)
                fillBitmapRect(bmp, sx + 2, iconTop + 2, sx + iconSize - 2, iconTop + iconSize - 2, fillColor)
            } else {
                // Tiny gray center dot in empty slot
                fillBitmapRect(
                    bmp,
                    sx + iconSize / 2 - 2,
                    iconTop + iconSize / 2 - 2,
                    sx + iconSize / 2 + 2,
                    iconTop + iconSize / 2 + 2,
                    emptyDot
                )
            }
        }

        val scan = FlyffVisionAnalyzer.autoDetectOriginalViewTaskbarSlots(
            frame = bmp,
            paneRect = Rect(0, 0, w, h),
            owner = WebViewTarget.ACCOUNT_2
        )
        assertEquals(listOf(0, 1, 7, 9), scan.detectedSlots.map { it.slotIndex })
        assertTrue(scan.previewStripBitmap != null)
        scan.detectedSlots.forEach { slot ->
            assertTrue("Expected tight icon width ~37px, got ${slot.bounds.width()}", slot.bounds.width() in 35..39)
            assertTrue("Expected tight icon height ~37px, got ${slot.bounds.height()}", slot.bounds.height() in 35..39)
            slot.iconBitmap.recycle()
        }
        scan.previewStripBitmap?.recycle()
        bmp.recycle()
    }

    @Test
    fun flyffVisionAnalyzer_verifiesRealFlyffScreenshotsIfPresent() {
        val dir = java.io.File("../public/assets/aistudio")
        if (!dir.exists()) return

        // 1. Single Original View (Fork22 RM: all 10 1-0 slots occupied)
        val f441 = java.io.File(dir, "Screenshot_20261121_090441_ShizuMacro_c5dac6e3.jpg")
        if (f441.exists()) {
            val bmp = BitmapFactory.decodeFile(f441.absolutePath)!!
            val scan = FlyffVisionAnalyzer.autoDetectOriginalViewTaskbarSlots(
                bmp, Rect(0, 0, bmp.width, bmp.height), WebViewTarget.ACCOUNT_2
            )
            assertEquals((0..9).toList(), scan.detectedSlots.map { it.slotIndex })
            scan.detectedSlots.forEach { slot ->
                assertTrue("RM slot ${slot.slotIndex} w=${slot.bounds.width()}", slot.bounds.width() in 33..41)
                assertTrue("RM slot ${slot.slotIndex} h=${slot.bounds.height()}", slot.bounds.height() in 33..41)
                slot.iconBitmap.recycle()
            }
            scan.previewStripBitmap?.recycle()
            bmp.recycle()
        }

        // 2. Single Original View (oOXOo2 Psy: 7 slots at 0,1,2,3,7,8,9 + distracting items on left 20 click bar)
        val f429 = java.io.File(dir, "Screenshot_20261121_090429_ShizuMacro_80558010.jpg")
        if (f429.exists()) {
            val bmp = BitmapFactory.decodeFile(f429.absolutePath)!!
            val scan = FlyffVisionAnalyzer.autoDetectOriginalViewTaskbarSlots(
                bmp, Rect(0, 0, bmp.width, bmp.height), WebViewTarget.ACCOUNT_1
            )
            assertEquals(listOf(0, 1, 2, 3, 7, 8, 9), scan.detectedSlots.map { it.slotIndex })
            scan.detectedSlots.forEach { it.iconBitmap.recycle() }
            scan.previewStripBitmap?.recycle()
            bmp.recycle()
        }

        // 3. Split Original View (090407: Top=Fork22 10/10, Bottom=oOXOo2 7/10)
        val f407 = java.io.File(dir, "Screenshot_20261121_090407_ShizuMacro_418ba37f.jpg")
        if (f407.exists()) {
            val bmp = BitmapFactory.decodeFile(f407.absolutePath)!!
            val topScan = FlyffVisionAnalyzer.autoDetectOriginalViewTaskbarSlots(
                bmp, Rect(0, 0, bmp.width, bmp.height / 2), WebViewTarget.ACCOUNT_2
            )
            val botScan = FlyffVisionAnalyzer.autoDetectOriginalViewTaskbarSlots(
                bmp, Rect(0, bmp.height / 2, bmp.width, bmp.height), WebViewTarget.ACCOUNT_1
            )
            assertEquals((0..9).toList(), topScan.detectedSlots.map { it.slotIndex })
            assertEquals(listOf(0, 1, 2, 3, 7, 8, 9), botScan.detectedSlots.map { it.slotIndex })
            topScan.detectedSlots.forEach { it.iconBitmap.recycle() }
            botScan.detectedSlots.forEach { it.iconBitmap.recycle() }
            topScan.previewStripBitmap?.recycle()
            botScan.previewStripBitmap?.recycle()
            bmp.recycle()
        }

        // 4. Mobile UI 50/50 Split (090341: Top Fork22 HP 8402/10928 = 77% depleted!, Bottom oOXOo2 full + Active Buff tiles)
        val f341 = java.io.File(dir, "Screenshot_20261121_090341_ShizuMacro_62e243ba.jpg")
        if (f341.exists()) {
            val bmp = BitmapFactory.decodeFile(f341.absolutePath)!!
            val topHud = FlyffVisionAnalyzer.autoDetectMobileHudBars(
                bmp, Rect(0, 0, bmp.width, bmp.height / 2), WebViewTarget.ACCOUNT_2
            )
            val topHp = FlyffVisionAnalyzer.measureBarFillPercent(bmp, topHud.hpBar!!)
            val topMp = FlyffVisionAnalyzer.measureBarFillPercent(bmp, topHud.mpBar!!)
            val topFp = FlyffVisionAnalyzer.measureBarFillPercent(bmp, topHud.fpBar!!)
            // Fork22's HP is 8402 / 10928 (~76.9%)! Must NOT falsely read 100% from dark maroon empty trough!
            assertTrue("Real Fork22 depleted HP (8402/10928=77%) must read 68..85%, got $topHp% (rect=${topHud.hpBar!!.rect})", topHp in 68..85)
            assertTrue("Real Fork22 full MP (9608/9608) must read >= 96%, got $topMp%", topMp >= 96)
            assertTrue("Real Fork22 full FP (284/284) must read >= 96%, got $topFp%", topFp >= 96)

            val topTiles = FlyffVisionAnalyzer.autoDetectActiveBuffTiles(bmp, topHud)
            val botHud = FlyffVisionAnalyzer.autoDetectMobileHudBars(
                bmp, Rect(0, bmp.height / 2, bmp.width, bmp.height), WebViewTarget.ACCOUNT_1
            )
            val botHp = FlyffVisionAnalyzer.measureBarFillPercent(bmp, botHud.hpBar!!)
            val botMp = FlyffVisionAnalyzer.measureBarFillPercent(bmp, botHud.mpBar!!)
            val botFp = FlyffVisionAnalyzer.measureBarFillPercent(bmp, botHud.fpBar!!)
            assertTrue("Real oOXOo2 full HP (12803/12803) must read >= 96%, got $botHp%", botHp >= 96)
            assertTrue("Real oOXOo2 full MP (7334/7334) must read >= 96%, got $botMp%", botMp >= 96)
            assertTrue("Real oOXOo2 full FP (278/278) must read >= 96%, got $botFp%", botFp >= 96)

            val botTiles = FlyffVisionAnalyzer.autoDetectActiveBuffTiles(bmp, botHud)
            assertTrue("Expected >= 12 active buffs on top pane, got ${topTiles.size}", topTiles.size >= 12)
            assertTrue("Expected >= 10 active buffs on bot pane, got ${botTiles.size}", botTiles.size >= 10)
            topTiles.forEach {
                val w = it.tileBounds.width()
                val h = it.tileBounds.height()
                assertTrue("Buff tile width ~32-48px, got $w", w in 28..50)
                assertTrue("Buff tile must stay square (|w-h| <= 4), got ${w}x${h}", kotlin.math.abs(w - h) <= 4)
                it.fullTileBitmap.recycle(); it.artCropBitmap.recycle(); it.timerStripBitmap.recycle()
            }
            botTiles.forEach {
                val w = it.tileBounds.width()
                val h = it.tileBounds.height()
                assertTrue("Bot buff tile must stay square (|w-h| <= 4), got ${w}x${h}", kotlin.math.abs(w - h) <= 4)
                it.fullTileBitmap.recycle(); it.artCropBitmap.recycle(); it.timerStripBitmap.recycle()
            }
            bmp.recycle()
        }
    }

    @Test
    fun overlayCoordinator_hideAndResumeStudioPreservesExactViewportAndFunction() {
        val coordinator = OverlayCoordinator()
        coordinator.setOverlayShowing(true, "macro-1", "RM Buffs")
        coordinator.setOverlayStudioOpen(true, editingStepId = "step-9", activeFunctionId = "fn-buffs")
        coordinator.updateStudioViewport(
            activeFunctionId = "fn-buffs",
            editingStepId = "step-9",
            scrollIndex = 6,
            scrollOffset = 128
        )

        // Hiding Overlay Studio keeps exact function, step, and scroll position
        coordinator.hideOverlayStudio()
        val hidden = coordinator.overlayState.value
        assertFalse(hidden.showOverlayStudio)
        assertTrue(hidden.isStudioHidden)
        assertEquals("fn-buffs", hidden.studioActiveFunctionId)
        assertEquals("step-9", hidden.studioEditingStepId)
        assertEquals(6, hidden.studioScrollIndex)
        assertEquals(128, hidden.studioScrollOffset)

        // Resuming returns directly to the exact same function, step, and scroll offset
        coordinator.resumeOverlayStudio()
        val resumed = coordinator.overlayState.value
        assertTrue(resumed.showOverlayStudio)
        assertFalse(resumed.isStudioHidden)
        assertEquals("fn-buffs", resumed.studioActiveFunctionId)
        assertEquals("step-9", resumed.studioEditingStepId)
        assertEquals(6, resumed.studioScrollIndex)
        assertEquals(128, resumed.studioScrollOffset)

        // Switching to a different macro cleanly resets viewport state
        coordinator.setLoadedMacro("macro-2", "Psy Fight")
        val switched = coordinator.overlayState.value
        assertEquals("macro-2", switched.activeMacroId)
        assertNull(switched.studioActiveFunctionId)
        assertNull(switched.studioEditingStepId)
        assertEquals(0, switched.studioScrollIndex)
        assertEquals(0, switched.studioScrollOffset)
    }

    @Test
    fun flyffVisionAnalyzer_readsFullHpMpFpAt100PercentAndRejectsPartyBars() {
        val w = 900
        val h = 900
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        // Bright snowy world background
        fillBitmapRect(bmp, 0, 0, w, h, Color.rgb(212, 218, 224))

        // Top-left Mobile HUD panel background
        fillBitmapRect(bmp, 12, 12, 310, 118, Color.rgb(34, 38, 46))
        // Portrait square on the left
        fillBitmapRect(bmp, 18, 18, 102, 108, Color.rgb(130, 110, 95))

        // Full HP, MP, FP bars from x=112..294 with white numeric overlay gap in center
        val barLeft = 112
        val barRight = 294
        // HP bar (red)
        fillBitmapRect(bmp, barLeft, 36, barRight, 50, Color.rgb(218, 46, 52))
        fillBitmapRect(bmp, 194, 39, 212, 47, Color.rgb(245, 245, 245)) // "100%" white text gap
        // Reddish window border accent right of HP (must NOT skew HP width vs MP/FP!)
        fillBitmapRect(bmp, barRight + 4, 34, barRight + 10, 52, Color.rgb(188, 66, 62))
        // MP bar (blue)
        fillBitmapRect(bmp, barLeft, 58, barRight, 72, Color.rgb(42, 138, 232))
        fillBitmapRect(bmp, 194, 61, 212, 69, Color.rgb(245, 245, 245))
        // FP bar (green)
        fillBitmapRect(bmp, barLeft, 80, barRight, 94, Color.rgb(48, 198, 72))
        fillBitmapRect(bmp, 194, 83, 212, 91, Color.rgb(245, 245, 245))

        // Active buff dark container under HUD with 3 real square icons (36x36, pitch 39)
        fillBitmapRect(bmp, 16, 124, 152, 174, Color.rgb(28, 30, 36))
        val buffColors = listOf(
            Pair(Color.rgb(220, 60, 180), Color.rgb(255, 210, 60)),
            Pair(Color.rgb(40, 170, 240), Color.rgb(250, 240, 120)),
            Pair(Color.rgb(230, 110, 40), Color.rgb(80, 230, 210))
        )
        buffColors.forEachIndexed { idx, (c1, c2) ->
            val bx = 20 + idx * 39
            val by = 128
            fillBitmapRect(bmp, bx, by, bx + 36, by + 36, Color.rgb(40, 44, 52))
            fillBitmapRect(bmp, bx + 3, by + 3, bx + 33, by + 20, c1)
            fillBitmapRect(bmp, bx + 6, by + 8, bx + 30, by + 18, c2)
            // Bottom green timer text band
            fillBitmapRect(bmp, bx + 3, by + 23, bx + 33, by + 33, Color.rgb(20, 24, 28))
            fillBitmapRect(bmp, bx + 7, by + 25, bx + 28, by + 31, Color.rgb(70, 245, 95))
        }

        // Right-adjacent Party member bar ("eRo...") with thin red HP stripe at top & flat pale lower half
        val px = 176
        val py = 128
        fillBitmapRect(bmp, px, py, px + 140, py + 36, Color.rgb(198, 200, 204))
        fillBitmapRect(bmp, px + 2, py + 2, px + 136, py + 8, Color.rgb(220, 55, 55))

        val hud = FlyffVisionAnalyzer.autoDetectMobileHudBars(
            frame = bmp,
            paneRect = Rect(0, 0, w, h),
            owner = WebViewTarget.ACCOUNT_1
        )
        val hpPct = FlyffVisionAnalyzer.measureBarFillPercent(bmp, hud.hpBar!!)
        val mpPct = FlyffVisionAnalyzer.measureBarFillPercent(bmp, hud.mpBar!!)
        val fpPct = FlyffVisionAnalyzer.measureBarFillPercent(bmp, hud.fpBar!!)
        assertTrue("Full HP bar should read >= 98%, got $hpPct%", hpPct >= 98)
        assertTrue("Full MP bar should read >= 98%, got $mpPct%", mpPct >= 98)
        assertTrue("Full FP bar should read >= 98%, got $fpPct%", fpPct >= 98)

        val tiles = FlyffVisionAnalyzer.autoDetectActiveBuffTiles(bmp, hud)
        assertEquals(
            "Should detect exactly the 3 real buffs and ignore Party bar; hudBuffGrid=${hud.buffGridRegion.toAndroidRect(w, h)}, tiles=${tiles.map { "r${it.row}c${it.col}:${it.tileBounds}" }}",
            3,
            tiles.size
        )
        tiles.forEach {
            assertTrue("Tile must be left of Party bar (x < 170), got ${it.tileBounds.left}", it.tileBounds.right < 172)
            it.fullTileBitmap.recycle()
            it.artCropBitmap.recycle()
            it.timerStripBitmap.recycle()
        }
        bmp.recycle()
    }

    @Test
    fun flyffIconMatcher_crossMatchesActiveBuffAgainstSavedStep2SkillIcon() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())

        // Create two distinct synthetic skill icons (Haste = red/gold flame, Heap Up = cyan/blue shield)
        val hasteBmp = Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)
        fillBitmapRect(hasteBmp, 0, 0, 36, 36, Color.rgb(40, 20, 20))
        fillBitmapRect(hasteBmp, 4, 4, 32, 32, Color.rgb(235, 72, 38))
        fillBitmapRect(hasteBmp, 10, 6, 26, 22, Color.rgb(255, 220, 55))
        val hasteTpl = repo.saveTemplateBitmap("Key Haste", hasteBmp)

        val heapBmp = Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)
        fillBitmapRect(heapBmp, 0, 0, 36, 36, Color.rgb(18, 30, 52))
        fillBitmapRect(heapBmp, 4, 4, 32, 32, Color.rgb(38, 148, 240))
        fillBitmapRect(heapBmp, 12, 8, 24, 24, Color.rgb(220, 248, 255))
        val heapTpl = repo.saveTemplateBitmap("Key Heap Up", heapBmp)

        // Simulate an active Haste buff tile with a dark green "19:42" timer band painted over the bottom 38%
        val activeTile = hasteBmp.copy(Bitmap.Config.ARGB_8888, true)
        fillBitmapRect(activeTile, 2, 23, 34, 34, Color.rgb(18, 24, 20))
        fillBitmapRect(activeTile, 6, 25, 30, 32, Color.rgb(64, 245, 92))
        val artCrop = Bitmap.createBitmap(activeTile, 2, 2, 32, 20)

        val savedKeys = listOf(
            ScannedSkillKey(
                id = "k-haste",
                label = "Haste",
                category = SkillCategory.PARTY_BUFF,
                owner = WebViewTarget.ACCOUNT_2,
                keyCode = KeyEvent.KEYCODE_1,
                keyLabel = "1",
                groupKey = TaskbarGroupKey.MAIN_1_0,
                slotIndex = 0,
                iconTemplateId = hasteTpl.id
            ),
            ScannedSkillKey(
                id = "k-heap",
                label = "Heap Up",
                category = SkillCategory.PARTY_BUFF,
                owner = WebViewTarget.ACCOUNT_2,
                keyCode = KeyEvent.KEYCODE_2,
                keyLabel = "2",
                groupKey = TaskbarGroupKey.MAIN_1_0,
                slotIndex = 1,
                iconTemplateId = heapTpl.id
            )
        )
        val pathMap = repo.getTemplatePathMap()
        val match = FlyffIconMatcher.identifyActiveBuffIcon(
            fullTileBitmap = activeTile,
            artCropBitmap = artCrop,
            catalogEntries = emptyList(),
            savedKeys = savedKeys,
            templatePathById = pathMap
        )

        assertTrue("Active buff tile should visually match Step 2 Haste key", match != null)
        assertEquals("Haste", match!!.label)
        assertEquals("k-haste", match.matchedSkillKeyId)
        assertTrue("Confidence should be strong (>= 70%), got ${match.confidencePct}%", match.confidencePct >= 70)

        hasteBmp.recycle()
        heapBmp.recycle()
        activeTile.recycle()
        artCrop.recycle()
        repo.deleteTemplate(hasteTpl.id)
        repo.deleteTemplate(heapTpl.id)
        db.close()
    }

    @Test
    fun flyffSetupController_saveProfileAutoCommitsPendingSlotsAndRetainsAllData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())
        val detector = ScreenDetector(context)
        val bridge = DualWebInputBridge()
        val ocrReader = BuffTimerOcrReader()
        val smartEngine = SmartFlyffEngine(repo, detector, bridge, ocrReader)
        val controller = FlyffSetupController(
            repository = repo,
            timerOcrReader = ocrReader,
            smartFlyffEngine = smartEngine,
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined),
            initialProfile = FlyffProfile(id = "prof-save-test", name = "My RM + Psy")
        )

        // Create a synthetic Original View taskbar bitmap with 2 icons (Slot 1 & Slot 2)
        val w = 1812
        val h = 1088
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fillBitmapRect(bmp, 0, 0, w, h, Color.rgb(60, 85, 110))
        fillBitmapRect(bmp, 0, h - 56, w, h, Color.rgb(180, 130, 95))
        fillBitmapRect(bmp, 1112, h - 50, 1523, h - 10, Color.rgb(245, 240, 230))
        val iconTop = h - 49
        for (slot in 0 until 10) {
            val sx = (1114f + slot * 41.1f).toInt()
            if (slot == 0 || slot == 1) {
                fillBitmapRect(bmp, sx, iconTop, sx + 37, iconTop + 37, Color.rgb(40, 25, 15))
                fillBitmapRect(
                    bmp,
                    sx + 2,
                    iconTop + 2,
                    sx + 35,
                    iconTop + 35,
                    if (slot == 0) Color.rgb(240, 70, 40) else Color.rgb(40, 175, 240)
                )
            } else {
                fillBitmapRect(bmp, sx + 16, iconTop + 16, sx + 20, iconTop + 20, Color.rgb(175, 170, 165))
            }
        }

        // Scan into pendingSlots (user does NOT tap manual Commit before tapping Save!)
        val fullRect = Rect(0, 0, w, h)
        controller.processOriginalViewCapture(
            frame = bmp,
            layoutMode = DualLayoutMode.SINGLE_ACCT_2,
            acct1Rect = Rect(),
            acct2Rect = fullRect
        )
        var waitTicks = 0
        while (controller.uiState.value.isBusy && waitTicks < 40) {
            delay(50)
            waitTicks++
        }
        assertEquals(2, controller.uiState.value.pendingSlots.size)

        // User taps Save directly — pendingSlots MUST be auto-committed and persisted!
        var savedProfile: FlyffProfile? = null
        controller.saveProfile { savedProfile = it }
        waitTicks = 0
        while (savedProfile == null && waitTicks < 40) {
            delay(50)
            waitTicks++
        }

        assertTrue("saveProfile callback should fire", savedProfile != null)
        assertEquals("Saved profile should retain both scanned keys", 2, savedProfile!!.skillKeys.size)

        // Reload fresh from Room to guarantee nothing was lost
        val reloaded = repo.getFlyffProfile("prof-save-test")!!
        assertEquals(2, reloaded.skillKeys.size)
        assertEquals("My RM + Psy", reloaded.name)

        if (!bmp.isRecycled) bmp.recycle()
        db.close()
    }

    private fun drawFlyffVitalBar(
        bmp: Bitmap,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        fillPct: Int,
        vitalType: VitalType
    ) {
        val w = right - left
        val filledW = ((w * fillPct) / 100f).toInt().coerceIn(0, w)
        val (bevelColor, emptyTrackColor, filledColor) = when (vitalType) {
            VitalType.HP -> Triple(Color.rgb(120, 42, 42), Color.rgb(92, 34, 34), Color.rgb(220, 44, 48))
            VitalType.MP -> Triple(Color.rgb(38, 66, 118), Color.rgb(30, 52, 92), Color.rgb(42, 140, 236))
            VitalType.FP -> Triple(Color.rgb(38, 98, 48), Color.rgb(30, 78, 38), Color.rgb(52, 204, 74))
        }
        // Full outer tinted 1px bevel + dark-tinted depleted trough (exactly like Flyff Mobile UI!)
        fillBitmapRect(bmp, left, top, right, bottom, bevelColor)
        fillBitmapRect(bmp, left + 1, top + 1, right - 1, bottom - 1, emptyTrackColor)
        if (filledW > 0) {
            fillBitmapRect(bmp, left + 1, top + 1, (left + filledW).coerceAtMost(right - 1), bottom - 1, filledColor)
        }
        // White centered numeric text ("8402 / 10928") with 1px dark outline across center 28%
        val mid = (left + right) / 2
        val textHalf = (w * 0.14f).toInt().coerceAtLeast(8)
        fillBitmapRect(bmp, mid - textHalf, top + 3, mid + textHalf, bottom - 3, Color.rgb(245, 245, 245))
    }

    @Test
    fun flyffVisionAnalyzer_accuratelyMeasuresDepletedBarsWithDarkTintedTracksAndReanchorsShift() {
        val w = 900
        val h = 900
        val barLeft = 112
        val barRight = 292 // 180px wide

        for (expected in listOf(100, 75, 50, 25, 0)) {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            fillBitmapRect(bmp, 0, 0, w, h, Color.rgb(210, 215, 222))
            fillBitmapRect(bmp, 12, 12, 306, 110, Color.rgb(32, 36, 44))
            drawFlyffVitalBar(bmp, barLeft, 30, barRight, 44, expected, VitalType.HP)
            drawFlyffVitalBar(bmp, barLeft, 52, barRight, 66, expected, VitalType.MP)
            drawFlyffVitalBar(bmp, barLeft, 74, barRight, 88, expected, VitalType.FP)

            val hpSpec = BarCalibrationSpec(VitalType.HP, RectSpec.fromPixels(barLeft, 30, barRight, 44, w, h))
            val mpSpec = BarCalibrationSpec(VitalType.MP, RectSpec.fromPixels(barLeft, 52, barRight, 66, w, h))
            val fpSpec = BarCalibrationSpec(VitalType.FP, RectSpec.fromPixels(barLeft, 74, barRight, 88, w, h))

            val hp = FlyffVisionAnalyzer.measureBarFillPercent(bmp, hpSpec)
            val mp = FlyffVisionAnalyzer.measureBarFillPercent(bmp, mpSpec)
            val fp = FlyffVisionAnalyzer.measureBarFillPercent(bmp, fpSpec)

            assertTrue("HP expected ~$expected%, got $hp%", kotlin.math.abs(hp - expected) <= 4)
            assertTrue("MP expected ~$expected%, got $mp%", kotlin.math.abs(mp - expected) <= 4)
            assertTrue("FP expected ~$expected%, got $fp%", kotlin.math.abs(fp - expected) <= 4)
            bmp.recycle()
        }

        // Now shift the ENTIRE HUD vertically by +34px (simulating status bar / chrome shift)
        // and verify dynamic re-anchoring still reads exact 55% HP & 30% MP!
        val shifted = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fillBitmapRect(shifted, 0, 0, w, h, Color.rgb(215, 222, 232)) // bright sky at old y=30..44!
        val dy = 34
        fillBitmapRect(shifted, 12, 12 + dy, 306, 110 + dy, Color.rgb(32, 36, 44))
        drawFlyffVitalBar(shifted, barLeft, 30 + dy, barRight, 44 + dy, 55, VitalType.HP)
        drawFlyffVitalBar(shifted, barLeft, 52 + dy, barRight, 66 + dy, 30, VitalType.MP)
        drawFlyffVitalBar(shifted, barLeft, 74 + dy, barRight, 88 + dy, 100, VitalType.FP)

        val oldCalHud = com.example.flyff.CharacterHudRegions(
            owner = WebViewTarget.ACCOUNT_1,
            paneRect = RectSpec.fromPixels(0, 0, w, h, w, h),
            hpBar = BarCalibrationSpec(VitalType.HP, RectSpec.fromPixels(barLeft, 30, barRight, 44, w, h)),
            mpBar = BarCalibrationSpec(VitalType.MP, RectSpec.fromPixels(barLeft, 52, barRight, 66, w, h)),
            fpBar = BarCalibrationSpec(VitalType.FP, RectSpec.fromPixels(barLeft, 74, barRight, 88, w, h))
        )
        val live = FlyffVisionAnalyzer.measureHudVitalsDynamic(shifted, oldCalHud, Rect(0, 0, w, h))
        assertTrue("Expected dynamic reanchor to trigger on +34px shift", live.reanchored)
        assertTrue("Shifted HP (55%) should read 48..62%, got ${live.hpPct}%", (live.hpPct ?: -1) in 48..62)
        assertTrue("Shifted MP (30%) should read 23..37%, got ${live.mpPct}%", (live.mpPct ?: -1) in 23..37)

        val singleReanchoredHp = FlyffVisionAnalyzer.measureSingleBarDynamic(shifted, oldCalHud.hpBar!!)
        assertTrue("Single-bar dynamic reanchor HP (55%) should read 48..62%, got $singleReanchoredHp%", singleReanchoredHp in 48..62)
        shifted.recycle()
    }

    @Test
    fun flyffVisionAnalyzer_skipsBuffPartyTabBarAndKeepsNarrowSpritesSquare() {
        val w = 900
        val h = 900
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fillBitmapRect(bmp, 0, 0, w, h, Color.rgb(185, 195, 205))

        // Top-left Mobile HUD (HP/MP/FP)
        fillBitmapRect(bmp, 10, 10, 290, 104, Color.rgb(34, 38, 46))
        drawFlyffVitalBar(bmp, 106, 26, 280, 40, 100, VitalType.HP)
        drawFlyffVitalBar(bmp, 106, 48, 280, 62, 100, VitalType.MP)
        drawFlyffVitalBar(bmp, 106, 70, 280, 84, 100, VitalType.FP)

        // "Buff x10 | Party x2" dark tab bar sitting directly below HP/MP/FP (y=112..140)
        // Includes a small green '+' icon on the far left — MUST BE SKIPPED!
        fillBitmapRect(bmp, 12, 112, 286, 140, Color.rgb(36, 38, 44))
        fillBitmapRect(bmp, 18, 118, 30, 132, Color.rgb(75, 215, 85)) // tiny green '+' icon
        fillBitmapRect(bmp, 148, 118, 162, 132, Color.rgb(220, 175, 65)) // gold Party icon

        // Actual 2-row active buff panel below the tab bar (y=146..234), 5 buffs in Row 0 + 4 buffs in Row 1
        // Includes a narrow-artwork sprite ("Quick Step" boot at Row 0 Col 2) with dark left/right borders
        fillBitmapRect(bmp, 12, 146, 294, 234, Color.rgb(26, 28, 34))
        val tileSize = 36
        val pitchX = 39
        val pitchY = 40
        val rowCounts = listOf(5, 4)
        for ((rowIdx, count) in rowCounts.withIndex()) {
            val by = 150 + rowIdx * pitchY
            for (col in 0 until count) {
                val bx = 16 + col * pitchX
                fillBitmapRect(bmp, bx, by, bx + tileSize, by + tileSize, Color.rgb(34, 38, 46))
                if (rowIdx == 0 && col == 2) {
                    // Narrow sprite in center (e.g. Quick Step boot) with dark 8px sides
                    fillBitmapRect(bmp, bx + 9, by + 3, bx + 27, by + 22, Color.rgb(235, 165, 55))
                } else {
                    fillBitmapRect(bmp, bx + 2, by + 2, bx + tileSize - 2, by + 22, Color.rgb(55 + col * 35, 135, 230 - rowIdx * 60))
                    fillBitmapRect(bmp, bx + 6, by + 6, bx + tileSize - 6, by + 18, Color.rgb(245, 210, 65))
                }
                // Green MM:SS timer digits in lower third
                fillBitmapRect(bmp, bx + 2, by + 24, bx + tileSize - 2, by + 34, Color.rgb(18, 22, 24))
                fillBitmapRect(bmp, bx + 6, by + 26, bx + tileSize - 6, by + 32, Color.rgb(65, 245, 90))
            }
        }

        val hud = FlyffVisionAnalyzer.autoDetectMobileHudBars(bmp, Rect(0, 0, w, h), WebViewTarget.ACCOUNT_1)
        val buffPanel = hud.buffGridRegion.toAndroidRect(w, h)
        assertTrue("Violet buff box MUST start below 'Buff|Party' tab bar (y >= 142), got top=${buffPanel.top}", buffPanel.top >= 142)

        val tiles = FlyffVisionAnalyzer.autoDetectActiveBuffTiles(bmp, hud)
        assertEquals(
            "Should detect all 9 buffs (5 in Row 0 + 4 in Row 1) and skip tab bar; tiles=${tiles.map { "r${it.row}c${it.col}:${it.tileBounds}" }}",
            9,
            tiles.size
        )
        // Verify Quick Step (Row 0 Col 2) is cropped SQUARE, never narrowed!
        val quickStep = tiles.first { it.row == 0 && it.col == 2 }
        assertTrue(
            "Quick Step crop must remain square (width >= 32, |w-h| <= 3), got ${quickStep.tileBounds.width()}x${quickStep.tileBounds.height()}",
            quickStep.tileBounds.width() >= 32 && kotlin.math.abs(quickStep.tileBounds.width() - quickStep.tileBounds.height()) <= 3
        )
        tiles.forEach {
            it.fullTileBitmap.recycle(); it.artCropBitmap.recycle(); it.timerStripBitmap.recycle()
        }
        bmp.recycle()
    }

    @Test
    fun smartFlyffEngineAndExportedMacro_fireHealAndHpMpPotsSimultaneouslyOnDepletedBars() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())

        // Build a 50/50 split frame where A2 (top) has 100% HP / 25% MP and A1 (bottom) has 50% HP / 30% MP
        val w = 900
        val h = 1600
        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fillBitmapRect(frame, 0, 0, w, h, Color.rgb(190, 200, 210))
        // A2 top pane (y=0..800): HP 100%, MP 25%, FP 100%
        fillBitmapRect(frame, 12, 12, 290, 108, Color.rgb(34, 38, 46))
        drawFlyffVitalBar(frame, 104, 28, 280, 42, 100, VitalType.HP)
        drawFlyffVitalBar(frame, 104, 50, 280, 64, 25, VitalType.MP)
        drawFlyffVitalBar(frame, 104, 72, 280, 86, 100, VitalType.FP)
        // A1 bottom pane (y=800..1600): HP 50%, MP 30%, FP 100%
        fillBitmapRect(frame, 12, 812, 290, 908, Color.rgb(34, 38, 46))
        drawFlyffVitalBar(frame, 104, 828, 280, 842, 50, VitalType.HP)
        drawFlyffVitalBar(frame, 104, 850, 280, 864, 30, VitalType.MP)
        drawFlyffVitalBar(frame, 104, 872, 280, 886, 100, VitalType.FP)

        val testDetector = object : ScreenDetector(context) {
            init { _isActive.value = true }
            override suspend fun captureLatestBitmap(): Bitmap = frame.copy(Bitmap.Config.ARGB_8888, false)
        }

        val dispatched = java.util.Collections.synchronizedList(mutableListOf<Pair<WebViewTarget, Int>>())
        val bridge = DualWebInputBridge()
        val delegate = object : DualWebMacroDelegate {
            override fun dispatchKey(keyCode: Int, holdMs: Long, metaState: Int, targetOverride: WebViewTarget): Boolean {
                dispatched.add(targetOverride to keyCode)
                return true
            }
            override fun dispatchTap(screenX: Float, screenY: Float, holdMs: Long, jitterPx: Int, targetOverride: WebViewTarget) = true
            override fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long, steps: Int, targetOverride: WebViewTarget) = true
            override fun describeTarget(targetOverride: WebViewTarget) = targetOverride.shortLabel
            override fun currentLayoutMode() = DualLayoutMode.SPLIT_50_50
            override fun currentPaneRects() = Rect(0, 800, w, h) to Rect(0, 0, w, 800)
        }
        bridge.attach(delegate)

        val healKey = ScannedSkillKey(id = "k-heal", owner = WebViewTarget.ACCOUNT_2, label = "Heal", category = SkillCategory.HEAL, keyCode = KeyEvent.KEYCODE_1, keyLabel = "1")
        val a1FoodKey = ScannedSkillKey(id = "k-food", owner = WebViewTarget.ACCOUNT_1, label = "HP Food", category = SkillCategory.HP_POT, keyCode = KeyEvent.KEYCODE_1, keyLabel = "1")
        val a1PillKey = ScannedSkillKey(id = "k-pill", owner = WebViewTarget.ACCOUNT_1, label = "HP Pill", category = SkillCategory.HP_POT, keyCode = KeyEvent.KEYCODE_2, keyLabel = "2")
        val a1MpKey = ScannedSkillKey(id = "k-a1mp", owner = WebViewTarget.ACCOUNT_1, label = "MP Pot", category = SkillCategory.MP_POT, keyCode = KeyEvent.KEYCODE_3, keyLabel = "3")
        val a2MpKey = ScannedSkillKey(id = "k-a2mp", owner = WebViewTarget.ACCOUNT_2, label = "RM MP Pot", category = SkillCategory.MP_POT, keyCode = KeyEvent.KEYCODE_4, keyLabel = "4")

        val a1Hud = FlyffVisionAnalyzer.autoDetectMobileHudBars(frame, Rect(0, 800, w, h), WebViewTarget.ACCOUNT_1).copy(
            hpBar = BarCalibrationSpec(VitalType.HP, RectSpec.fromPixels(104, 828, 280, 842, w, h)),
            mpBar = BarCalibrationSpec(VitalType.MP, RectSpec.fromPixels(104, 850, 280, 864, w, h)),
            fpBar = BarCalibrationSpec(VitalType.FP, RectSpec.fromPixels(104, 872, 280, 886, w, h))
        )
        val a2Hud = FlyffVisionAnalyzer.autoDetectMobileHudBars(frame, Rect(0, 0, w, 800), WebViewTarget.ACCOUNT_2).copy(
            hpBar = BarCalibrationSpec(VitalType.HP, RectSpec.fromPixels(104, 28, 280, 42, w, h)),
            mpBar = BarCalibrationSpec(VitalType.MP, RectSpec.fromPixels(104, 50, 280, 64, w, h)),
            fpBar = BarCalibrationSpec(VitalType.FP, RectSpec.fromPixels(104, 72, 280, 86, w, h))
        )

        val profile = FlyffProfile(
            name = "Test Split",
            pollingIntervalMs = 80L,
            skillKeys = listOf(healKey, a1FoodKey, a1PillKey, a1MpKey, a2MpKey),
            layouts = mapOf(
                DualLayoutMode.SPLIT_50_50 to LayoutHudCalibration(
                    layoutMode = DualLayoutMode.SPLIT_50_50,
                    screenWidth = w,
                    screenHeight = h,
                    charHuds = mapOf(WebViewTarget.ACCOUNT_1 to a1Hud, WebViewTarget.ACCOUNT_2 to a2Hud)
                )
            ),
            healRule = HealRuleConfig(
                enabled = true,
                targetChar = WebViewTarget.ACCOUNT_1,
                healSkillKeyId = healKey.id,
                triggerBelowPct = 85,
                healCadenceMs = 100L
            ),
            vitalTriggers = listOf(
                VitalTriggerRule(targetChar = WebViewTarget.ACCOUNT_1, vitalType = VitalType.HP, triggerBelowPct = 65, skillKeyId = a1FoodKey.id),
                VitalTriggerRule(targetChar = WebViewTarget.ACCOUNT_1, vitalType = VitalType.HP, triggerBelowPct = 60, skillKeyId = a1PillKey.id),
                VitalTriggerRule(targetChar = WebViewTarget.ACCOUNT_1, vitalType = VitalType.MP, triggerBelowPct = 40, skillKeyId = a1MpKey.id),
                VitalTriggerRule(targetChar = WebViewTarget.ACCOUNT_2, vitalType = VitalType.MP, triggerBelowPct = 40, skillKeyId = a2MpKey.id)
            )
        )

        // 1. Verify SmartFlyffEngine fires Heal AND all 4 HP/MP pots without starvation!
        val engine = SmartFlyffEngine(repo, testDetector, bridge, BuffTimerOcrReader())
        engine.start(profile)
        delay(350L)
        engine.stop()

        val fired = dispatched.toList()
        assertTrue("SmartFlyffEngine MUST fire A2 Heal (1), fired=$fired", fired.any { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_1) })
        assertTrue("SmartFlyffEngine MUST fire A1 HP Food (1), fired=$fired", fired.any { it == (WebViewTarget.ACCOUNT_1 to KeyEvent.KEYCODE_1) })
        assertTrue("SmartFlyffEngine MUST fire A1 HP Pill (2), fired=$fired", fired.any { it == (WebViewTarget.ACCOUNT_1 to KeyEvent.KEYCODE_2) })
        assertTrue("SmartFlyffEngine MUST fire A1 MP Pot (3), fired=$fired", fired.any { it == (WebViewTarget.ACCOUNT_1 to KeyEvent.KEYCODE_3) })
        assertTrue("SmartFlyffEngine MUST fire A2 MP Pot (4), fired=$fired", fired.any { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_4) })

        // 2. Verify Exported Macro (MacroInterpreter) ALSO triggers Heal + HP Food + MP Pot!
        dispatched.clear()
        val exported = FlyffMacroCompiler.compileToMacro(profile, DualLayoutMode.SPLIT_50_50).copy(loopCount = 1)
        val interpreter = MacroInterpreter(repo, testDetector, OverlayCoordinator(), dualWebInputBridge = bridge)
        interpreter.start(exported)
        var ticks = 0
        while (interpreter.state.value.status == ExecutionStatus.RUNNING && ticks < 60) {
            delay(50L)
            ticks++
        }
        val exportedFired = dispatched.toList()
        assertTrue("Exported macro MUST fire A2 Heal (1), fired=$exportedFired", exportedFired.any { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_1) })
        assertTrue("Exported macro MUST fire A1 HP Food (1), fired=$exportedFired", exportedFired.any { it == (WebViewTarget.ACCOUNT_1 to KeyEvent.KEYCODE_1) })
        assertTrue("Exported macro MUST fire A1 MP Pot (3), fired=$exportedFired", exportedFired.any { it == (WebViewTarget.ACCOUNT_1 to KeyEvent.KEYCODE_3) })

        bridge.detach(delegate)
        frame.recycle()
        db.close()
    }

    @Test
    fun flyffIconMatcher_ignoresNonBuffApiAttackSkillsWhenIdentifyingActiveBuffs() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())

        val iconBmp = Bitmap.createBitmap(36, 36, Bitmap.Config.ARGB_8888)
        fillBitmapRect(iconBmp, 0, 0, 36, 36, Color.rgb(25, 30, 50))
        fillBitmapRect(iconBmp, 5, 4, 31, 22, Color.rgb(75, 195, 250))
        fillBitmapRect(iconBmp, 12, 8, 24, 18, Color.rgb(255, 235, 120))
        val attackTpl = repo.saveTemplateBitmap("Electric Shock", iconBmp)
        val artCrop = Bitmap.createBitmap(iconBmp, 2, 2, 32, 20)

        val nonBuffEntry = FlyffCatalogEntry(
            id = "api_999",
            name = "Electric Shock",
            group = FlyffSkillGroup.PSY_ELEM,
            recommendedCategory = SkillCategory.ATTACK_AOE,
            isBuff = false,
            cachedIconPath = attackTpl.filePath
        )

        val res = FlyffIconMatcher.identifyActiveBuffIcon(
            fullTileBitmap = iconBmp,
            artCropBitmap = artCrop,
            catalogEntries = listOf(nonBuffEntry),
            savedKeys = emptyList(),
            templatePathById = emptyMap()
        )
        assertNull("Non-buff attack skill like Electric Shock MUST NEVER be returned for active buff slots", res)

        iconBmp.recycle()
        artCrop.recycle()
        repo.deleteTemplate(attackTpl.id)
        db.close()
    }

    @Test
    fun flyffVisionAnalyzer_derivesHpBarFromMpFpSpacingEvenWhenHpEmptyOrPortraitRed() {
        val w = 900
        val h = 900
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fillBitmapRect(bmp, 0, 0, w, h, Color.rgb(185, 195, 205))
        // Dark HUD box
        fillBitmapRect(bmp, 10, 10, 304, 112, Color.rgb(32, 36, 44))
        // Distracting red/crimson character hair/armor in portrait area (MUST NOT hijack HP!)
        fillBitmapRect(bmp, 18, 18, 92, 96, Color.rgb(210, 52, 46))

        // Uniformly spaced HP (y=30..44, EMPTY at 0%!), MP (y=52..66, 100%), FP (y=74..88, 100%) -> stepY = 22
        val barLeft = 108
        val barRight = 288
        drawFlyffVitalBar(bmp, barLeft, 30, barRight, 44, 0, VitalType.HP)
        drawFlyffVitalBar(bmp, barLeft, 52, barRight, 66, 100, VitalType.MP)
        drawFlyffVitalBar(bmp, barLeft, 74, barRight, 88, 100, VitalType.FP)

        val hud = FlyffVisionAnalyzer.autoDetectMobileHudBars(bmp, Rect(0, 0, w, h), WebViewTarget.ACCOUNT_1)
        val hpRect = hud.hpBar!!.rect.toAndroidRect(w, h)
        assertTrue("HP left should match MP/FP (~$barLeft), got ${hpRect.left}", kotlin.math.abs(hpRect.left - barLeft) <= 3)
        assertTrue("HP right should match MP/FP (~$barRight), got ${hpRect.right}", kotlin.math.abs(hpRect.right - barRight) <= 3)
        assertTrue("HP top should be derived at ~30 (mp.top - stepY), got ${hpRect.top}", kotlin.math.abs(hpRect.top - 30) <= 3)
        assertEquals("Empty HP bar must read 0%", 0, FlyffVisionAnalyzer.measureBarFillPercent(bmp, hud.hpBar!!))

        // Now paint HP at 45% in the same frame and give measureHudVitalsDynamic a BAD saved HP rect (off by +18px)
        drawFlyffVitalBar(bmp, barLeft, 30, barRight, 44, 45, VitalType.HP)
        val brokenSavedHud = hud.copy(
            hpBar = BarCalibrationSpec(VitalType.HP, RectSpec.fromPixels(barLeft, 12, barRight - 40, 24, w, h))
        )
        val live = FlyffVisionAnalyzer.measureHudVitalsDynamic(bmp, brokenSavedHud, Rect(0, 0, w, h))
        assertTrue("Saved bad HP bar should auto-snap from MP/FP geometry", live.reanchored)
        assertTrue("Auto-snapped HP (45%) should read 39..51%, got ${live.hpPct}%", (live.hpPct ?: -1) in 39..51)
        bmp.recycle()
    }

    @Test
    fun smartFlyffEngine_rapidHealAndMultiHitAttacksWith8MinBuffLockout() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())

        // Verify v1->v2 migration upgrades old slow defaults automatically
        val legacyV1 = FlyffProfile(
            schemaVersion = 1,
            pollingIntervalMs = 160L,
            healRule = HealRuleConfig(healCadenceMs = 220L),
            combatConfig = CombatRotationConfig(attackIntervalMs = 350L),
            vitalTriggers = listOf(VitalTriggerRule(cooldownMs = 1500L))
        )
        val migrated = legacyV1.migrateIfNeeded()
        assertEquals(FlyffProfile.CURRENT_SCHEMA_VERSION, migrated.schemaVersion)
        assertEquals(45L, migrated.pollingIntervalMs)
        assertEquals(60L, migrated.healRule.healCadenceMs)
        assertEquals(80L, migrated.combatConfig.attackIntervalMs)
        assertEquals(350L, migrated.vitalTriggers.first().cooldownMs)
        assertEquals(480, migrated.minBuffRecastSec) // 8 minutes minimum!

        // Build live 50/50 split frame: A1 HP=50% (needs rapid heal + rapid attacks), A2 HP=100%
        val w = 900
        val h = 1600
        val frame = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        fillBitmapRect(frame, 0, 0, w, h, Color.rgb(185, 195, 205))
        // A2 top pane
        fillBitmapRect(frame, 12, 12, 290, 108, Color.rgb(34, 38, 46))
        drawFlyffVitalBar(frame, 104, 28, 280, 42, 100, VitalType.HP)
        drawFlyffVitalBar(frame, 104, 50, 280, 64, 100, VitalType.MP)
        drawFlyffVitalBar(frame, 104, 72, 280, 86, 100, VitalType.FP)
        // A1 bottom pane: HP=50% (triggers rapid heal), MP=100%, FP=100%
        fillBitmapRect(frame, 12, 812, 290, 908, Color.rgb(34, 38, 46))
        drawFlyffVitalBar(frame, 104, 828, 280, 842, 50, VitalType.HP)
        drawFlyffVitalBar(frame, 104, 850, 280, 864, 100, VitalType.MP)
        drawFlyffVitalBar(frame, 104, 872, 280, 886, 100, VitalType.FP)

        // Create a dummy buff template that is NOT present on frame (to test 8-min single-cast lockout)
        val missingBuffBmp = Bitmap.createBitmap(24, 16, Bitmap.Config.ARGB_8888)
        missingBuffBmp.eraseColor(Color.MAGENTA)
        val missingTpl = repo.saveTemplateBitmap("MissingHaste", missingBuffBmp)
        missingBuffBmp.recycle()

        val testDetector = object : ScreenDetector(context) {
            init { _isActive.value = true }
            override suspend fun captureLatestBitmap(): Bitmap = frame.copy(Bitmap.Config.ARGB_8888, false)
        }

        val dispatched = java.util.Collections.synchronizedList(mutableListOf<Pair<WebViewTarget, Int>>())
        val bridge = DualWebInputBridge()
        val delegate = object : DualWebMacroDelegate {
            override fun dispatchKey(keyCode: Int, holdMs: Long, metaState: Int, targetOverride: WebViewTarget): Boolean {
                dispatched.add(targetOverride to keyCode)
                return true
            }
            override fun dispatchTap(screenX: Float, screenY: Float, holdMs: Long, jitterPx: Int, targetOverride: WebViewTarget) = true
            override fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long, steps: Int, targetOverride: WebViewTarget) = true
            override fun describeTarget(targetOverride: WebViewTarget) = targetOverride.shortLabel
            override fun currentLayoutMode() = DualLayoutMode.SPLIT_50_50
            override fun currentPaneRects() = Rect(0, 800, w, h) to Rect(0, 0, w, 800)
        }
        bridge.attach(delegate)

        val healKey = ScannedSkillKey(id = "k-heal", owner = WebViewTarget.ACCOUNT_2, label = "Heal", category = SkillCategory.HEAL, keyCode = KeyEvent.KEYCODE_1, keyLabel = "1")
        val atkKey = ScannedSkillKey(id = "k-atk", owner = WebViewTarget.ACCOUNT_1, label = "Max Crisis", category = SkillCategory.ATTACK_AOE, keyCode = KeyEvent.KEYCODE_9, keyLabel = "9")
        val buffKey = ScannedSkillKey(id = "k-haste", owner = WebViewTarget.ACCOUNT_2, label = "Haste", category = SkillCategory.PARTY_BUFF, keyCode = KeyEvent.KEYCODE_5, keyLabel = "5")
        val buffDef = ActiveBuffDefinition(
            id = "b-haste",
            targetChar = WebViewTarget.ACCOUNT_1,
            label = "Haste",
            perLayoutTemplateIds = mapOf(DualLayoutMode.SPLIT_50_50 to missingTpl.id)
        )

        val a1Hud = CharacterHudRegions(
            owner = WebViewTarget.ACCOUNT_1,
            paneRect = RectSpec.fromPixels(0, 800, w, h, w, h),
            hpBar = BarCalibrationSpec(VitalType.HP, RectSpec.fromPixels(104, 828, 280, 842, w, h)),
            mpBar = BarCalibrationSpec(VitalType.MP, RectSpec.fromPixels(104, 850, 280, 864, w, h)),
            fpBar = BarCalibrationSpec(VitalType.FP, RectSpec.fromPixels(104, 872, 280, 886, w, h)),
            buffGridRegion = RectSpec.fromPixels(16, 920, 280, 1020, w, h)
        )
        val a2Hud = CharacterHudRegions(
            owner = WebViewTarget.ACCOUNT_2,
            paneRect = RectSpec.fromPixels(0, 0, w, 800, w, h),
            hpBar = BarCalibrationSpec(VitalType.HP, RectSpec.fromPixels(104, 28, 280, 42, w, h)),
            mpBar = BarCalibrationSpec(VitalType.MP, RectSpec.fromPixels(104, 50, 280, 64, w, h)),
            fpBar = BarCalibrationSpec(VitalType.FP, RectSpec.fromPixels(104, 72, 280, 86, w, h)),
            buffGridRegion = RectSpec.fromPixels(16, 120, 280, 220, w, h)
        )

        val profile = FlyffProfile(
            name = "Fast Combat + 8m Buff",
            pollingIntervalMs = 35L,
            buffScanIntervalMs = 600L,
            minBuffRecastSec = 480,
            skillKeys = listOf(healKey, atkKey, buffKey),
            activeBuffs = listOf(buffDef),
            buffLinks = listOf(
                BuffKeyLink(
                    id = "link-haste",
                    skillKeyId = buffKey.id,
                    activeBuffId = buffDef.id,
                    requiresSafeHp = false
                )
            ),
            layouts = mapOf(
                DualLayoutMode.SPLIT_50_50 to LayoutHudCalibration(
                    layoutMode = DualLayoutMode.SPLIT_50_50,
                    screenWidth = w,
                    screenHeight = h,
                    charHuds = mapOf(WebViewTarget.ACCOUNT_1 to a1Hud, WebViewTarget.ACCOUNT_2 to a2Hud)
                )
            ),
            healRule = HealRuleConfig(
                enabled = true,
                targetChar = WebViewTarget.ACCOUNT_1,
                healSkillKeyId = healKey.id,
                triggerBelowPct = 85,
                healCadenceMs = 60L
            ),
            combatConfig = CombatRotationConfig(
                enabled = true,
                manualCombatEnabled = true,
                attackSkillKeyIds = listOf(atkKey.id),
                attackIntervalMs = 80L
            )
        )

        val engine = SmartFlyffEngine(repo, testDetector, bridge, BuffTimerOcrReader())
        engine.start(profile)
        delay(550L)
        engine.stop()

        val fired = dispatched.toList()
        val healCount = fired.count { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_1) }
        val atkCount = fired.count { it == (WebViewTarget.ACCOUNT_1 to KeyEvent.KEYCODE_9) }
        val buffCount = fired.count { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_5) }

        assertTrue("Rapid Heal should fire multiple times in 550ms (>= 3), got $healCount", healCount >= 3)
        assertTrue("Multi-hit Attack should fire multiple times in 550ms (>= 3), got $atkCount", atkCount >= 3)
        assertTrue("Buff with 8-min lockout must NOT spam-recast (expected <= 1), got $buffCount", buffCount <= 1)

        bridge.detach(delegate)
        frame.recycle()
        repo.deleteTemplate(missingTpl.id)
        db.close()
    }

    @Test
    fun flyffVisionAnalyzer_detectsRealChatAndResurrectionDialogsWithoutFalsePositives() {
        val dir = java.io.File("../public/assets/aistudio")
        if (!dir.exists()) return

        // 1. A1 Full: Chat open + Android soft keyboard open (pitch-black top box + tabs)
        val f073809 = java.io.File(dir, "Screenshot_20261122_073809_Flyff_Ftools_45016b38.jpg")
        if (f073809.exists()) {
            val bmp = BitmapFactory.decodeFile(f073809.absolutePath)!!
            val fullPane = Rect(0, 0, bmp.width, bmp.height)
            assertTrue("073809 (A1 Full Chat+Keyboard) MUST detect chat open", FlyffVisionAnalyzer.detectFlyffChatOpenInPane(bmp, fullPane))
            assertFalse("073809 MUST NOT falsely trigger Resurrection dialog", FlyffVisionAnalyzer.detectFlyffResurrectionDialog(bmp, fullPane))
            bmp.recycle()
        }

        // 2. A1 Full: Chat open, keyboard closed (bottom dark input bar + lime-green Send pill + tabs)
        val f073804 = java.io.File(dir, "Screenshot_20261122_073804_Flyff_Ftools_3d7e3b59.jpg")
        if (f073804.exists()) {
            val bmp = BitmapFactory.decodeFile(f073804.absolutePath)!!
            val fullPane = Rect(0, 0, bmp.width, bmp.height)
            assertTrue("073804 (A1 Full Chat, no keyboard) MUST detect chat open", FlyffVisionAnalyzer.detectFlyffChatOpenInPane(bmp, fullPane))
            assertFalse("073804 MUST NOT falsely trigger Resurrection dialog", FlyffVisionAnalyzer.detectFlyffResurrectionDialog(bmp, fullPane))
            bmp.recycle()
        }

        // 3. 50/50 Split: Chat open on A1 (bottom pane) + keyboard open; A2 (top pane) normal
        val f073751 = java.io.File(dir, "Screenshot_20261122_073751_Flyff_Ftools_b6fda41b.jpg")
        if (f073751.exists()) {
            val bmp = BitmapFactory.decodeFile(f073751.absolutePath)!!
            val a2Pane = Rect(0, 0, bmp.width, bmp.height / 2)
            val a1Pane = Rect(0, bmp.height / 2, bmp.width, bmp.height)
            assertTrue("073751 (Split A1 Chat+Keyboard) MUST detect A1 chat open", FlyffVisionAnalyzer.detectFlyffChatOpenInPane(bmp, a1Pane))
            assertFalse("073751 (Split A2 normal) MUST NOT detect A2 chat open", FlyffVisionAnalyzer.detectFlyffChatOpenInPane(bmp, a2Pane))
            assertFalse("073751 MUST NOT falsely trigger Resurrection dialog on A1", FlyffVisionAnalyzer.detectFlyffResurrectionDialog(bmp, a1Pane))
            bmp.recycle()
        }

        // 4. 50/50 Split: Chat open on A1 (bottom pane), keyboard closed; A2 (top pane) normal
        val f073744 = java.io.File(dir, "Screenshot_20261122_073744_Flyff_Ftools_8be23e80.jpg")
        if (f073744.exists()) {
            val bmp = BitmapFactory.decodeFile(f073744.absolutePath)!!
            val a2Pane = Rect(0, 0, bmp.width, bmp.height / 2)
            val a1Pane = Rect(0, bmp.height / 2, bmp.width, bmp.height)
            assertTrue("073744 (Split A1 Chat, no keyboard) MUST detect A1 chat open", FlyffVisionAnalyzer.detectFlyffChatOpenInPane(bmp, a1Pane))
            assertFalse("073744 (Split A2 normal) MUST NOT detect A2 chat open", FlyffVisionAnalyzer.detectFlyffChatOpenInPane(bmp, a2Pane))
            assertFalse("073744 MUST NOT falsely trigger Resurrection dialog on A1", FlyffVisionAnalyzer.detectFlyffResurrectionDialog(bmp, a1Pane))
            bmp.recycle()
        }

        // 5. 50/50 Split: A1 (bottom pane) DEAD (Resurrection dialog + HP 0%), A2 (top pane) alive
        val f073915 = java.io.File(dir, "Screenshot_20261122_073915_Flyff_Ftools_ee8789ba.jpg")
        if (f073915.exists()) {
            val bmp = BitmapFactory.decodeFile(f073915.absolutePath)!!
            val a2Pane = Rect(0, 0, bmp.width, bmp.height / 2)
            val a1Pane = Rect(0, bmp.height / 2, bmp.width, bmp.height)
            assertTrue("073915 (Split A1 Dead) MUST detect Resurrection dialog on A1", FlyffVisionAnalyzer.detectFlyffResurrectionDialog(bmp, a1Pane))
            assertFalse("073915 (Split A1 Dead) MUST NOT falsely detect chat open", FlyffVisionAnalyzer.detectFlyffChatOpenInPane(bmp, a1Pane))
            assertFalse("073915 (Split A2 Alive) MUST NOT falsely detect Resurrection dialog on A2", FlyffVisionAnalyzer.detectFlyffResurrectionDialog(bmp, a2Pane))
            val a1Hud = FlyffVisionAnalyzer.autoDetectMobileHudBars(bmp, a1Pane, WebViewTarget.ACCOUNT_1)
            val a1Hp = FlyffVisionAnalyzer.measureBarFillPercent(bmp, a1Hud.hpBar!!)
            assertEquals("073915 dead A1 HP bar must read 0%", 0, a1Hp)
            bmp.recycle()
        }

        // 6. A1 Full: DEAD (Resurrection dialog + HP 0%)
        val f073941 = java.io.File(dir, "Screenshot_20261122_073941_Flyff_Ftools_67086684.jpg")
        if (f073941.exists()) {
            val bmp = BitmapFactory.decodeFile(f073941.absolutePath)!!
            val fullPane = Rect(0, 0, bmp.width, bmp.height)
            assertTrue("073941 (A1 Full Dead) MUST detect Resurrection dialog", FlyffVisionAnalyzer.detectFlyffResurrectionDialog(bmp, fullPane))
            assertFalse("073941 (A1 Full Dead) MUST NOT falsely detect chat open", FlyffVisionAnalyzer.detectFlyffChatOpenInPane(bmp, fullPane))
            val a1Hud = FlyffVisionAnalyzer.autoDetectMobileHudBars(bmp, fullPane, WebViewTarget.ACCOUNT_1)
            val a1Hp = FlyffVisionAnalyzer.measureBarFillPercent(bmp, a1Hud.hpBar!!)
            assertEquals("073941 dead A1 HP bar must read 0%", 0, a1Hp)
            bmp.recycle()
        }

        // 7. 50/50 Split Normal Live Gameplay: NO false positives for Chat OR Resurrection
        val f090341 = java.io.File(dir, "Screenshot_20261121_090341_ShizuMacro_62e243ba.jpg")
        if (f090341.exists()) {
            val bmp = BitmapFactory.decodeFile(f090341.absolutePath)!!
            val a2Pane = Rect(0, 0, bmp.width, bmp.height / 2)
            val a1Pane = Rect(0, bmp.height / 2, bmp.width, bmp.height)
            assertFalse("090341 normal A1 MUST NOT trigger Chat", FlyffVisionAnalyzer.detectFlyffChatOpenInPane(bmp, a1Pane))
            assertFalse("090341 normal A2 MUST NOT trigger Chat", FlyffVisionAnalyzer.detectFlyffChatOpenInPane(bmp, a2Pane))
            assertFalse("090341 normal A1 MUST NOT trigger Resurrection", FlyffVisionAnalyzer.detectFlyffResurrectionDialog(bmp, a1Pane))
            assertFalse("090341 normal A2 MUST NOT trigger Resurrection", FlyffVisionAnalyzer.detectFlyffResurrectionDialog(bmp, a2Pane))
            bmp.recycle()
        }
    }

    @Test
    fun smartFlyffEngine_startupBuffSweepChatPauseAndDeathReviveRebuff() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repo = MacroRepository(context, db.macroDao())

        val w = 900
        val h = 1600

        // Create two distinct 24x20 buff icon templates
        val buffUpBmp = Bitmap.createBitmap(24, 20, Bitmap.Config.ARGB_8888)
        fillBitmapRect(buffUpBmp, 0, 0, 24, 20, Color.rgb(30, 180, 240))
        fillBitmapRect(buffUpBmp, 6, 4, 18, 16, Color.rgb(255, 240, 80))
        val buffUpTpl = repo.saveTemplateBitmap("ActiveHeapUp", buffUpBmp)

        val buffMissingBmp = Bitmap.createBitmap(24, 20, Bitmap.Config.ARGB_8888)
        fillBitmapRect(buffMissingBmp, 0, 0, 24, 20, Color.rgb(210, 45, 195))
        fillBitmapRect(buffMissingBmp, 5, 5, 19, 15, Color.rgb(90, 255, 160))
        val buffMissingTpl = repo.saveTemplateBitmap("MissingHaste", buffMissingBmp)

        val a1HpPct = java.util.concurrent.atomic.AtomicInteger(100)
        val a2MpPct = java.util.concurrent.atomic.AtomicInteger(100)
        val showBuffUp = java.util.concurrent.atomic.AtomicBoolean(true)
        val showBuffMissing = java.util.concurrent.atomic.AtomicBoolean(false)
        val showResDialog = java.util.concurrent.atomic.AtomicBoolean(false)

        fun buildFrame(): Bitmap {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            fillBitmapRect(bmp, 0, 0, w, h, Color.rgb(180, 190, 200))
            // A2 top pane
            fillBitmapRect(bmp, 12, 12, 290, 108, Color.rgb(34, 38, 46))
            drawFlyffVitalBar(bmp, 104, 28, 280, 42, 100, VitalType.HP)
            drawFlyffVitalBar(bmp, 104, 50, 280, 64, a2MpPct.get(), VitalType.MP)
            drawFlyffVitalBar(bmp, 104, 72, 280, 86, 100, VitalType.FP)

            // A1 bottom pane
            fillBitmapRect(bmp, 12, 812, 290, 908, Color.rgb(34, 38, 46))
            drawFlyffVitalBar(bmp, 104, 828, 280, 842, a1HpPct.get(), VitalType.HP)
            drawFlyffVitalBar(bmp, 104, 850, 280, 864, 100, VitalType.MP)
            drawFlyffVitalBar(bmp, 104, 872, 280, 886, 100, VitalType.FP)

            // A1 active buff panel (y=920..1020)
            fillBitmapRect(bmp, 16, 920, 280, 1020, Color.rgb(26, 30, 38))
            val canvas = Canvas(bmp)
            if (showBuffUp.get()) {
                canvas.drawBitmap(buffUpBmp, 24f, 928f, null)
            }
            if (showBuffMissing.get()) {
                canvas.drawBitmap(buffMissingBmp, 64f, 928f, null)
            }

            // Optional Resurrection dialog inside A1 pane (y=800..1600, W=900)
            if (showResDialog.get()) {
                val dlgLeft = (w * 0.41f).toInt()
                val dlgRight = (w * 0.59f).toInt()
                val dlgTop = 800 + (w * 0.055f).toInt()
                val dlgBot = 800 + (w * 0.255f).toInt()
                // Cream parchment background
                fillBitmapRect(bmp, dlgLeft, dlgTop, dlgRight, dlgBot, Color.rgb(235, 224, 198))
                // 4 stacked golden-yellow buttons separated by cream gaps
                for (btnIdx in 0 until 4) {
                    val bt = dlgTop + 14 + btnIdx * 38
                    val bb = bt + 24
                    fillBitmapRect(bmp, dlgLeft + 10, bt, dlgRight - 10, bb, Color.rgb(225, 180, 50))
                }
            }
            return bmp
        }

        val testDetector = object : ScreenDetector(context) {
            init { _isActive.value = true }
            override suspend fun captureLatestBitmap(): Bitmap = buildFrame()
        }

        val dispatched = java.util.Collections.synchronizedList(mutableListOf<Pair<WebViewTarget, Int>>())
        val bridge = DualWebInputBridge()
        val delegate = object : DualWebMacroDelegate {
            override fun dispatchKey(keyCode: Int, holdMs: Long, metaState: Int, targetOverride: WebViewTarget): Boolean {
                dispatched.add(targetOverride to keyCode)
                return true
            }
            override fun dispatchTap(screenX: Float, screenY: Float, holdMs: Long, jitterPx: Int, targetOverride: WebViewTarget) = true
            override fun dispatchSwipe(startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long, steps: Int, targetOverride: WebViewTarget) = true
            override fun describeTarget(targetOverride: WebViewTarget) = targetOverride.shortLabel
            override fun currentLayoutMode() = DualLayoutMode.SPLIT_50_50
            override fun currentPaneRects() = Rect(0, 800, w, h) to Rect(0, 0, w, 800)
        }
        bridge.attach(delegate)

        val healKey = ScannedSkillKey(id = "k-heal", owner = WebViewTarget.ACCOUNT_2, label = "Heal", category = SkillCategory.HEAL, keyCode = KeyEvent.KEYCODE_1, keyLabel = "1")
        val a2MpKey = ScannedSkillKey(id = "k-a2mp", owner = WebViewTarget.ACCOUNT_2, label = "RM MP", category = SkillCategory.MP_POT, keyCode = KeyEvent.KEYCODE_2, keyLabel = "2")
        val buffUpKey = ScannedSkillKey(id = "k-heap", owner = WebViewTarget.ACCOUNT_2, label = "Heap Up", category = SkillCategory.PARTY_BUFF, keyCode = KeyEvent.KEYCODE_4, keyLabel = "4")
        val buffMissingKey = ScannedSkillKey(id = "k-haste", owner = WebViewTarget.ACCOUNT_2, label = "Haste", category = SkillCategory.PARTY_BUFF, keyCode = KeyEvent.KEYCODE_5, keyLabel = "5")
        val atkKey = ScannedSkillKey(id = "k-atk", owner = WebViewTarget.ACCOUNT_1, label = "Psychic Bomb", category = SkillCategory.ATTACK_AOE, keyCode = KeyEvent.KEYCODE_9, keyLabel = "9")

        val buffUpDef = ActiveBuffDefinition(
            id = "b-heap",
            targetChar = WebViewTarget.ACCOUNT_1,
            label = "Heap Up",
            perLayoutTemplateIds = mapOf(DualLayoutMode.SPLIT_50_50 to buffUpTpl.id)
        )
        val buffMissingDef = ActiveBuffDefinition(
            id = "b-haste",
            targetChar = WebViewTarget.ACCOUNT_1,
            label = "Haste",
            perLayoutTemplateIds = mapOf(DualLayoutMode.SPLIT_50_50 to buffMissingTpl.id)
        )

        val a1Hud = CharacterHudRegions(
            owner = WebViewTarget.ACCOUNT_1,
            paneRect = RectSpec.fromPixels(0, 800, w, h, w, h),
            hpBar = BarCalibrationSpec(VitalType.HP, RectSpec.fromPixels(104, 828, 280, 842, w, h)),
            mpBar = BarCalibrationSpec(VitalType.MP, RectSpec.fromPixels(104, 850, 280, 864, w, h)),
            fpBar = BarCalibrationSpec(VitalType.FP, RectSpec.fromPixels(104, 872, 280, 886, w, h)),
            buffGridRegion = RectSpec.fromPixels(16, 920, 280, 1020, w, h)
        )
        val a2Hud = CharacterHudRegions(
            owner = WebViewTarget.ACCOUNT_2,
            paneRect = RectSpec.fromPixels(0, 0, w, 800, w, h),
            hpBar = BarCalibrationSpec(VitalType.HP, RectSpec.fromPixels(104, 28, 280, 42, w, h)),
            mpBar = BarCalibrationSpec(VitalType.MP, RectSpec.fromPixels(104, 50, 280, 64, w, h)),
            fpBar = BarCalibrationSpec(VitalType.FP, RectSpec.fromPixels(104, 72, 280, 86, w, h)),
            buffGridRegion = RectSpec.fromPixels(16, 120, 280, 220, w, h)
        )

        val profile = FlyffProfile(
            name = "Full Lifecycle Test",
            pollingIntervalMs = 30L,
            buffScanIntervalMs = 600L,
            minBuffRecastSec = 480, // 8-minute wait after initial sweep
            skillKeys = listOf(healKey, a2MpKey, buffUpKey, buffMissingKey, atkKey),
            activeBuffs = listOf(buffUpDef, buffMissingDef),
            buffLinks = listOf(
                BuffKeyLink(id = "l-heap", skillKeyId = buffUpKey.id, activeBuffId = buffUpDef.id, priority = 0, castGuardMs = 150L),
                BuffKeyLink(id = "l-haste", skillKeyId = buffMissingKey.id, activeBuffId = buffMissingDef.id, priority = 1, castGuardMs = 150L)
            ),
            layouts = mapOf(
                DualLayoutMode.SPLIT_50_50 to LayoutHudCalibration(
                    layoutMode = DualLayoutMode.SPLIT_50_50,
                    screenWidth = w,
                    screenHeight = h,
                    charHuds = mapOf(WebViewTarget.ACCOUNT_1 to a1Hud, WebViewTarget.ACCOUNT_2 to a2Hud)
                )
            ),
            healRule = HealRuleConfig(
                enabled = true,
                targetChar = WebViewTarget.ACCOUNT_1,
                healSkillKeyId = healKey.id,
                triggerBelowPct = 80,
                safeBuffMinHpPct = 85,
                postHealStabilizeChecks = 1,
                healCadenceMs = 60L
            ),
            vitalTriggers = listOf(
                VitalTriggerRule(
                    id = "vt-a2mp",
                    targetChar = WebViewTarget.ACCOUNT_2,
                    vitalType = VitalType.MP,
                    triggerBelowPct = 50,
                    skillKeyId = a2MpKey.id,
                    cooldownMs = 120L
                )
            ),
            combatConfig = CombatRotationConfig(
                enabled = true,
                manualCombatEnabled = true,
                attackSkillKeyIds = listOf(atkKey.id),
                attackIntervalMs = 60L
            )
        )

        val engine = SmartFlyffEngine(repo, testDetector, bridge, BuffTimerOcrReader())
        engine.start(profile)

        // STAGE 1: Startup Initial Buff Sweep — casts ONLY MissingHaste (KEYCODE_5), NEVER ActiveHeapUp (KEYCODE_4)
        var waited = 0
        while (waited < 40 && dispatched.none { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_5) }) {
            delay(50L)
            waited++
        }
        assertTrue(
            "Startup sweep MUST cast missing buff Haste (KEYCODE_5), fired=$dispatched",
            dispatched.any { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_5) }
        )
        assertEquals(
            "Startup sweep MUST NOT cast already-active Heap Up (KEYCODE_4)",
            0,
            dispatched.count { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_4) }
        )

        // Now paint Haste on screen -> sweep completes and enters 8-minute wait!
        showBuffMissing.set(true)
        delay(550L)
        dispatched.clear()
        // Even if Haste disappears immediately after sweep completes, ZERO buff replacement casts should fire during the 8-min wait!
        showBuffMissing.set(false)
        delay(500L)
        assertEquals(
            "During 8-min post-sweep wait, ZERO buff casts should fire, fired=$dispatched",
            0,
            dispatched.count { it.second == KeyEvent.KEYCODE_4 || it.second == KeyEvent.KEYCODE_5 }
        )

        // STAGE 2: Char 1 Chat / Typing Pause -> stops ONLY ACCOUNT_1 keys while RM (ACCOUNT_2) continues!
        bridge.setAccount1TypingActive(true)
        a2MpPct.set(25) // RM needs MP pot
        delay(100L)
        dispatched.clear()
        delay(350L)
        val chatFired = dispatched.toList()
        assertEquals(
            "When Char 1 is chatting/typing, ZERO ACCOUNT_1 keys may fire, fired=$chatFired",
            0,
            chatFired.count { it.first == WebViewTarget.ACCOUNT_1 }
        )
        assertTrue(
            "When Char 1 is chatting/typing, RM (ACCOUNT_2) MUST continue firing MP pot, fired=$chatFired",
            chatFired.any { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_2) }
        )
        bridge.setAccount1TypingActive(false)
        a2MpPct.set(100)

        // STAGE 3: Death full-stop -> ZERO keys for BOTH Char 1 AND RM!
        a1HpPct.set(0)
        a2MpPct.set(20) // RM wants MP pot, A1 wants Heal & Attack — ALL must be stopped!
        showResDialog.set(true)
        showBuffUp.set(false)
        showBuffMissing.set(false)
        var deadWait = 0
        while (deadWait < 25 && engine.state.value.phase != SmartEnginePhase.DEAD_PAUSED) {
            delay(40L)
            deadWait++
        }
        assertEquals("Engine MUST enter DEAD_PAUSED when main char dies", SmartEnginePhase.DEAD_PAUSED, engine.state.value.phase)
        dispatched.clear()
        delay(350L)
        assertTrue(
            "While DEAD, ALL keys (both A1 & A2) MUST be stopped, fired=$dispatched",
            dispatched.isEmpty()
        )

        // STAGE 4: Revive -> resets 8-minute lockout and immediately triggers full buff refresh sweep!
        showResDialog.set(false)
        a1HpPct.set(95)
        a2MpPct.set(100)
        dispatched.clear()
        var reviveWait = 0
        while (reviveWait < 40 && (
                dispatched.none { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_4) } ||
                    dispatched.none { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_5) }
                )
        ) {
            delay(50L)
            reviveWait++
        }
        val reviveFired = dispatched.toList()
        assertTrue(
            "After Revive, full buff sweep MUST cast Heap Up (KEYCODE_4), fired=$reviveFired",
            reviveFired.any { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_4) }
        )
        assertTrue(
            "After Revive, full buff sweep MUST cast Haste (KEYCODE_5), fired=$reviveFired",
            reviveFired.any { it == (WebViewTarget.ACCOUNT_2 to KeyEvent.KEYCODE_5) }
        )

        engine.stop()
        bridge.detach(delegate)
        buffUpBmp.recycle()
        buffMissingBmp.recycle()
        repo.deleteTemplate(buffUpTpl.id)
        repo.deleteTemplate(buffMissingTpl.id)
        db.close()
    }
}
