package com.denoya.xenorush

import android.app.Activity
import android.content.Context
import android.os.CancellationSignal
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.concurrent.Executors

/**
 * Google sign-in (Firebase Auth via Credential Manager) and cloud save (Firestore users/{uid}).
 * Everything here is optional: if app/google-services.json was not present at build time,
 * [available] is false and the game runs exactly as before, fully offline.
 * Nothing in this class runs unless the player opens ACCOUNT / MULTIPLAYER or is already signed in.
 */
class Cloud(private val context: Context) {
    val app: FirebaseApp? = try {
        FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
    } catch (_: Exception) { null }

    val available: Boolean get() = app != null && webClientId() != null
    private val exec = Executors.newSingleThreadExecutor()

    private fun auth(): FirebaseAuth? = app?.let { FirebaseAuth.getInstance(it) }

    /** Cached locally by Firebase - no network needed to know who is signed in. */
    fun uid(): String? = try { auth()?.currentUser?.uid } catch (_: Exception) { null }
    fun displayName(): String = try {
        val u = auth()?.currentUser
        (u?.displayName?.takeIf { it.isNotBlank() } ?: u?.email?.substringBefore("@") ?: "Racer")
    } catch (_: Exception) { "Racer" }
    fun email(): String = try { auth()?.currentUser?.email ?: "" } catch (_: Exception) { "" }

    private fun webClientId(): String? {
        val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
        return if (id == 0) null else context.getString(id)
    }

    fun signIn(activity: Activity, done: (ok: Boolean, msg: String) -> Unit) {
        val a = auth(); val clientId = webClientId()
        if (a == null || clientId == null) { done(false, "Online features not configured (google-services.json missing)"); return }
        val option = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        activity.runOnUiThread {
            CredentialManager.create(activity).getCredentialAsync(activity, request, CancellationSignal(), exec,
                object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                    override fun onResult(result: GetCredentialResponse) {
                        val cred = result.credential
                        if (cred is CustomCredential && cred.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                            try {
                                val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                                a.signInWithCredential(GoogleAuthProvider.getCredential(token, null))
                                    .addOnSuccessListener { done(true, "Signed in as ${displayName()}") }
                                    .addOnFailureListener { done(false, "Sign-in failed: ${it.message ?: "error"}") }
                            } catch (e: Exception) { done(false, "Sign-in failed: ${e.message ?: "bad token"}") }
                        } else done(false, "Unexpected credential type")
                    }
                    override fun onError(e: GetCredentialException) { done(false, "Sign-in cancelled or failed: ${e.type}") }
                })
        }
    }

    fun signOut() { try { auth()?.signOut() } catch (_: Exception) {} }

    /**
     * Downloads the cloud save, merges it with [localJson] (see SaveMerge) and uploads the result.
     * [done] receives the merged save JSON, or null + error message.
     */
    fun sync(localJson: String, done: (merged: String?, msg: String) -> Unit) {
        val u = uid(); val fa = app
        if (u == null || fa == null) { done(null, "Not signed in"); return }
        val doc = FirebaseFirestore.getInstance(fa).collection("users").document(u)
        doc.get().addOnSuccessListener { snap ->
            try {
                val local = SaveCodec.fromJson(localJson)
                val remote = SaveCodec.fromJson(snap.getString("save"))
                val merged = if (snap.exists()) SaveMerge.merge(local, remote) else local
                val cloudJson = SaveCodec.toJson(merged, includeLocal = false)
                doc.set(mapOf("save" to cloudJson, "updatedAt" to merged.updatedAt, "name" to displayName()), SetOptions.merge())
                    .addOnSuccessListener { done(SaveCodec.toJson(merged), "Cloud save synced") }
                    .addOnFailureListener { done(SaveCodec.toJson(merged), "Merged, but upload failed: ${it.message}") }
            } catch (e: Exception) { done(null, "Sync failed: ${e.message}") }
        }.addOnFailureListener { done(null, "Sync failed: ${it.message ?: "network error"}") }
    }
}
