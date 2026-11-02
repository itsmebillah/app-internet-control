package com.itsmebillah.appinternetcontrol

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

class UsageCollectionJobService : JobService() {
    private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        worker = Thread({
            UsageCollectionRunner.collect(applicationContext)
            jobFinished(params, false)
        }, "usage-stats-collection").apply { start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        worker?.interrupt()
        worker = null
        return false
    }

    companion object {
        private const val PERIODIC_JOB_ID = 6_801
        private const val PERIODIC_INTERVAL_MILLIS = 6L * 60 * 60 * 1000

        fun schedule(context: Context): Boolean {
            val job = JobInfo.Builder(PERIODIC_JOB_ID, ComponentName(context, UsageCollectionJobService::class.java))
                .setPersisted(true)
                .setPeriodic(PERIODIC_INTERVAL_MILLIS)
                .build()
            return context.getSystemService(JobScheduler::class.java).schedule(job) == JobScheduler.RESULT_SUCCESS
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java).cancel(PERIODIC_JOB_ID)
        }
    }
}
