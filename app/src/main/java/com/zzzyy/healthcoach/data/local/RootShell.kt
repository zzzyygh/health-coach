package com.zzzyy.healthcoach.data.local

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStreamReader

/**
 * 通过 Magisk 的 `su` 执行 shell 命令。
 *
 * app 自身没有 /data/data/com.mi.health 的读权限（别的 app 的私有目录），
 * 只能借 root shell 把数据取出来。
 *
 * ⚠️ 三个踩过的坑（都表现为「界面永远卡在启动画面」）：
 *   1. `su` 起的是**交互式 shell**，执行完命令不会自己退出 → 读 stdout 永久阻塞。
 *      交互式回退必须显式追加 `exit` 并关掉 stdin。
 *   2. stderr 不读会堆在管道里把进程卡住 → 合并进 stdout。
 *   3. `su -c "cmd"` 必须作为**独立参数**传，不能拼进一个字符串，
 *      否则命令里的引号会被 app 自己的 shell 先吃掉一层。
 */
object RootShell {

    private const val TAG = "HealthCoach.Root"

    /** 是否有 root（Magisk/KernelSU 都可用）。 */
    fun isAvailable(): Boolean = try {
        val (code, output) = exec("id")
        val ok = output.contains("uid=0")
        Log.i(TAG, "isAvailable: exit=$code out='${output.take(120).trim()}' → $ok")
        ok
    } catch (e: Exception) {
        Log.w(TAG, "isAvailable 异常：${e.message}")
        false
    }

    /** 执行命令，返回 (退出码, 合并后的输出)。 */
    fun exec(command: String, timeoutMs: Long = 30_000L): Pair<Int, String> {
        Log.d(TAG, "exec 开始：cmd='${command.take(140)}' timeout=${timeoutMs}ms")
        val result = try {
            // ⚠️ 必须带 -mm：Android 11+ 每个 app 有独立的 mount namespace，
            // 不加的话即使拿到 root 也**看不到别的 app 的私有数据目录**
            // （症状：ls /data/data/<别的app> → No such file or directory）。
            // -mm 让 su 切到全局 mount namespace，才能读到 com.mi.health 的库。
            val process = ProcessBuilder("su", "-mm", "-c", command)
                .redirectErrorStream(true)
                .start()

            // ⚠️ 关键：立刻给 su 一个 EOF。
            // app 进程的 stdin 是管道，su 会等它关闭才肯执行命令；
            // 不关的话 su 一直等输入，整条链路就卡死在这里。
            process.outputStream.close()

            val builder = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        val buffer = CharArray(4096)
                        while (true) {
                            val read = r.read(buffer)
                            if (read <= 0) break
                            synchronized(builder) { builder.append(buffer, 0, read) }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "读取 su 输出异常：${e.message}")
                }
            }
            reader.isDaemon = true
            reader.start()
            reader.join(timeoutMs)

            if (reader.isAlive) {
                Log.w(TAG, "su 命令超时（${timeoutMs}ms），强制结束：cmd=${command.take(100)}")
                process.destroyForcibly()
                reader.join(1000)
                -1 to ""
            } else {
                val code = process.waitFor()
                code to synchronized(builder) { builder.toString() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "exec(su -c) 异常：${e.message}")
            -1 to ""
        }

        // su 的参数不被支持时会返回非 0 且输出里带用法提示 → 退回交互式
        val needsFallback = result.first != 0 &&
            (result.second.contains("usage", true) ||
                result.second.contains("unrecognized", true) ||
                result.second.contains("invalid option", true) ||
                result.second.contains("not found", true))

        if (needsFallback) {
            Log.i(TAG, "su -c 不被支持，回退到交互式：cmd=${command.take(100)}")
            return execInteractive(command)
        }

        Log.d(TAG, "exec: cmd='${command.take(140)}' exit=${result.first} out='${result.second.take(200).trim()}'")
        return result
    }

    fun run(command: String): String = exec(command).second

    /**
     * 交互式回退：逐行写入命令，读到 EOF 为止。
     *
     * 关键点：写完命令后必须补 `exit` 并关闭 stdin，
     * 否则 su 的 shell 会一直等着下一条命令，读取端永久阻塞。
     */
    private fun execInteractive(command: String): Pair<Int, String> {
        val process = ProcessBuilder("su", "-mm").redirectErrorStream(true).start()
        val output = DataOutputStream(process.outputStream)
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        try {
            output.write("$command\nexit\n".toByteArray())
            output.flush()
        } catch (e: IOException) {
            Log.w(TAG, "交互式写入失败：${e.message}")
        }
        output.close()

        val builder = StringBuilder()
        val buffer = CharArray(4096)
        while (true) {
            val read = try {
                reader.read(buffer)
            } catch (e: IOException) {
                break
            }
            if (read <= 0) break
            builder.append(buffer, 0, read)
        }
        reader.close()
        val code = process.waitFor()
        Log.d(TAG, "execInteractive: cmd='${command.take(140)}' exit=$code out='${builder.toString().take(200).trim()}'")
        return code to builder.toString()
    }

    /**
     * 用 root 权限把文件复制到 app 私有目录，返回目标路径。
     *
     * ⚠️ 复制完必须 chown 回 app 自己的 uid/gid：
     * root 复制出来的文件属主是 root:root，app 进程（普通 uid）读不了，
     * 会表现为「复制成功但打开数据库失败」。
     */
    fun copyToApp(sourcePath: String, targetPath: String): String {
        // Android 上 app 的 gid 就是它自己的 uid（没有单独的用户组）
        val uid = android.os.Process.myUid()
        val gid = uid
        val (code, output) = exec(
            "cp -f '$sourcePath' '$targetPath'; " +
                "cp -f '${sourcePath}-wal' '${targetPath}-wal' 2>/dev/null; " +
                "cp -f '${sourcePath}-shm' '${targetPath}-shm' 2>/dev/null; " +
                "chown $uid:$gid '$targetPath' '${targetPath}-wal' '${targetPath}-shm' 2>/dev/null; " +
                "echo __OK__",
            timeoutMs = 180_000L,
        )
        if (!output.contains("__OK__")) {
            throw IOException("复制失败（exit=$code）：${output.take(200)}")
        }
        return targetPath
    }

    /** 判断文件是否存在。 */
    fun exists(path: String): Boolean =
        exec("test -f '$path' && echo __OK__").second.contains("__OK__")
}
