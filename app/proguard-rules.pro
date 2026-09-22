# Release shrinking rules. Every library in use (Room, OkHttp, Media3, Compose, WorkManager,
# DataStore, coroutines) ships its own consumer rules, so nothing app-specific is needed yet.
# Room entities and DAOs are referenced from KSP-generated code, not by reflection, and the
# only class instantiated by name is EpgRefreshWorker, which WorkManager's own rules keep.

# Keep the line-number table so a release stack trace from a Fire Stick can be read.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
