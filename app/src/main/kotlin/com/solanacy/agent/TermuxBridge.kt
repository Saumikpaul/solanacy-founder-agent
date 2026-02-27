package com.solanacy.agent

import android.content.Context
import android.content.Intent
import android.os.Environment
import java.io.File

object TermuxBridge {

    // Termux এ command run করার জন্য script file লিখব
    // Termux:Tasker বা file based approach

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

            // Write script
            val wd = if (workDir.isNotBlank()) {
                "/storage/emulated/0/Solanacy/$workDir"
            } else {
                "/storage/emulated/0/Solanacy"
            }

            scriptFile.writeText("""
#!/bin/bash
cd "$wd" 2>&1
$command > "${outputFile.absolutePath}" 2> "${errorFile.absolutePath}"
echo $? >> "${outputFile.absolutePath}"
""".trimIndent())

            // Run via Termux:API am broadcast
            val process = Runtime.getRuntime().exec(arrayOf(
                "am", "broadcast",
                "-a", "com.termux.RUN_COMMAND",
                "-n", "com.termux/com.termux.app.RunCommandService",
                "--es", "com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/bash",
                "--esa", "com.termux.RUN_COMMAND_ARGUMENTS", scriptFile.absolutePath,
                "--es", "com.termux.RUN_COMMAND_WORKDIR", wd,
                "--ez", "com.termux.RUN_COMMAND_BACKGROUND", "true"
            ))
            process.waitFor()

            // Wait for output
            Thread.sleep(3000)

            val output = if (outputFile.exists()) outputFile.readText().trim() else ""
            val error = if (errorFile.exists()) errorFile.readText().trim() else ""

            if (error.isNotBlank()) "ERROR: $error\nOUTPUT: $output"
            else if (output.isNotBlank()) output
            else "Command executed"
        } catch (e: Exception) {
            "Error running command: ${e.message}"
        }
    }
}
