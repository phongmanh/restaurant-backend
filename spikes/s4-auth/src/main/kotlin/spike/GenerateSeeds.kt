package spike

/** Prints PHC strings for Seeds.kt: `./gradlew -q generateSeeds`. Run once; the output is pasted in. */
fun main() {
    val bc = BouncyCastleArgon2()
    println("password: " + bc.hash(Seeds.PASSWORD))
    println("dummy:    " + bc.hash("not a password: unknown emails are checked against this"))
}
