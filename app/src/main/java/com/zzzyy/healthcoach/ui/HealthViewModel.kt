package com.zzzyy.healthcoach.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zzzyy.healthcoach.data.HealthRepository
import com.zzzyy.healthcoach.data.advice.Advice
import com.zzzyy.healthcoach.data.advice.AdviceClient
import com.zzzyy.healthcoach.data.advice.AdvicePrompt
import com.zzzyy.healthcoach.data.advice.ModelConfig
import com.zzzyy.healthcoach.data.advice.ModelConfigStore
import com.zzzyy.healthcoach.data.advice.buildFullCheckup
import com.zzzyy.healthcoach.data.backup.HealthBackup
import com.zzzyy.healthcoach.data.backup.WebDavClient
import com.zzzyy.healthcoach.data.backup.WebDavConfig
import com.zzzyy.healthcoach.data.backup.WebDavConfigStore
import com.zzzyy.healthcoach.data.cloud.MiAuth
import com.zzzyy.healthcoach.data.cloud.MiToken
import com.zzzyy.healthcoach.data.analysis.SleepScorer
import com.zzzyy.healthcoach.data.cloud.MiTokenStore
import com.zzzyy.healthcoach.data.store.SleepGoalStore
import com.zzzyy.healthcoach.data.model.FeedbackEntry
import com.zzzyy.healthcoach.data.model.AdviceRecord
import com.zzzyy.healthcoach.data.model.HealthDay
import com.zzzyy.healthcoach.data.model.InterventionEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UiState(
    val loading: Boolean = false,
    val syncing: Boolean = false,
    val days: List<HealthDay> = emptyList(),
    val feedback: List<FeedbackEntry> = emptyList(),
    /** 干预记录（补充剂 / 咖啡 / 运动…），用于观察因果关系 */
    val interventions: List<InterventionEntry> = emptyList(),
    /** AI 建议历史（含每次生成用的模型） */
    val adviceHistory: List<AdviceRecord> = emptyList(),
    /** WebDAV 备份配置 */
    val webDav: WebDavConfig = WebDavConfig(),
    val backingUp: Boolean = false,
    /** 上次备份结果（成功与否都显示） */
    val backupStatus: String? = null,
    /** AI 分析窗口天数 */
    val adviceWindowDays: Int = 7,
    val rootAvailable: Boolean = false,
    /** 仅云端模式：跳过本地库（无 root 场景的等效路径，也用于验证云端数据完整性） */
    val cloudOnly: Boolean = false,
    val tokenUsable: Boolean = false,
    val modelConfigured: Boolean = false,
    val modelPresetId: String = com.zzzyy.healthcoach.data.advice.ModelPreset.MINIMAX.id,
    val modelBaseUrl: String = com.zzzyy.healthcoach.data.advice.ModelPreset.MINIMAX.baseUrl,
    val modelName: String = com.zzzyy.healthcoach.data.advice.ModelPreset.MINIMAX.model,
    val modelApiKey: String = "",
    val statusMessage: String? = null,
    val syncDetail: String? = null,
    val advice: Advice? = null,
    val adviceLoading: Boolean = false,
    val adviceError: String? = null,
    val qrImageUrl: String? = null,
    val qrBitmap: android.graphics.Bitmap? = null,
    val loginWaiting: Boolean = false,
    /** 用户手动设定的作息目标（睡眠评分「节律」维度的锚点） */
    val sleepGoal: SleepScorer.Goal = SleepScorer.Goal(),
    /** 是否弹「昨晚睡得怎么样」打分卡 */
    val showMorningCheckIn: Boolean = false,
    /** 打卡卡里展示的昨晚睡眠摘要 */
    val lastNightSummary: MorningSummary? = null,
)

/** 睡醒打卡用的昨晚摘要。 */
data class MorningSummary(
    val date: String,
    val hours: Double,
    val deepMinutes: Int,
    val remMinutes: Int,
    val score: Int?,
    val grade: String?,
)

class HealthViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = HealthRepository(app)
    private val tokenStore = MiTokenStore(app)
    private val modelStore = ModelConfigStore(app)
    private val goalStore = SleepGoalStore(app)
    private val webDavStore = WebDavConfigStore(app)
    private val backup = HealthBackup(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val syncPrefs = app.getSharedPreferences("sync_state", android.content.Context.MODE_PRIVATE)

    init {
        // 一次性把历史上明文保存的凭据升级成密文
        runCatching {
            modelStore.migrateToEncrypted()
            webDavStore.migrateToEncrypted()
            tokenStore.migrateToEncrypted()
        }.onFailure {
            android.util.Log.e("HealthCoach.Secure", "凭据加密迁移失败：${it.message}")
        }
        importSeedToken()
        refresh()
        // 启动时自动同步：健康 app 应该「打开就有最新数据」，而不是逼用户点按钮。
        // 但 30 天的云端数据有 4 万多条心率记录，每次启动都拉一遍太浪费流量和时间，
        // 所以 6 小时内只同步一次。
        // 数据模型升级过（例如新增了 REM / 前后半夜心率）时，老数据里那些字段是 null，
        // 界面上会显示「无数据」。这时必须无视限频强制重同步一次，否则永远补不上。
        if (repository.needsUpgrade()) {
            android.util.Log.i("HealthCoach.Sync", "检测到落盘数据版本落后，强制重新同步一次")
            sync(days = 30, force = true, alsoCheckIn = true, fullPull = true)
        } else if (shouldSyncToday()) {
            android.util.Log.i("HealthCoach.Sync", "今天第一次打开（${loggingDayKey()}），同步一次")
            sync(days = 30, alsoCheckIn = true)
        } else {
            android.util.Log.i(
                "HealthCoach.Sync",
                "今天（${loggingDayKey()}）已经同步过，跳过自动同步",
            )
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val root = withContext(Dispatchers.IO) { repository.hasRoot() }
            _state.value = _state.value.copy(
                loading = true,
                days = repository.loadDays(),
                feedback = repository.loadFeedback(),
                interventions = repository.loadInterventions(),
                adviceHistory = repository.loadAdvices(),
                webDav = webDavStore.load(),
                adviceWindowDays = getApplication<Application>().getSharedPreferences("advice_prefs", android.content.Context.MODE_PRIVATE)
                    .getInt("window_days", 7),
                rootAvailable = root,
                cloudOnly = syncPrefs.getBoolean(KEY_CLOUD_ONLY, false),
                tokenUsable = repository.hasToken(),
                modelConfigured = modelStore.load().isConfigured,
                modelPresetId = modelStore.load().presetId,
                modelBaseUrl = modelStore.load().baseUrl,
                modelName = modelStore.load().model,
                modelApiKey = modelStore.load().apiKey,
                sleepGoal = goalStore.load(),
            )
            _state.value = _state.value.copy(loading = false)
        }
    }

    /**
     * 同步。
     *
     * 云端请求量口径（2026-11-15 按用户意见定）：
     *  - **首次**（从没同步过）→ 全量，把历史拉回来；
     *  - **之后每天第一次打开** → 只拉「昨天 00:00 起」，一般每个 key 一页，约 8~16 个请求；
     *  - **用户手动点「同步 N 天」** → [fullPull] = true，按所选窗口全量（用户明确要这段历史）。
     *
     * 如果这次增量一条新数据都没拉到，说明手机上的小米运动健康还没同步，
     * 界面提示「请先打开小米运动健康同步数据后重试」，而不是让用户以为 App 坏了。
     */
    fun sync(
        days: Int = 30,
        force: Boolean = false,
        alsoCheckIn: Boolean = false,
        fullPull: Boolean = false,
    ) {
        viewModelScope.launch {
            val lastSyncAt = syncPrefs.getLong(KEY_LAST_SYNC, 0L)
            // 判定逻辑抽成纯函数（data/cloud/SyncCadence.kt），有单测覆盖
            val incrementalSince = if (fullPull) 0L else {
                com.zzzyy.healthcoach.data.cloud.syncStartSeconds(
                    lastSyncAtMillis = lastSyncAt,
                    nowMillis = System.currentTimeMillis(),
                )
            }
            android.util.Log.i(
                "HealthCoach.Sync",
                "开始同步：$days 天，force=$force，fullPull=$fullPull，" +
                    "上次同步=${if (lastSyncAt > 0) java.util.Date(lastSyncAt) else "从未"}，" +
                    "云端起点=${if (incrementalSince > 0) java.util.Date(incrementalSince * 1000) else "全量"}",
            )
            _state.value = _state.value.copy(syncing = true, syncDetail = null)
            val cloudOnly = syncPrefs.getBoolean(KEY_CLOUD_ONLY, false)
            val report = repository.sync(days, incrementalSince, cloudOnly)
            val detail = buildString {
                append("本地 ${report.localDays} 天")
                if (report.localError != null) append("（${report.localError}）")
                append("，云端 ${report.cloudDays} 天")
                if (report.cloudError != null) append("（${report.cloudError}）")
                if (report.failedKeys.isNotEmpty()) {
                    append("（${report.failedKeys.size} 项拉取失败：${report.failedKeys.joinToString("、")}）")
                }
                append("，共 ${report.totalDays} 天")
            }
            val finishedAt = System.currentTimeMillis()
            syncPrefs.edit()
                .putLong(KEY_LAST_SYNC, finishedAt)
                .putString(KEY_LAST_SYNC_DAY, loggingDayKey())
                .apply()
            // 增量没拉到任何新记录 → 多半是手机上的小米运动健康还没同步，提示用户去那边先同步
            val staleNotice = if (!fullPull && report.tokenUsable && report.cloudError == null &&
                report.cloudRecords == 0
            ) {
                android.util.Log.i(
                    "HealthCoach.Sync",
                    "增量窗口内云端 0 条记录（本地 ${report.localDays} 天），提示用户先同步小米运动健康",
                )
                "本次没拉到新数据：请先打开「小米运动健康」同步一次，再回来点同步"
            } else {
                null
            }
            val loaded = repository.loadDays()
            _state.value = _state.value.copy(
                syncing = false,
                days = loaded,
                syncDetail = detail,
                rootAvailable = report.rootAvailable,
                tokenUsable = report.tokenUsable,
                statusMessage = staleNotice ?: _state.value.statusMessage,
            )
            if (alsoCheckIn) maybeShowMorningCheckIn(loaded)
            maybeAutoBackup()
        }
    }

    /**
     * 每天第一次打开时弹「昨晚睡得怎么样」。
     *
     * 为什么要问：手环能测时长和分期，但「睡醒感觉如何」只有人自己知道 ——
     * 刚醒来那几分钟的主观感受，是判断恢复质量最直接的一条信息，
     * 而且事后回忆会失真，所以必须趁刚醒问。
     */
    private fun maybeShowMorningCheckIn(days: List<com.zzzyy.healthcoach.data.model.HealthDay>) {
        val today = loggingDayKey()
        if (syncPrefs.getString(KEY_LAST_CHECKIN_DAY, null) == today) {
            android.util.Log.i("HealthCoach.CheckIn", "今天已经打过卡（$today），不再弹窗")
            return
        }
        val lastNight = days.lastOrNull { it.sleep != null } ?: return
        val sleep = lastNight.sleep ?: return
        val goal = goalStore.load()
        val score = SleepScorer.score(lastNight, days, goal)
        val summary = MorningSummary(
            date = lastNight.date,
            hours = sleep.hours,
            deepMinutes = sleep.deepMinutes,
            remMinutes = sleep.remMinutes,
            score = score?.total,
            grade = score?.grade,
        )
        android.util.Log.i(
            "HealthCoach.CheckIn",
            "弹出睡醒打卡：${summary.date} ${summary.hours}h 深睡${summary.deepMinutes} " +
                "REM${summary.remMinutes} 评分${summary.score ?: "-"}",
        )
        _state.value = _state.value.copy(showMorningCheckIn = true, lastNightSummary = summary)
    }

    /** 提交睡醒打分（只更新睡眠质量 / 精力，不覆盖当天其他反馈字段）。 */
    fun submitMorningCheckIn(sleepQuality: Int, energy: Int, note: String) {
        submitCheckIn(date = loggingDayKey(), sleepQuality, energy, note, fromMorningPrompt = true)
    }

    /**
     * 给指定日期补填/修改主观感受。
     *
     * 为什么需要补填：没有打分的那天只能用纯设备分，会被「打折过的日子」显得更高 ——
     * 这是数据缺失造成的不公平，唯一解法是让用户能补上。
     */
    fun submitCheckInForDate(
        date: String,
        sleepQuality: Int,
        energy: Int,
        note: String,
    ) {
        submitCheckIn(date, sleepQuality, energy, note, fromMorningPrompt = false)
    }

    private fun submitCheckIn(
        date: String,
        sleepQuality: Int,
        energy: Int,
        note: String,
        fromMorningPrompt: Boolean,
    ) {
        viewModelScope.launch {
            // 只跟「当天的打卡记录」合并，不要把用户白天随手记的内容一起改掉
            val existing = repository.loadFeedback()
                .firstOrNull { it.date == date && it.isMorningCheckIn }
            val merged = (existing ?: FeedbackEntry(date = date, timestamp = 0L)).copy(
                date = date,
                timestamp = System.currentTimeMillis(),
                source = if (fromMorningPrompt) FeedbackEntry.SOURCE_MORNING else FeedbackEntry.SOURCE_MANUAL,
                sleepQuality = sleepQuality.takeIf { it > 0 } ?: existing?.sleepQuality,
                energy = energy.takeIf { it > 0 } ?: existing?.energy,
                note = note.trim().ifBlank { existing?.note ?: "" },
            )
            repository.saveFeedback(merged)
            if (fromMorningPrompt) {
                syncPrefs.edit().putString(KEY_LAST_CHECKIN_DAY, loggingDayKey()).apply()
            }
            android.util.Log.i(
                "HealthCoach.CheckIn",
                "$date 感受已保存：睡眠质量${merged.sleepQuality ?: "-"} 精力${merged.energy ?: "-"}" +
                    if (fromMorningPrompt) "（早间弹窗）" else "（补填）",
            )
            _state.value = _state.value.copy(
                showMorningCheckIn = false,
                feedback = repository.loadFeedback(),
                statusMessage = "已记录 $date 的主观感受",
            )
        }
    }

    fun dismissMorningCheckIn() {
        syncPrefs.edit().putString(KEY_LAST_CHECKIN_DAY, loggingDayKey()).apply()
        _state.value = _state.value.copy(showMorningCheckIn = false)
        android.util.Log.i("HealthCoach.CheckIn", "用户跳过了今天的打卡")
    }

    /** 是否今天还没同步过（以 04:00 为日界：凌晨 3 点打开仍算前一天）。 */
    private fun shouldSyncToday(): Boolean =
        syncPrefs.getString(KEY_LAST_SYNC_DAY, null) != loggingDayKey()

    private fun loggingDayKey(): String {
        val calendar = java.util.Calendar.getInstance()
        // 04:00 之前算前一天，避免熬夜到凌晨时把「昨晚」算成两天
        calendar.add(java.util.Calendar.HOUR_OF_DAY, -4)
        return java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
            .format(calendar.time)
    }

    /** 追加一条干预记录（同日同名同剂量会去重）。 */
    fun addIntervention(entry: InterventionEntry) {
        viewModelScope.launch {
            val added = repository.addIntervention(entry)
            _state.value = _state.value.copy(
                interventions = repository.loadInterventions(),
                adviceHistory = repository.loadAdvices(),
                webDav = webDavStore.load(),
                adviceWindowDays = getApplication<Application>().getSharedPreferences("advice_prefs", android.content.Context.MODE_PRIVATE)
                    .getInt("window_days", 7),
                statusMessage = if (added) "已记录 ${entry.display}" else "${entry.display} 今天已经记过了",
            )
        }
    }

    fun deleteIntervention(entry: InterventionEntry) {
        viewModelScope.launch {
            repository.deleteIntervention(entry)
            _state.value = _state.value.copy(
                interventions = repository.loadInterventions(),
                adviceHistory = repository.loadAdvices(),
                webDav = webDavStore.load(),
                adviceWindowDays = getApplication<Application>().getSharedPreferences("advice_prefs", android.content.Context.MODE_PRIVATE)
                    .getInt("window_days", 7),
                statusMessage = "已删除 ${entry.display}",
            )
        }
    }

    fun saveFeedback(entry: FeedbackEntry) {
        viewModelScope.launch {
            repository.saveFeedback(entry)
            _state.value = _state.value.copy(
                feedback = repository.loadFeedback(),
                statusMessage = "已记录 ${entry.date} 的反馈",
            )
        }
    }

    /**
     * 生成健康建议。
     *
     * @param singleNight true = 只看昨晚（单日诊断），false = 看最近 N 天趋势
     */
    fun generateAdvice(singleNight: Boolean = false, fullCheckup: Boolean = false) {
        viewModelScope.launch {
            _state.value = _state.value.copy(adviceLoading = true, adviceError = null)
            try {
                val window = _state.value.adviceWindowDays
                val allDays = repository.loadDays()
                // 聚焦诊断：昨晚 + 前两晚作为参照（只看一天没有比较基准）
                val days = when {
                    fullCheckup -> allDays.takeLast(30)   // 体检用 30 天，样本更稳
                    singleNight -> allDays.takeLast(3)    // 聚焦诊断：昨晚 + 前两晚
                    else -> allDays.takeLast(window)
                }
                if (days.isEmpty()) {
                    _state.value = _state.value.copy(
                        adviceLoading = false,
                        adviceError = "还没有数据，先同步一次",
                    )
                    return@launch
                }
                val feedback = repository.loadFeedback()
                val interventions = repository.loadInterventions()
                val prompt = if (fullCheckup) {
                    buildFullCheckup(days, feedback, interventions)
                } else if (singleNight && days.isNotEmpty()) {
                    AdvicePrompt.buildFocused(
                        day = days.last(),
                        previousDays = days.dropLast(1),
                        feedback = feedback,
                        interventions = interventions,
                    )
                } else {
                    AdvicePrompt.build(days, feedback, interventions)
                }
                android.util.Log.i(
                    "HealthCoach.Advice",
                    "请求分析：${
                        when {
                            fullCheckup -> "全身体检"
                            singleNight -> "聚焦诊断（3 晚）"
                            else -> "${days.size} 天趋势"
                        }
                    }，" +
                        "prompt ${prompt.length} 字符",
                )
                val config = modelStore.load()
                val advice = AdviceClient(config).generate(prompt)
                // 存下来：几周后回看「当时模型说了什么」比只看今天这条有用，
                // 换模型后也能对比同一批数据下不同判断
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA)
                    .format(java.util.Date())
                repository.saveAdvice(
                    AdviceRecord(
                        date = today,
                        timestamp = System.currentTimeMillis(),
                        model = config.model + when {
                            fullCheckup -> "（全身体检·${days.size}天）"
                            singleNight -> "（聚焦3晚）"
                            else -> "（${days.size}天）"
                        },
                        headline = advice.headline,
                        summary = advice.summary,
                        suggestions = advice.items,
                        warnings = advice.warnings,
                        rawText = advice.rawText,
                        parseFailed = advice.parseFailed,
                    )
                )
                _state.value = _state.value.copy(
                    adviceLoading = false,
                    advice = advice,
                    adviceHistory = repository.loadAdvices(),
                webDav = webDavStore.load(),
                adviceWindowDays = getApplication<Application>().getSharedPreferences("advice_prefs", android.content.Context.MODE_PRIVATE)
                    .getInt("window_days", 7),
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    adviceLoading = false,
                    adviceError = e.message ?: e.javaClass.simpleName,
                )
            }
        }
    }

    /** 保存作息目标（睡眠评分的节律维度用这个，不用设备推断的习惯）。 */
    fun saveSleepGoal(goal: SleepScorer.Goal) {
        goalStore.save(goal)
        _state.value = _state.value.copy(
            sleepGoal = goal,
            statusMessage = "已保存作息目标：" +
                "${goal.bedtimeMinutes?.let { SleepScorer.fmt(it) } ?: "-"} → " +
                "${goal.wakeMinutes?.let { SleepScorer.fmt(it) } ?: "-"}",
        )
        android.util.Log.i("HealthCoach.Goal", "作息目标已保存：${goal}")
    }

    fun saveWebDavConfig(config: WebDavConfig) {
        webDavStore.save(config)
        _state.value = _state.value.copy(webDav = config, statusMessage = "WebDAV 配置已保存")
    }

    fun testWebDavConnection() {
        viewModelScope.launch {
            _state.value = _state.value.copy(backupStatus = "正在测试连接…")
            val result = WebDavClient(webDavStore.load()).testConnection()
            _state.value = _state.value.copy(
                backupStatus = result.fold(
                    onSuccess = { it },
                    onFailure = { "连接失败：${it.message}" },
                ),
            )
        }
    }

    /** 立即备份一次。 */
    fun backupNow() {
        viewModelScope.launch {
            _state.value = _state.value.copy(backingUp = true, backupStatus = "正在备份…")
            val result = backup.backupTo(webDavStore.load())
            android.util.Log.i(
                "HealthCoach.Backup",
                "备份结果：${if (result.ok) "成功" else "失败"} ${result.message}（${result.bytes} 字符）",
            )
            _state.value = _state.value.copy(
                backingUp = false,
                backupStatus = if (result.ok) {
                    "备份成功 · ${result.bytes / 1024} KB"
                } else {
                    "备份失败：${result.message}"
                },
            )
        }
    }

    /** 每天第一次打开时的自动备份（与自动同步同频）。 */
    private fun maybeAutoBackup() {
        val config = webDavStore.load()
        if (!config.isConfigured || !config.autoBackup) return
        val key = "last_backup_day"
        val today = loggingDayKey()
        if (syncPrefs.getString(key, null) == today) {
            android.util.Log.i("HealthCoach.Backup", "今天（$today）已备份过，跳过")
            return
        }
        viewModelScope.launch {
            val result = backup.backupTo(config)
            if (result.ok) {
                syncPrefs.edit().putString(key, today).apply()
                android.util.Log.i("HealthCoach.Backup", "自动备份完成：${result.message}")
            } else {
                android.util.Log.w("HealthCoach.Backup", "自动备份失败：${result.message}")
            }
            _state.value = _state.value.copy(
                backupStatus = if (result.ok) "今日自动备份完成" else "自动备份失败：${result.message}",
            )
        }
    }

    fun setAdviceWindowDays(days: Int) {
        getApplication<Application>()
            .getSharedPreferences("advice_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putInt("window_days", days).apply()
        _state.value = _state.value.copy(adviceWindowDays = days)
        android.util.Log.i("HealthCoach.Advice", "分析窗口改为 $days 天")
    }

    fun clearAdviceHistory() {
        repository.clearAdvices()
        _state.value = _state.value.copy(adviceHistory = emptyList(), statusMessage = "建议历史已清空")
    }

    fun saveModelConfig(config: ModelConfig) {
        modelStore.save(config)
        _state.value = _state.value.copy(
            modelConfigured = config.isConfigured,
            modelPresetId = config.presetId,
            modelBaseUrl = config.baseUrl,
            modelName = config.model,
            modelApiKey = config.apiKey,
            statusMessage = "已保存模型配置（${config.preset.label} / ${config.model}）",
        )
    }

    fun saveToken(token: MiToken) {
        tokenStore.save(token)
        _state.value = _state.value.copy(
            tokenUsable = token.isUsable,
            statusMessage = "已保存小米账号",
        )
    }

    /**
     * 手动用 passToken 登录（扫码失败时的兜底）。
     *
     * 为什么需要：扫码要依赖小米某个官方 App 支持扫这个码，版本和入口都可能变。
     * 而 passToken 可以从**浏览器 cookie** 里直接拿到（登录 account.xiaomi.com 后，
     * 在开发者工具里找 passToken 和 userId），这条路不依赖任何 App 的扫码能力。
     */
    fun loginWithPassTokenManual(userId: String, passToken: String) {
        viewModelScope.launch {
            if (userId.isBlank() || passToken.isBlank()) {
                android.util.Log.w(
                    "HealthCoach.Login",
                    "手动登录被拒：userId 为空=${userId.isBlank()}，passToken 为空=${passToken.isBlank()}",
                )
                _state.value = _state.value.copy(statusMessage = "userId 和 passToken 都要填")
                return@launch
            }
            // 只打长度，绝不打内容
            android.util.Log.i(
                "HealthCoach.Login",
                "手动登录开始：userId 长度=${userId.trim().length}，passToken 长度=${passToken.trim().length}",
            )
            _state.value = _state.value.copy(statusMessage = "正在用 passToken 登录…")
            try {
                val token = MiAuth().loginWithPassToken(
                    passToken = passToken.trim(),
                    userId = userId.trim(),
                    deviceId = "",
                )
                tokenStore.save(token)
                android.util.Log.i(
                    "HealthCoach.Login",
                    "手动登录结果：isUsable=${token.isUsable}，有 serviceToken=${token.serviceToken.isNotBlank()}，" +
                        "有 ssecurity=${token.ssecurity.isNotBlank()}",
                )
                _state.value = _state.value.copy(
                    tokenUsable = token.isUsable,
                    statusMessage = if (token.isUsable) "登录成功，可以同步云端数据了" else "凭证不完整，检查 passToken 是否正确",
                )
            } catch (e: Exception) {
                android.util.Log.e("HealthCoach.Login", "手动登录失败：${e.javaClass.simpleName} ${e.message}")
                _state.value = _state.value.copy(
                    statusMessage = "登录失败：${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    /** 扫码登录：先取二维码，再等用户扫。 */
    fun startQrLogin() {
        viewModelScope.launch {
            _state.value = _state.value.copy(qrImageUrl = null, loginWaiting = true, statusMessage = "正在获取二维码…")
            try {
                val auth = MiAuth()
                val session = withContext(Dispatchers.IO) { auth.requestQr() }
                val bitmap = withContext(Dispatchers.IO) { downloadImage(session.imageUrl) }
                _state.value = _state.value.copy(
                    qrImageUrl = session.imageUrl,
                    qrBitmap = bitmap,
                    statusMessage = "请用小米账号 App 扫一扫下面的二维码",
                )
                val token = auth.awaitScan(session)
                tokenStore.save(token)
                _state.value = _state.value.copy(
                    qrImageUrl = null,
                    loginWaiting = false,
                    tokenUsable = token.isUsable,
                    statusMessage = "登录成功",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    qrImageUrl = null,
                    loginWaiting = false,
                    statusMessage = "登录失败：${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    private companion object {
        const val KEY_LAST_SYNC = "last_sync_at"
        const val KEY_LAST_SYNC_DAY = "last_sync_day"
        const val KEY_LAST_CHECKIN_DAY = "last_checkin_day"
        const val KEY_CLOUD_ONLY = "cloud_only"
    }

    /**
     * 开关「仅云端模式」。
     *
     * 为什么要这个开关：
     *  1. 给**没有 root** 的用户一个明确路径（他们的真实场景就是只有云端）；
     *  2. 验证云端数据是否完整 —— 有 root 时云端结果会被本地覆盖合并，
     *     不开这个开关就永远看不出云端单独能撑到什么程度。
     */
    fun setCloudOnly(enabled: Boolean) {
        syncPrefs.edit().putBoolean(KEY_CLOUD_ONLY, enabled).apply()
        android.util.Log.i("HealthCoach.Sync", "仅云端模式 -> $enabled（下次同步生效）")
        _state.value = _state.value.copy(
            cloudOnly = enabled,
            statusMessage = if (enabled) "已开启仅云端模式：下次同步跳过本地库" else "已关闭仅云端模式",
        )
    }

    fun dismissMessage() {
        _state.value = _state.value.copy(statusMessage = null)
    }

    /**
     * 用已保存的 passToken 换取新凭证（免扫码）。
     *
     * serviceToken / ssecurity 会过期，但 passToken 一直有效，
     * 所以第一次扫码之后就不需要再扫了。
     */
    fun refreshTokenWithPassToken() {
        viewModelScope.launch {
            val current = tokenStore.load()
            if (current.passToken.isBlank() || current.userId.isBlank()) {
                _state.value = _state.value.copy(
                    statusMessage = "还没有 passToken，需要先扫码登录一次",
                )
                return@launch
            }
            _state.value = _state.value.copy(statusMessage = "正在用 passToken 续期…")
            try {
                val token = MiAuth().loginWithPassToken(
                    passToken = current.passToken,
                    userId = current.userId,
                    deviceId = current.deviceId,
                )
                tokenStore.save(token)
                _state.value = _state.value.copy(
                    tokenUsable = token.isUsable,
                    statusMessage = "凭证已续期",
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    statusMessage = "续期失败：${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    /**
     * 导入电脑上推过来的 token 种子文件。
     *
     * 路径固定为 app 私有目录下的 `mi_token_seed.json`，
     * 导入成功后删除，避免长期留一份明文凭证在磁盘上。
     */
    fun importSeedToken() {
        viewModelScope.launch {
            val seed = java.io.File(getApplication<Application>().filesDir, "mi_token_seed.json")
            if (!seed.exists()) return@launch
            val ok = tokenStore.importFromJson(seed.readText())
            seed.delete()
            _state.value = _state.value.copy(
                tokenUsable = tokenStore.load().isUsable,
                statusMessage = if (ok) "已导入小米账号凭证" else "导入失败：文件格式不对",
            )
        }
    }

    /** 下载二维码图片。 */
    private fun downloadImage(url: String): android.graphics.Bitmap? = try {
        val request = okhttp3.Request.Builder().url(url).get().build()
        okhttp3.OkHttpClient().newCall(request).execute().use { response ->
            val bytes = response.body?.bytes()
            if (bytes != null) {
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}
