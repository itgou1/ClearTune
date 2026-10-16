package com.cleartune.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import java.io.File

/** Opt-in UI driver for the real activity and real backend; no ViewModel or network substitutes. */
class MusicTagDeviceFlowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()

    @Test fun driveRealActivity() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ctDeviceFlow") == "true")
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        val command = File(directory, "ct-ui-command.json")
        val result = File(directory, "ct-ui-result.json")
        result.writeText("{\"status\":\"ready\"}")
        val deadline = System.currentTimeMillis() + 30 * 60_000
        while (System.currentTimeMillis() < deadline) {
            if (!command.exists()) { ui.waitForIdle(); Thread.sleep(100); continue }
            val data = Json.parseToJsonElement(command.readText()).jsonObject
            command.delete()
            val id = data["id"]!!.jsonPrimitive.content
            val action = data["action"]!!.jsonPrimitive.content
            val label = data["label"]?.jsonPrimitive?.content.orEmpty()
            try {
                val matcher = if (data["description"]?.jsonPrimitive?.booleanOrNull == true)
                    hasContentDescription(label) else hasText(label)
                when (action) {
                    "fill" -> ui.onNode(matcher and hasSetTextAction()).performScrollTo().performTextReplacement(data["value"]!!.jsonPrimitive.content)
                    "click" -> {
                        val node = ui.onAllNodes(matcher)[data["index"]?.jsonPrimitive?.intOrNull ?: 0]
                        runCatching { node.performScrollTo() }
                        node.performClick()
                    }
                    "back" -> InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                    "assert" -> ui.onNode(matcher).assertExists()
                    "finish" -> { result.writeText("{\"id\":\"$id\",\"status\":\"done\"}"); return }
                    else -> error("Unknown action")
                }
                ui.waitForIdle()
                result.writeText("{\"id\":\"$id\",\"status\":\"ok\"}")
            } catch (error: Throwable) {
                // Do not persist Compose failure messages: they can include field contents.
                result.writeText("{\"id\":\"$id\",\"status\":\"failed\",\"type\":\"${error.javaClass.simpleName}\"}")
            }
        }
        error("Device UI driver timed out")
    }
}
