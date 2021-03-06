package com.machiav3lli.backup.tasks

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.machiav3lli.backup.R
import com.machiav3lli.backup.activities.MainActivityX
import com.machiav3lli.backup.dbs.Schedule
import com.machiav3lli.backup.handler.BackupRestoreHelper
import com.machiav3lli.backup.handler.NotificationHelper
import com.machiav3lli.backup.items.ActionResult
import com.machiav3lli.backup.items.AppInfo
import com.machiav3lli.backup.utils.LogUtils


class ScheduledWork(val context: Context, workerParams: WorkerParameters) : Worker(context, workerParams) {
    private val TAG = ScheduledWork::class.java.simpleName

    override fun doWork(): Result {
        val selectedPackages = inputData.getStringArray("selectedPackages")?.toList()
                ?: listOf()
        val blackList = inputData.getStringArray("blackList")?.toList()
                ?: listOf()
        val selectedMode = inputData.getInt("selectedMode", Schedule.SubMode.BOTH.value)
        val notificationId = inputData.getInt("notificationId", 123454321)

        val results: MutableList<ActionResult> = mutableListOf()
        val packageInfoList = context.packageManager.getInstalledPackages(0)
        val selectedApps: MutableList<AppInfo> = mutableListOf()
        selectedPackages.forEach { packageName ->
            val foundItem = packageInfoList.find { it.packageName == packageName }
            // TODO cover special backups scenario
            if (foundItem != null) {
                selectedApps.add(AppInfo(context, foundItem))
            } else {
                results.add(ActionResult(null, null, "Selected backup for processing can't be found.", false))
            }
        }
        val totalOfActions = selectedPackages.size
        var i = 1
        var packageLabel = "NONE"
        try {
            selectedApps.forEach { appInfo ->
                packageLabel = appInfo.packageLabel
                if (blackList.contains(appInfo.packageName)) {
                    Log.i(TAG, "${appInfo.packageName} ignored")
                    i++
                    return@forEach
                }
                val title = "${context.getString(R.string.backupProgress)} ($i/$totalOfActions)"
                NotificationHelper.showNotification(context, MainActivityX::class.java, notificationId, title, appInfo.packageLabel, false)
                var result: ActionResult? = null
                try {
                    result = BackupRestoreHelper.backup(context, MainActivityX.shellHandlerInstance!!, appInfo, selectedMode)
                } catch (e: Throwable) {
                    result = ActionResult(appInfo, null, "not processed: $packageLabel: $e", false)
                    Log.w(TAG, "package: ${appInfo.packageLabel} result: $e")
                } finally {
                    result?.let {
                        if (it.succeeded)
                            NotificationHelper.showNotification(context, MainActivityX::class.java, it.hashCode(), appInfo.packageLabel, it.message, false)
                        results.add(it)
                    }
                    i++
                }
            }
        } catch (e: Throwable) {
            LogUtils.unhandledException(e, packageLabel)
        } finally {
            val errors = results
                    .map { it.message }
                    .filter { it.isNotEmpty() }
                    .joinToString(separator = "\n")
            val resultsSuccess = results.parallelStream().anyMatch(ActionResult::succeeded)

            val overAllResult = ActionResult(null, null, errors, resultsSuccess)
            val notificationMessage = when {
                overAllResult.succeeded || selectedPackages.isEmpty() -> context.getString(R.string.batchSuccess)
                else -> context.getString(R.string.batchFailure)
            }
            val notificationTitle = context.getString(R.string.sched_notificationMessage)
            NotificationHelper.showNotification(context, MainActivityX::class.java, notificationId, notificationTitle, notificationMessage, true)
            if (!overAllResult.succeeded) {
                LogUtils.logErrors(context, errors)
            }

            // for (l in listeners) l.onBackupRestoreDone()

            return Result.success()
        }
    }
}