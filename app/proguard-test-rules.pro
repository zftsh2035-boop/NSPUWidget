# AndroidX Test uses these compile-time annotations; they have no Android runtime.
# Keep this suppression in the test APK only, not the installable application.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.MustBeClosed

# The runner selects this class by its name in `am instrument -e class`.
-keep class ru.nspu.schedule.ScheduleScrollTest { *; }
-keep class ru.nspu.schedule.ParkedSyncWorker { *; }
