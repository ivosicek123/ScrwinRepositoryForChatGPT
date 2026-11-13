package cz.faltyn.elfall.backend.bankid

import com.webauthn4j.data.AuthenticatorAssertionResponse
import com.webauthn4j.data.PublicKeyCredentialCreationOptions
import com.webauthn4j.data.PublicKeyCredentialDescriptor
import com.webauthn4j.data.PublicKeyCredentialParameters
import com.webauthn4j.data.PublicKeyCredentialRequestOptions
import com.webauthn4j.data.PublicKeyCredentialRpEntity
import com.webauthn4j.data.PublicKeyCredentialType
import com.webauthn4j.data.PublicKeyCredentialUserEntity
import com.webauthn4j.data.UserVerificationRequirement
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier
import com.webauthn4j.data.client.Origin
import com.webauthn4j.data.client.challenge.DefaultChallenge
import com.webauthn4j.test.client.ClientPlatform
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PasskeyTest {
    private val cfg = PasskeyConfig("elfall.test", "ELFALL", "https://elfall.test")
    private val svc = PasskeyService(cfg)
    private val enc = Base64.getUrlEncoder().withoutPadding(); private val dec = Base64.getUrlDecoder()
    private fun b(s: String) = dec.decode(s); private fun s(b: ByteArray) = enc.encodeToString(b)
    private fun platform(origin: String = "https://elfall.test") = ClientPlatform(Origin(origin), com.webauthn4j.test.authenticator.webauthn.WebAuthnAuthenticatorAdaptor(com.webauthn4j.test.authenticator.webauthn.PackedAuthenticator()))

    private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content

    private fun registerWith(cp: ClientPlatform, user: String = "ivo-test"): Pair<String, String> {
        val o = svc.registrationOptions(user)
        val u = o["user"]!!.jsonObject
        val opts = PublicKeyCredentialCreationOptions(
            PublicKeyCredentialRpEntity("elfall.test", "ELFALL"),
            PublicKeyCredentialUserEntity(b(u.str("id")), u.str("name"), u.str("displayName")),
            DefaultChallenge(b(o.str("challenge"))),
            listOf(PublicKeyCredentialParameters(PublicKeyCredentialType.PUBLIC_KEY, COSEAlgorithmIdentifier.ES256)),
            null, emptyList(),
            com.webauthn4j.data.AuthenticatorSelectionCriteria(null, com.webauthn4j.data.ResidentKeyRequirement.REQUIRED, com.webauthn4j.data.UserVerificationRequirement.REQUIRED),
            com.webauthn4j.data.AttestationConveyancePreference.NONE, null
        )
        val cred = cp.create(opts)
        return o.str("challenge") to svc.register(o.str("challenge"), s(cred.response!!.attestationObject), s(cred.response!!.clientDataJSON))
    }

    private fun assertion(cp: ClientPlatform, id: String, user: String? = "ivo-test", challenge: String? = null): Array<String> {
        val o = svc.authenticationOptions(user)
        val ch = challenge ?: o.str("challenge")
        val opts = PublicKeyCredentialRequestOptions(DefaultChallenge(b(o.str("challenge"))), 60_000L, "elfall.test",
            listOf(PublicKeyCredentialDescriptor(PublicKeyCredentialType.PUBLIC_KEY, b(id), null)), UserVerificationRequirement.REQUIRED, null)
        val r: AuthenticatorAssertionResponse = cp.get(opts).response!!
        return arrayOf(ch, id, s(r.authenticatorData), s(r.clientDataJSON), s(r.signature))
    }

    @Test fun optionsPolicy() {
        val o = svc.registrationOptions("ivo-test")
        val algs = o["pubKeyCredParams"]!!.jsonArray.map { it.jsonObject.str("alg").toLong() }
        assertEquals(listOf(-7L, -8L), algs) // ES256, EdDSA; žádné RS256
        assertEquals("required", o["authenticatorSelection"]!!.jsonObject.str("userVerification"))
        assertEquals(43, o.str("challenge").length)
    }

    @Test fun registerAndLogin() {
        val cp = platform(); val (_, id) = registerWith(cp)
        val a = assertion(cp, id)
        assertEquals("ivo-test", svc.authenticate(a[0], a[1], a[2], a[3], a[4], null))
    }

    @Test fun registrationChallengeSingleUse() {
        val cp = platform(); val (ch, _) = registerWith(cp)
        assertFailsWith<PasskeyError> { svc.register(ch, "AA", "AA") }
    }

    @Test fun wrongOriginRejected() {
        assertFailsWith<PasskeyError> { registerWith(platform("https://evil.example")) }
    }

    @Test fun replayedAssertionRejected() {
        val cp = platform(); val (_, id) = registerWith(cp)
        val a = assertion(cp, id)
        svc.authenticate(a[0], a[1], a[2], a[3], a[4], null)
        assertFailsWith<PasskeyError> { svc.authenticate(a[0], a[1], a[2], a[3], a[4], null) }
    }

    @Test fun assertionForOtherChallengeRejected() {
        val cp = platform(); val (_, id) = registerWith(cp)
        val other = svc.authenticationOptions("ivo-test").str("challenge")
        val a = assertion(cp, id, challenge = other)
        assertFailsWith<PasskeyError> { svc.authenticate(a[0], a[1], a[2], a[3], a[4], null) }
    }

    @Test fun tamperedSignatureRejected() {
        val cp = platform(); val (_, id) = registerWith(cp)
        val a = assertion(cp, id)
        val sig = b(a[4]); sig[sig.size - 1] = (sig.last() + 1).toByte()
        assertFailsWith<PasskeyError> { svc.authenticate(a[0], a[1], a[2], a[3], s(sig), null) }
    }

    @Test fun credentialOfOtherUserRejected() {
        val cp = platform(); val (_, id) = registerWith(cp, "alice")
        val a = assertion(cp, id, user = "bob")
        assertFailsWith<PasskeyError> { svc.authenticate(a[0], a[1], a[2], a[3], a[4], null) }
    }

    @Test fun invalidUsernameRejected() { assertFailsWith<IllegalArgumentException> { svc.registrationOptions("<script>") } }
    @Test fun configRequiresHttpsAndMatchingRp() {
        assertFailsWith<IllegalArgumentException> { PasskeyConfig("elfall.test", "x", "http://elfall.test") }
        assertFailsWith<IllegalArgumentException> { PasskeyConfig("elfall.test", "x", "https://evil.example") }
        assertTrue(PasskeyConfig.fromEnv(emptyMap()) == null)
    }
}
