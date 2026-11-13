package cz.faltyn.elfall.shared

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

actual fun secureRandom(): Random = SecureRandom().asKotlinRandom()
