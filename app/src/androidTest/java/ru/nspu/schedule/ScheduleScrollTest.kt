package ru.nspu.schedule

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.work.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.TimeUnit

/** Test-APK-only fixtures: no network, credentials or benchmark hooks in the installed app. */
@RunWith(AndroidJUnit4::class)
class ScheduleScrollTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private val output = File(context.getExternalFilesDir(null), "performance")
    private val teacher = "Петров Иван Иванович"
    private val source = "https://schedule.nspu.ru/"
    private val times = listOf("8:30-10:00", "10:10-11:40", "12:10-13:40", "14:10-15:40",
        "15:50-17:20", "17:30-19:00", "19:10-20:40", "20:50-22:20")

    @Test fun seededHomeAndTeacherScrollAndRoomNavigation() {
        output.mkdirs()
        output.listFiles()?.forEach { it.delete() }
        seed()
        // Keep the app's normal periodic sync parked while the deterministic fixture is measured.
        val work = WorkManager.getInstance(context)
        work.cancelAllWork().result.get(10, TimeUnit.SECONDS)
        work.enqueueUniquePeriodicWork("nspu-sync", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<ParkedSyncWorker>(3, TimeUnit.HOURS)
                .setInitialDelay(1, TimeUnit.DAYS).build()).result.get(10, TimeUnit.SECONDS)
        ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use {
            assertTrue("Home fixture did not load", device.wait(Until.hasObject(By.text("ТВОЯ ГРУППА")), 15_000))
            device.waitForIdle(2_000)
            device.takeScreenshot(File(output, "home.png"))
            val verify = InstrumentationRegistry.getArguments().getString("verifyCards", "true") == "true"
            if (verify) {
                assertFalse("Navigation arrows remain on lesson cards",
                    device.hasObject(By.text(java.util.regex.Pattern.compile(".*↗.*"))))
                val badge = device.findObject(By.desc("Аудитория 411"))
                assertNotNull("Room badge is missing", badge)
                val bounds = badge.visibleBounds
                assertTrue("Room badge must be square", kotlin.math.abs(bounds.width() - bounds.height()) <= 3)
                assertTrue("Room badge must be on the right", bounds.centerX() > device.displayWidth * .65)
                assertTrue("Room badge must be clickable", badge.isClickable)
                badge.click()
                assertTrue("Room click did not open the native timetable",
                    device.wait(Until.hasObject(By.text("Расписание аудитории")), 10_000))
                device.takeScreenshot(File(output, "room.png"))
                device.pressBack()
                assertTrue(device.wait(Until.hasObject(By.text("ТВОЯ ГРУППА")), 10_000))
            }
            measure("home")
            top()
            val teacherObject = device.findObject(By.textStartsWith(teacher))
            assertNotNull("Teacher link is missing", teacherObject)
            teacherObject.click()
            assertTrue(device.wait(Until.hasObject(By.text("Расписание преподавателя")), 10_000))
            assertTrue("Cached teacher fixture did not render",
                device.wait(Until.hasObject(By.text("Алгебра 1")), 10_000))
            device.takeScreenshot(File(output, "teacher.png"))
            measure("teacher")
            if (verify) {
                device.findObject(By.text("Заочные")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Заочные занятия по датам")), 5_000))
                device.findObject(By.text("Сессия")).click()
                assertTrue(device.wait(Until.hasObject(By.text("Сессия · консультации и экзамены")), 5_000))
                device.pressBack()
                assertTrue("Back must return to the home timetable",
                    device.wait(Until.hasObject(By.text("Расписание НГПУ")), 10_000))
            }
        }
    }

    private fun measure(label: String) {
        // Warm text/layout caches before recording. Same fixture and gestures for v2.7 and current.
        repeat(2) { swipe(up = true) }
        repeat(2) { swipe(up = false) }
        shell("dumpsys gfxinfo ${context.packageName} reset")
        repeat(6) { swipe(up = true) }
        repeat(6) { swipe(up = false) }
        device.waitForIdle(2_000)
        File(output, "$label-frames.txt").writeText(shell("dumpsys gfxinfo ${context.packageName} framestats"))
        File(output, "$label-memory.txt").writeText(shell("dumpsys meminfo ${context.packageName}"))
    }

    private fun swipe(up: Boolean) {
        val x = device.displayWidth / 2
        val upper = device.displayHeight * 32 / 100
        val lower = device.displayHeight * 75 / 100
        assertTrue(device.swipe(x, if (up) lower else upper, x, if (up) upper else lower, 30))
        SystemClock.sleep(120)
    }

    private fun top() {
        repeat(20) {
            if (device.hasObject(By.text("ТВОЯ ГРУППА"))) return
            swipe(up = false)
        }
        fail("Could not return to the first lesson")
    }

    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        FileInputStream(it.fileDescriptor).bufferedReader().readText()
    }

    private fun seed() {
        val today = LocalDate.now(ZoneId.of("Asia/Novosibirsk"))
        val selected = if (today.dayOfWeek == DayOfWeek.SUNDAY) today.plusDays(1) else today
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val term = monday.minusWeeks(6)
        val lessons = JSONArray()
        for (day in 0..5) for ((slot, time) in times.withIndex()) for (copy in 0..3) {
            lessons.put(JSONObject().put("day", day).put("time", time)
                .put("raw", "Алгебра ${slot * 4 + copy + 1}, $teacher, (п.з.: 1-24 нед.), а. 411")
                .put("changed", false))
        }
        val links = JSONArray().put(JSONObject().put("kind", "TEACHER").put("label", teacher)
            .put("url", source + "teacher_shedule.php?id=452"))
            .put(JSONObject().put("kind", "ROOM").put("label", "411")
                .put("url", source + "room_shedule.php?id=31"))
        assertTrue(context.getSharedPreferences("schedule", Context.MODE_PRIVATE).edit().clear()
            .putInt("group_id", 3328).putString("group_name", "3.008.2.26 ИСиТвО")
            .putString("subgroup", "Все").putString("selected_date", selected.toString())
            .putString("term_start", term.toString()).putString("lessons", lessons.toString())
            .putString("notices", "[]").putString("change_history", "[]")
            .putString("updated", "Тестовая локальная копия").putString("schedule_links", links.toString()).commit())
        assertTrue(context.getSharedPreferences("app-settings", Context.MODE_PRIVATE).edit().clear()
            .putString("theme", "light").putBoolean("auto_scroll", false)
            .putBoolean("animate_highlight", true).putBoolean("update_prompts", false).commit())
        assertTrue(context.getSharedPreferences("app-updates", Context.MODE_PRIVATE).edit().clear()
            .putLong("last_stable", System.currentTimeMillis()).commit())
        for (path in listOf("teacher_shedule.php?id=452", "room_shedule.php?id=31")) {
            val sessions = JSONArray()
            for (day in 0..5) for ((slot, time) in times.withIndex()) {
                sessions.put(JSONObject().put("time", time).put("subject", "Алгебра ${slot + 1}")
                    .put("description", "Алгебра, 3.008.2.26, а. 411, (п.з.): 1–24 нед.")
                    .put("kind", "ПРАКТИКА").put("day", day).put("date", "").put("section", "WEEKLY")
                    .put("links", JSONArray().put(JSONObject().put("label", "3.008.2.26")
                        .put("url", source + "group_shedule.php?id=3328").put("type", "group"))
                        .put(JSONObject().put("label", "411")
                            .put("url", source + "room_shedule.php?id=31").put("type", "room"))))
            }
            val data = JSONObject().put("title", teacher).put("week", 7).put("weekStart", monday.toString())
                .put("roomInfo", "Тестовая аудитория").put("hasPast", true).put("sessions", sessions)
            val request = source + path + "&nextweek=0"
            val digest = MessageDigest.getInstance("SHA-256").digest(request.toByteArray())
                .take(12).joinToString("") { "%02x".format(it) }
            File(context.cacheDir, "nspu-public-$digest.json").writeText(data.toString())
        }
    }
}

/** Never runs: reserves the regular sync name with a delayed request in the test process. */
class ParkedSyncWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result = Result.success()
}
