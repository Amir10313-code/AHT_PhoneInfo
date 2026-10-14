# MAHIREX Pulse - R8 rules.
# The app is built from plain Android views and does not load its own classes by name, so the
# default optimised rules from the Android Gradle Plugin are enough. Nothing is kept "to hide" code.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
# the only reflective call targets a platform class (battery design capacity); it is not part of the app
-dontwarn com.android.internal.os.PowerProfile
