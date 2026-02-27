package com.solanacy.agent

import android.os.Environment
import java.io.File

object TermuxBridge {

    private fun getScriptDir(): File {
        val dir = File(Environment.getExternalStorageDirectory(), "Solanacy/.scripts")
        dir.mkdirs()
        return dir
    }

    fun runCommand(command: String, workDir: String = ""): String {
        return try {
            val scriptFile = File(getScriptDir(), "run_cmd.sh")
            val outputFile = File(getScriptDir(), "output.txt")
            val errorFile = File(getScriptDir(), "error.txt")
            val doneFile = File(getScriptDir(), "done.flag")

            // Clean old files
            outputFile.delete()
            errorFile.delete()
            doneFile.delete()

            val wd = if (workDir.isNotBlank())
                "/storage/emulated/0/Solanacy/$workDir"
            else
                "/storage/emulated/0/Solanacy"

            scriptFile.writeText("""
#!/bin/bash
cd "$wd" 2>&1
$command > "${outputFile.absolutePath}" 2> "${errorFile.absolutePath}"
echo "EXIT:$?" >> "${outputFile.absolutePath}"
touch "${doneFile.absolutePath}"
""".trimIndent())
            scriptFile.setExecutable(true)

            // Run via Termux broadcast
            Runtime.getRuntime().exec(arrayOf(
                "am", "broadcast",
                "-a", "com.termux.RUN_COMMAND",
                "-n", "com.termux/com.termux.app.RunCommandService",
                "--es", "com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash",
                "--es", "com.termux.RUN_COMMAND_ARGUMENTS", scriptFile.absolutePath,
                "--es", "com.termux.RUN_COMMAND_WORKDIR", wd,
                "--ez", "com.termux.RUN_COMMAND_BACKGROUND", "true"
            )).waitFor()

            // Wait for done flag — max 30 seconds
            var waited = 0
            while (!doneFile.exists() && waited < 30000) {
                Thread.sleep(500)
                waited += 500
            }

            val output = if (outputFile.exists()) outputFile.readText().trim() else ""
            val error = if (errorFile.exists()) errorFile.readText().trim() else ""

            when {
                waited >= 30000 -> "Timeout after 30s. Output so far: $output"
                error.isNotBlank() -> "ERROR:\n$error\nOUTPUT:\n$output"
                output.isNotBlank() -> output
                else -> "Command executed (no output)"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}
