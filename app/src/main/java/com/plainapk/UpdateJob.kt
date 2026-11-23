package com.plainapk

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** The daily background check: finds new releases and installs them. "Check now" in the app covers anything sooner. */
class UpdateJob : JobService() {
    override fun onStartJob(p: JobParameters): Boolean {
        Work.init(this)
        Work.stopRequested = false
        Work.run {
            try { Work.checkAll(install = true) } finally { jobFinished(p, false) }
        }
        return true
    }

    override fun onStopJob(p: JobParameters): Boolean {
        Work.stopRequested = true // the download in progress gives up; the next run picks it up again
        return true
    }

    companion object {
        private const val INTERVAL = 24 * 3600_000L

        fun schedule(c: Context) {
            val js = c.getSystemService(JobScheduler::class.java)
            // Already scheduled at this interval; an older install's hourly job gets replaced
            if (js.getPendingJob(1)?.intervalMillis == INTERVAL) return
            js.schedule(JobInfo.Builder(1, ComponentName(c, UpdateJob::class.java))
                .setPeriodic(INTERVAL)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build())
        }
    }
}
