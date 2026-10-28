#!/usr/bin/env python3
"""Exercise the shipped SwitchFeature on the JVM with small host API stubs.

Run with kotlinc on PATH, or pass --compiler-classpath for an embeddable compiler.
This verifies controller lifecycle, not Android hooks or real MMKV IPC.
"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import textwrap

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/Johnny/wcx/features/core/SwitchFeature.kt"
FIXTURES = {
    "Context.kt": """
package android.content
open class Context
""",
    "Preferences.kt": """
package com.Johnny.wcx.constants
object Preferences {
    var secretFriendMasterEnabled = true
    var articleAdMasterEnabled = false
}
""",
    "WePrefs.kt": """
package com.Johnny.wcx.preferences
object WePrefs {
    private val values = mutableMapOf<String, Boolean>()
    fun getBoolOrDef(name: String, default: Boolean) = values[name] ?: default
    fun putBool(name: String, value: Boolean) { values[name] = value }
}
""",
    "Host.kt": """
package com.Johnny.wcx.utils
object TargetProcesses { var isInMain = true }
object WeLogger { fun i(tag: String, message: String) {} }
""",
    "BaseFeature.kt": """
package com.Johnny.wcx.features.core
abstract class BaseFeature {
    var name = ""
    var categories = emptyList<String>()
    val displayName get() = name
    var isActive = false
        private set
    var installs = 0
        private set
    var removals = 0
        private set
    open fun startup() {}
    fun enable() {
        if (isActive) return
        isActive = true
        installs++
    }
    fun disable() {
        if (!isActive) return
        isActive = false
        removals++
    }
}
""",
    "Scenarios.kt": """
package test
import android.content.Context
import com.Johnny.wcx.constants.Preferences
import com.Johnny.wcx.features.core.SwitchFeature
import com.Johnny.wcx.preferences.WePrefs
import com.Johnny.wcx.utils.TargetProcesses

private class Controller : SwitchFeature() {
    override val underArticleAdMaster = true
    override val keepControllerEnabled = true
    override val shouldLoadInCurrentProcess = true
    fun filtering() = isMasterGroupEnabled
}
private class Ordinary : SwitchFeature()

fun main() {
    // Both main and already-running article hosts start controllers while master is off.
    val main = Controller().apply { name = "article"; startup() }
    TargetProcesses.isInMain = false
    val articleHost = Controller().apply { name = "article"; startup() }
    check(main.isActive && articleHost.isActive)
    check(!main.filtering() && !articleHost.filtering())

    // A child may be configured while master is off; the shared preference alone
    // must reach an already-started host once the master is enabled.
    check(main.onBeforeToggle(true, Context()))
    main.applyToggle(true)
    check(main.configuredEnabled && !articleHost.filtering())
    Preferences.articleAdMasterEnabled = true
    main.isEnabled = main.configuredEnabled
    check(main.filtering() && articleHost.filtering())

    // Master off preserves child choices, running controllers and pending cleanup.
    repeat(3) {
        Preferences.articleAdMasterEnabled = false
        main.isEnabled = main.configuredEnabled
        check(main.configuredEnabled && articleHost.configuredEnabled)
        check(main.isActive && articleHost.isActive)
        check(!main.filtering() && !articleHost.filtering())
        Preferences.articleAdMasterEnabled = true
        main.isEnabled = main.configuredEnabled
        check(main.filtering() && articleHost.filtering())
    }

    // Child off also retains the controller, without duplicate hooks on re-enable.
    main.applyToggle(false)
    check(main.isActive && !main.filtering() && !articleHost.filtering())
    main.applyToggle(true)
    check(main.filtering() && articleHost.filtering())
    check(main.installs == 1 && main.removals == 0)
    check(articleHost.installs == 1 && articleHost.removals == 0)

    // Repeating the saved value can recover an inactive controller.
    main.disable()
    main.isEnabled = true
    check(main.isActive && main.installs == 2)

    // Unrelated features retain their existing main-process and toggle behavior.
    val skipped = Ordinary().apply { name = "ordinary"; startup() }
    check(!skipped.isActive)
    TargetProcesses.isInMain = true
    val ordinary = Ordinary().apply { name = "ordinary"; startup() }
    check(!ordinary.isActive)
    ordinary.applyToggle(true)
    check(ordinary.isActive)
    ordinary.applyToggle(false)
    check(!ordinary.isActive && ordinary.removals == 1)
    Preferences.secretFriendMasterEnabled = false
    WePrefs.putBool("secret", true)
    val secret = Ordinary().apply {
        name = "secret"
        categories = listOf(SwitchFeature.SECRET_FRIEND_CATEGORY)
        startup()
    }
    check(!secret.isActive)
    println("PASS: actual SwitchFeature JVM lifecycle: cold start off, shared saved choices, master/child off-on, controller recovery, ordinary and secret-category behavior.")
}
""",
}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler-classpath")
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="wcx-switch-test-") as temporary:
        directory = Path(temporary)
        fixtures = []
        for name, content in FIXTURES.items():
            path = directory / name
            path.write_text(textwrap.dedent(content).lstrip(), encoding="utf-8")
            fixtures.append(str(path))
        jar = str(directory / "test.jar")
        if args.compiler_classpath:
            classpath = args.compiler_classpath
            stdlib = next(path for path in classpath.split(os.pathsep)
                          if Path(path).name.startswith("kotlin-stdlib-"))
            compiler = ["java", "-cp", classpath, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                        "-no-stdlib", "-no-reflect", "-classpath", stdlib]
            runner = ["java", "-cp", jar + os.pathsep + stdlib, "test.ScenariosKt"]
        else:
            kotlinc = shutil.which("kotlinc")
            if not kotlinc:
                parser.error("kotlinc is required, or pass --compiler-classpath")
            compiler = [kotlinc, "-include-runtime"]
            runner = ["java", "-jar", jar]
        subprocess.run(compiler + [str(SOURCE), *fixtures, "-d", jar], check=True)
        subprocess.run(runner, check=True)


if __name__ == "__main__":
    main()
