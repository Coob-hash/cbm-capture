# Project-specific R8 rules for release builds.
#
# The libraries this app uses (Retrofit, OkHttp, kotlinx.serialization, Room, Hilt, ARCore) ship
# their own consumer rules. Add rules here only for code reached by reflection that R8 cannot see.
# https://developer.android.com/build/shrink-code

# Tink (through androidx.security:security-crypto) references Error Prone annotations, which are
# needed at compile time only.
-dontwarn com.google.errorprone.annotations.**
